package app.lynli.klang.jam

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

internal class Peers(context: Context, private val scope: CoroutineScope, settings: JSONObject,
    private val sendSignal: suspend (String, String, JSONObject) -> Unit,
    private val accept: (String, JSONObject) -> Boolean,
    private val received: suspend (String, JSONObject, File) -> Unit,
    private val changed: (String, String) -> Unit,
    private val opened: (String) -> Unit) {
    private val directory = File(context.cacheDir, "incoming").apply { deleteRecursively(); mkdirs() }
    // A solo room only needs Supabase. Load native WebRTC when a peer actually connects.
    private val factoryDelegate = lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        val audio = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(false).setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        try {
            audio.setAudioRecordEnabled(false)
            PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
        } finally { audio.release() }
    }
    private val factory by factoryDelegate
    private val peers = mutableMapOf<String, Peer>()
    private val servers = buildList {
        settings.optString("stunUrl", "stun:stun.l.google.com:19302").takeIf { it.isNotBlank() }?.let {
            require(it.startsWith("stun:") || it.startsWith("stuns:")); add(PeerConnection.IceServer.builder(it).createIceServer())
        }
        settings.optString("turnUrl").takeIf { it.isNotBlank() }?.let {
            require(it.startsWith("turn:") || it.startsWith("turns:"))
            add(PeerConnection.IceServer.builder(it).setUsername(settings.optString("turnUsername"))
                .setPassword(settings.optString("turnPassword")).createIceServer())
        }
    }
    fun ensure(id: String, offer: Boolean) {
        if (id in peers) return
        val peer = Peer(id); peers[id] = peer
        var initialized = false
        try { peer.init(offer); initialized = true }
        finally { if (!initialized) { peers.remove(id); peer.close() } }
    }
    fun removeExcept(ids: Set<String>) { peers.keys.toList().filter { it !in ids }.forEach { peers.remove(it)?.close() } }
    fun retryOffer(id: String) {
        val peer = peers[id] ?: return
        if (peer.channel?.state() == DataChannel.State.OPEN) return
        val description = peer.connection.localDescription ?: return
        if (description.type == SessionDescription.Type.OFFER) scope.launch {
            runCatching { sendSignal(id, "offer", JSONObject().put("sdp", description.description)) }
        }
    }
    fun isOpen(id: String) = peers[id]?.channel?.state() == DataChannel.State.OPEN
    suspend fun send(id: String, metadata: JSONObject, file: File) { requireNotNull(peers[id]).send(metadata, file) }
    fun signal(id: String, event: String, payload: JSONObject) {
        ensure(id, false)
        peers[id]?.signal(event, payload)
    }
    fun close() { peers.values.forEach { it.close() }; peers.clear(); if (factoryDelegate.isInitialized()) factory.dispose(); directory.deleteRecursively() }
    private inner class Peer(val id: String) {
        lateinit var connection: PeerConnection
        var channel: DataChannel? = null
        private val sendLock = Mutex()
        private var incoming: Channel<Pair<Boolean, ByteArray>>? = null
        private val pendingIce = mutableListOf<IceCandidate>()
        private var remoteDescription = false
        private var transfer: Job? = null
        @Volatile private var closed = false
        private val acknowledged = AtomicLong(0)
        @Volatile private var completedHash = ""
        private var reportedOpen = false
        private fun callback(block: () -> Unit) = scope.launch { if (!closed) block() }
        fun init(offer: Boolean) {
            val config = PeerConnection.RTCConfiguration(servers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            connection = requireNotNull(factory.createPeerConnection(config, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    callback {
                        if (state == PeerConnection.IceConnectionState.FAILED) changed(id, "Direct connection failed. Try an optional TURN relay.")
                        if (state == PeerConnection.IceConnectionState.DISCONNECTED) changed(id, "Reconnecting")
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidate(candidate: IceCandidate) { scope.launch {
                    if (closed) return@launch
                    runCatching { sendSignal(id, "ice", JSONObject().put("candidate", candidate.sdp).put("mid", candidate.sdpMid).put("line", candidate.sdpMLineIndex)) }
                } }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dataChannel: DataChannel) { scope.launch {
                    if (closed) { dataChannel.close(); dataChannel.dispose() } else attach(dataChannel)
                } }
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            }))
            // Files use SCTP data channels. Klang owns playback; WebRTC never records or plays audio.
            connection.setAudioRecording(false)
            connection.setAudioPlayout(false)
            if (offer) {
                attach(connection.createDataChannel("jam-audio-v1", DataChannel.Init().apply { ordered = true }))
                connection.createOffer(observer { sdp -> local(sdp, "offer") }, MediaConstraints())
            }
            scope.launch { delay(20_000); if (!closed && !isOpen(id)) changed(id, "Could not connect directly. Try an optional TURN relay.") }
        }
        private fun local(sdp: SessionDescription, event: String) {
            connection.setLocalDescription(observer {}, sdp)
            scope.launch { runCatching { sendSignal(id, event, JSONObject().put("sdp", sdp.description)) } }
        }
        fun signal(event: String, payload: JSONObject) {
            when (event) {
                "offer", "answer" -> {
                    val text = payload.getString("sdp").also { require(it.length <= 50000) }
                    connection.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() { callback {
                            remoteDescription = true; pendingIce.forEach { connection.addIceCandidate(it) }; pendingIce.clear()
                            if (event == "offer") connection.createAnswer(observer { sdp -> local(sdp, "answer") }, MediaConstraints())
                        } }
                        override fun onSetFailure(error: String) { callback { changed(id, "Connection negotiation failed") } }
                        override fun onCreateSuccess(sdp: SessionDescription) {}
                        override fun onCreateFailure(error: String) {}
                    }, SessionDescription(if (event == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER, text))
                }
                "ice" -> {
                    val candidate = IceCandidate(payload.optString("mid"), payload.getInt("line"), payload.getString("candidate").also { require(it.length < 4096) })
                    if (remoteDescription) connection.addIceCandidate(candidate) else if (pendingIce.size < 100) pendingIce += candidate
                }
            }
        }
        private fun attach(dataChannel: DataChannel) {
            if (channel != null || dataChannel.label() != "jam-audio-v1") { dataChannel.close(); return }
            channel = dataChannel
            incoming = Channel(32)
            fun channelState() {
                if (closed) return
                if (dataChannel.state() == DataChannel.State.OPEN && !reportedOpen) {
                    reportedOpen = true; changed(id, "Connected"); opened(id)
                } else if (dataChannel.state() == DataChannel.State.CLOSED) {
                    incoming?.close(); changed(id, "Disconnected")
                }
            }
            dataChannel.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previous: Long) {}
                override fun onStateChange() { callback(::channelState) }
                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (closed) return
                    if (buffer.data.remaining() > 16 * 1024) { scope.launch { close() }; return }
                    val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
                    if (!buffer.binary) {
                        val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                        if (json?.has("ack") == true) { acknowledged.set(json.optLong("ack")); return }
                        if (json?.has("complete") == true) { scope.launch { completedHash = json.optString("complete") }; return }
                    }
                    if (incoming?.trySend(buffer.binary to bytes)?.isSuccess != true) scope.launch { changed(id, "Transfer interrupted"); close() }
                }
            })
            // onDataChannel may deliver an already OPEN receiver channel. Observers
            // are not guaranteed to replay its transition after registration.
            channelState()
            transfer = scope.launch {
                try {
                    val input = requireNotNull(incoming)
                    while (isActive) {
                        val start = input.receive()
                        require(!start.first)
                        val meta = JSONObject(start.second.toString(Charsets.UTF_8))
                        val hash = meta.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                        val size = meta.getLong("bytes").also { require(it in 1..256 * 1024 * 1024L) }
                        require(accept(id, meta)) { "Unsolicited audio" }
                        require(directory.listFiles().orEmpty().sumOf { it.length() } + size <= 512 * 1024 * 1024L)
                        val file = File(directory, "$hash.part")
                        require(!file.exists())
                        try {
                            withTimeout(120_000) {
                                withContext(Dispatchers.IO) {
                                    val digest = MessageDigest.getInstance("SHA-256")
                                    file.outputStream().use { output ->
                                        var total = 0L
                                        while (true) {
                                            val chunk = input.receive()
                                            if (!chunk.first) {
                                                require(JSONObject(chunk.second.toString(Charsets.UTF_8)).getString("end") == hash)
                                                break
                                            }
                                            total += chunk.second.size; require(total <= size)
                                            digest.update(chunk.second); output.write(chunk.second)
                                            frame(dataChannel, JSONObject().put("ack", total).toString().toByteArray(), false)
                                        }
                                        require(total == size && digest.digest().joinToString("") { "%02x".format(it) } == hash)
                                    }
                                }
                            }
                            received(id, meta, file)
                            frame(dataChannel, JSONObject().put("complete", hash).toString().toByteArray(), false)
                        } finally { file.delete() }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { changed(id, "Audio transfer failed: ${e.message}"); close() }
            }
        }
        suspend fun send(metadata: JSONObject, file: File) = sendLock.withLock {
            val dataChannel = requireNotNull(channel)
            require(dataChannel.state() == DataChannel.State.OPEN)
            changed(id, "Syncing audio")
            acknowledged.set(0); completedHash = ""
            withTimeout(120_000) {
                frame(dataChannel, metadata.toString().toByteArray(), false)
                withContext(Dispatchers.IO) {
                    file.inputStream().use { input ->
                        val bytes = ByteArray(16 * 1024)
                        var sent = 0L
                        while (true) {
                            ensureActive(); val count = input.read(bytes); if (count < 0) break
                            while (sent - acknowledged.get() >= 128 * 1024) { delay(5); requireOpen(dataChannel) }
                            frame(dataChannel, bytes.copyOf(count), true); sent += count
                        }
                    }
                }
                frame(dataChannel, JSONObject().put("end", metadata.getString("sha256")).toString().toByteArray(), false)
                while (completedHash != metadata.getString("sha256")) { delay(10); requireOpen(dataChannel) }
            }
        }
        private suspend fun requireOpen(dataChannel: DataChannel) = withContext(Dispatchers.Main) {
            require(!closed && dataChannel.state() == DataChannel.State.OPEN) { "Peer connection closed" }
        }
        private suspend fun frame(dataChannel: DataChannel, bytes: ByteArray, binary: Boolean) = withContext(Dispatchers.Main) {
            while (true) {
                requireOpen(dataChannel)
                // Serialize native calls with disposal. send consumes the ByteBuffer even on failure.
                if (dataChannel.bufferedAmount() <= 128 * 1024 &&
                    dataChannel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), binary))) break
                delay(10)
            }
        }
        fun close() { if (closed) return; closed = true; transfer?.cancel(); incoming?.close(); channel?.unregisterObserver(); channel?.close(); channel?.dispose(); if (::connection.isInitialized) { connection.close(); connection.dispose() } }
        private fun observer(created: (SessionDescription) -> Unit) = object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { callback { created(sdp) } }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { callback { changed(id, "WebRTC negotiation failed") } }
            override fun onSetFailure(error: String) { callback { changed(id, "WebRTC negotiation failed") } }
        }
    }
}
