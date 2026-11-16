package app.lynli.klang.jam

import android.app.Service
import android.content.Intent
import android.os.*
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Entire Jam session, synchronization and UI model live in this separately installed plugin. */
class JamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, failure -> reportFailure(failure) })
    private val host = Host()
    private var callerUid = -1
    private var callerToken = ""
    private var settings = JSONObject()
    private var realtime: Realtime? = null
    private var peers: Peers? = null
    private var room: JSONObject? = null
    private var snapshot = JSONObject()
    private val queue = JamQueue()
    private val playbackAnchor = PlaybackAnchor()
    private val localReferences = mutableMapOf<String, String>()
    private val announcedUploads = mutableSetOf<String>()
    private val notices = mutableSetOf<String>()
    private var installedQueue = ""
    private var lastPlayback: JSONObject? = null
    private var desiredPlaying = false
    private var switchingKey = ""
    private var lastCheckpoint = 0L
    private var lastClockPing = 0L
    private val current get() = queue.current
    private val next get() = if (leader && switchingKey.isBlank()) queue.find(snapshot.optJSONObject("next")?.optString("id").orEmpty()) ?: queue.next
        else if (!leader) queue.find(queue.nextKey) ?: queue.next else queue.next
    private var stateJob: Job? = null
    private var clockJob: Job? = null
    private var clock = SyncClock()
    private val files = mutableMapOf<String, File>()
    private val sourceHashes = mutableMapOf<String, String>()
    private val ready = mutableMapOf<String, MutableSet<String>>()
    private val connections = mutableMapOf<String, String>()
    private val transmitting = mutableSetOf<String>()
    private val receivedHashes = mutableSetOf<String>()
    private val libraryHashes = mutableSetOf<String>()
    private val receiving = mutableSetOf<String>()
    private val preparing = mutableSetOf<String>()
    private var networkReady = false
    private var busy = false
    private var error: String? = null
    private var sequence = 0L
    private var lastSequence = -1L
    private var lastSync: JSONObject? = null
    private var applyJob: Job? = null
    private var switchJob: Job? = null
    private var frozen = false
    private var generation = 0
    private var lastUi = ""
    private var name = ""
    private var lastTouch = 0L
    private var tickRunning = false
    private var invite = ""
    private var pendingControl = false
    private val controlsLock = Mutex()
    private val tickLock = Mutex()
    private val queueInstallLock = Mutex()
    private var lastCacheKeep = ""
    private var lastNominatedNext = ""
    private var switchingPosition = 0L
    private val directory get() = File(cacheDir, "outgoing").apply { mkdirs() }
    private val self get() = realtime?.userId.orEmpty()
    private val leader get() = room?.optString("leader") == self && self.isNotBlank()
    private val leaderId get() = room?.optString("leader").orEmpty()
    private val roomId get() = room?.optString("id").orEmpty()
    private val memberIds get() = room?.optJSONArray("members")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") }.toSet() }.orEmpty()
    private val binder = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val allowed = packageManager.getPackagesForUid(message.sendingUid).orEmpty().contains("io.github.lynlioss.klang")
            if (!allowed) return
            val token = message.data.getString("token").orEmpty()
            if (message.what == 1) {
                callerUid = message.sendingUid; callerToken = token; host.attach(message)
            }
            if (message.sendingUid != callerUid || token != callerToken) return
            val text = message.data.getString("json").orEmpty()
            if (text.length > 64 * 1024) return
            runCatching {
                val json = JSONObject(text.ifBlank { "{}" })
                when (message.what) {
                    1 -> { settings = json.optJSONObject("settings") ?: JSONObject(); lastUi = ""; render() }
                    2 -> safe { action(json) }
                    3 -> {
                        snapshot = json
                        if (room != null && !tickRunning) {
                            tickRunning = true
                            safe { try { tick() } finally { tickRunning = false } }
                        }
                    }
                    4 -> safe { leave() }
                    12 -> host.result(message)
                }
            }.onFailure { error = it.message; render() }
        }
    })
    override fun onCreate() { super.onCreate(); Names.load(this); directory.deleteRecursively(); File(cacheDir, "incoming").deleteRecursively(); name = Names.random() }
    override fun onBind(intent: Intent?) = binder.binder
    private fun safe(block: suspend () -> Unit) = scope.launch { jamAction(::reportFailure, block) }
    private fun reportFailure(failure: Throwable) {
        if (failure is CancellationException) return
        if (failure !is Exception && failure !is LinkageError) throw failure
        Log.e("KlangJam", "Jam operation failed", failure)
        error = if (failure is LinkageError) "Audio sharing couldn't start on this device. Update Jam and try again."
            else failure.message ?: "Jam could not complete this action"
        render()
    }
    private suspend fun action(json: JSONObject) {
        when (json.optString("action")) {
            "open" -> render()
            "link" -> {
                val uri = android.net.Uri.parse(json.getString("url"))
                val candidate = uri.lastPathSegment.orEmpty().lowercase()
                if (uri.scheme == "klang" && uri.host != "jam") return
                if (!candidate.matches(Regex("[a-z0-9]{4}"))) { error = "This invite needs a four-character code"; render(); return }
                invite = candidate; render() // Joining is an explicit action, even for deep links.
            }
            "create" -> enter("")
            "join" -> {
                val code = json.optString("code", invite).lowercase()
                require(code.matches(Regex("[a-z0-9]{4}"))) { "Enter four letters or numbers" }
                enter(code)
            }
            "leave" -> leave()
            "controls" -> {
                if (!leader) return
                updateRoom(requireNotNull(realtime).rpc("controls", JSONObject().put("room", roomId).put("enabled", json.getBoolean("checked"))))
            }
            "control" -> {
                if (room == null) return
                localControl(json)
            }
            "save" -> {
                val track = current ?: return
                val hash = track.getString("sha256")
                require(hash in receivedHashes)
                host.request("audio.save", JSONObject().put("sha256", hash).put("title", track.optString("title")))
                libraryHashes += hash; error = null; render()
            }
        }
    }
    private suspend fun enter(code: String) {
        if (busy || room != null) return
        require(code.isBlank() || code.matches(Regex("[a-z0-9]{4}"))) { "Enter four letters or numbers" }
        busy = true; error = null; render()
        try {
            val network = Realtime(this, scope, ::event) { connected ->
                networkReady = connected
                if (connected && room != null) safe {
                    room?.let(::updateRoom)
                    if (leader) { sendQueue(); publishSync(force = true); preload() } else {
                        ping(); realtime?.signal(leaderId, "request_sync", JSONObject())
                    }
                }
                render()
            }
            realtime = network
            network.authenticate(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
            val savedRoom = getSharedPreferences("jam-session", 0).getString("room", "").orEmpty()
            if (savedRoom.isNotBlank()) runCatching { network.rpc("leave", JSONObject().put("room", savedRoom)) }
            name = Names.random()
            val result = network.rpc("enter", JSONObject().put("invite", code).put("member_name", name))
            room = result
            getSharedPreferences("jam-session", 0).edit().putString("room", result.getString("id")).apply()
            lastTouch = SystemClock.elapsedRealtime()
            generation++
            peers = Peers(this, scope, settings, { target, event, payload -> network.signal(target, event, payload) },
                { sender, meta -> queue.tracks.any { track ->
                    track.optString("sha256").isNotBlank() && track.optString("sha256") == meta.optString("sha256") &&
                    track.optLong("bytes") == meta.optLong("bytes") && (sender == leaderId || leader && track.optString("owner") == sender)
                } }, ::audioReceived, { id, status -> connections[id] = status; render() }, { id -> safe {
                    receiving.clear(); announcedUploads.clear(); notices.clear()
                    if (leader) { ready.remove(id); sendQueue(id); sendSync(id); preload() }
                    else { ping(); transport(leaderId, "request_sync", JSONObject()) }
                } }, { sender, kind, payload -> event(kind, JSONObject().put("sender", sender).put("target", self).put("payload", payload)) })
            updateRoom(result)
            network.connect(roomId)
            stateJob = scope.launch {
                while (isActive && room != null) {
                    delay(10_000)
                    try { updateRoom(network.rpc("touch", JSONObject().put("room", roomId))); lastTouch = SystemClock.elapsedRealtime() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (e: Exception) {
                        if (e.message?.contains("ended", true) == true) { reset(); error = "This Jam has ended"; render(); break }
                        if (SystemClock.elapsedRealtime() - lastTouch > 45_000) { reset(); error = "Disconnected from Jam"; render(); break }
                        networkReady = false; error = "Reconnecting to your Jam"; render()
                    }
                }
            }
            clockJob = safe {
                while (room != null) {
                    if (!leader && networkReady) {
                        if (!clock.calibrated) repeat(5) { ping(); delay(200) } else ping()
                    }
                    delay(60_000)
                }
            }
            if (leader) {
                desiredPlaying = snapshot.optBoolean("playing")
                val initial = snapshot.optJSONArray("queue") ?: JSONArray().apply { snapshot.optJSONObject("track")?.let(::put) }
                val entries = (0 until minOf(initial.length(), 32)).map { index ->
                    val track = initial.getJSONObject(index)
                    val key = java.util.UUID.randomUUID().toString()
                    localReferences[key] = track.getString("id")
                    cleanTrack(track).put("key", key).put("owner", self)
                }
                queue.mutate(JSONObject().put("mode", "replace").put("index", snapshot.optInt("queueIndex").coerceIn(0, (entries.size - 1).coerceAtLeast(0))), entries)
                safe { preload(); installQueue(); tick() }
            }
        } catch (cancelled: CancellationException) {
            reset(); throw cancelled
        } catch (e: Exception) {
            if (room != null) runCatching { realtime?.rpc("leave", JSONObject().put("room", roomId)) }
            reset(); throw e
        } catch (e: LinkageError) {
            if (room != null) runCatching { realtime?.rpc("leave", JSONObject().put("room", roomId)) }
            reset(); throw e
        } finally { busy = false; render() }
    }
    private fun updateRoom(value: JSONObject) {
        if (value.optString("id").isBlank()) return
        room = value
        val others = memberIds - self
        peers?.removeExcept(if (leader) others else setOf(leaderId))
        connections.keys.retainAll(others)
        ready.keys.retainAll(others)
        if (networkReady) {
            if (leader) others.forEach { peers?.ensure(it, true) }
            else peers?.ensure(leaderId, false)
        }
        render()
    }
    private suspend fun transport(target: String, event: String, payload: JSONObject) {
        if (target == self || target.isBlank()) return
        if (peers?.control(target, event, payload) != true) realtime?.signal(target, event, payload)
    }
    private suspend fun sendQueue(target: String? = null) {
        if (!leader) return
        val payload = queue.payload().put("nextKey", next?.optString("key").orEmpty())
        val message = JSONObject().put("protocol", 2).put("queue", payload)
        (target?.let(::listOf) ?: (memberIds - self).toList()).forEach { transport(it, "track", message) }
    }
    private fun cleanTrack(track: JSONObject): JSONObject = JSONObject()
        .put("title", track.optString("title").take(100)).put("artist", track.optString("artist").take(100))
        .put("album", track.optString("album").take(100)).put("durationMs", track.optLong("durationMs").coerceIn(0, 24 * 60 * 60_000L))
    private fun validateTracks(entries: JSONArray, sender: String): List<JSONObject> {
        require(entries.length() <= 32)
        return (0 until entries.length()).map { index ->
            val track = entries.getJSONObject(index)
            val key = track.getString("key").also { require(it.matches(Regex("[a-zA-Z0-9-]{1,100}"))) }
            require(queue.find(key) == null)
            cleanTrack(track).put("key", key).put("owner", sender)
        }.also { require(it.map { track -> track.getString("key") }.distinct().size == it.size) }
    }
    private fun event(event: String, envelope: JSONObject) {
        if (room == null) return
        safe {
            if (event == "disband") { reset(); error = "This Jam has ended"; render(); return@safe }
            if (event == "membership") { updateRoom(requireNotNull(realtime).rpc("touch", JSONObject().put("room", roomId))); if (leader) { sendQueue(); tick() }; preload(); return@safe }
            if (event == "error") { error = envelope.optString("message"); render(); return@safe }
            val sender = envelope.optString("sender")
            if (sender !in memberIds || sender == self) return@safe
            val target = envelope.optString("target")
            if (target.isNotBlank() && target != "null" && target != self) return@safe
            val payload = envelope.optJSONObject("payload") ?: return@safe
            when (event) {
                "offer", "answer", "ice" -> if (leader || sender == leaderId) peers?.signal(sender, event, payload)
                "clock_ping" -> if (leader) {
                    val now = SystemClock.elapsedRealtime()
                    transport(sender, "clock_pong", JSONObject().put("t0", payload.getLong("t0")).put("t1", now).put("t2", SystemClock.elapsedRealtime()))
                }
                "clock_pong" -> if (!leader && sender == leaderId) {
                    clock.sample(payload.getLong("t0"), payload.getLong("t1"), payload.getLong("t2"), SystemClock.elapsedRealtime()); applySync()
                }
                "request_sync" -> if (leader) { peers?.retryOffer(sender); sendQueue(sender); sendSync(sender); preload() }
                "track" -> if (!leader && sender == leaderId) {
                    require(payload.optInt("protocol") == 2) { "Everyone needs the latest Jam plugin" }
                    val value = payload.getJSONObject("queue")
                    val tracks = value.getJSONArray("tracks"); require(tracks.length() <= 32)
                    for (i in 0 until tracks.length()) {
                        val track = tracks.getJSONObject(i)
                        require(track.getString("key").matches(Regex("[a-zA-Z0-9-]{1,100}")))
                        val hash = track.optString("sha256")
                        require(hash.isBlank() || hash.matches(Regex("[a-f0-9]{64}")))
                        if (hash.isNotBlank()) require(track.getLong("bytes") in 1..256 * 1024 * 1024L)
                    }
                    queue.receive(value)
                    localReferences.keys.retainAll(queue.tracks.map { it.getString("key") }.toSet())
                    if (queue.tracks.isEmpty()) { applyJob?.cancel(); lastSync = null; playback("playback.command", JSONObject().put("command", "pause")) }
                    preload(); installQueue(); applySync(); render()
                }
                "transfer_request" -> {
                    val hash = payload.optString("sha256")
                    if (sender == leaderId || leader) sendFile(sender, hash)
                }
                "ready" -> if (leader) {
                    val hash = payload.getString("sha256")
                    if (queue.tracks.any { it.optString("sha256") == hash }) {
                        if (payload.optBoolean("available", true)) ready.getOrPut(sender) { mutableSetOf() } += hash
                        else ready[sender]?.remove(hash)
                        connections[sender] = "Listening together"
                        if (!tickRunning) { tickRunning = true; try { tick() } finally { tickRunning = false } }
                    }
                }
                "sync" -> if (!leader && sender == leaderId) {
                    require(payload.optInt("protocol") == 2) { "Everyone needs the latest Jam plugin" }
                    if (payload.getLong("seq") >= lastSequence) {
                        lastSequence = payload.getLong("seq"); lastSync = payload
                        queue.find(payload.optString("key"))?.let { queue.currentKey = it.getString("key") }
                        queue.nextKey = payload.optString("nextKey")
                        preload(); installQueue(); applySync()
                    }
                }
                "control" -> if (leader) {
                    if (payload.optString("operation") == "upload_meta") uploadMetadata(sender, payload)
                    else if (room?.optBoolean("controls") == true) command(payload, sender)
                }
            }
        }
    }
    private suspend fun ping() {
        if (!leader && room != null && networkReady) {
            lastClockPing = SystemClock.elapsedRealtime()
            transport(leaderId, "clock_ping", JSONObject().put("t0", lastClockPing))
        }
    }
    private suspend fun ready(hash: String) { if (!leader) transport(leaderId, "ready", JSONObject().put("sha256", hash)) }
    private fun available(hash: String) = hash.isNotBlank() && (hash in receivedHashes || hash in libraryHashes || hash in files)
    private fun allReady(track: JSONObject): Boolean {
        val hash = track.optString("sha256")
        return available(hash) && (memberIds - self).all { hash in ready[it].orEmpty() }
    }
    private suspend fun playback(operation: String, value: JSONObject = JSONObject()): HostResult {
        val result = host.request(operation, value)
        result.json.optJSONObject("snapshot")?.let { snapshot = it }
        return result
    }
    private suspend fun command(json: JSONObject, sender: String = self) = controlsLock.withLock {
        pendingControl = true
        try { executeCommand(json, sender) } finally { pendingControl = false }
        tick()
    }
    private suspend fun executeCommand(json: JSONObject, sender: String = self) {
        when (json.optString("operation")) {
            "queue_error" -> error(json.optString("message"))
            "queue" -> {
                val mode = json.optString("mode")
                val selected = json.optJSONArray("tracks") ?: JSONArray()
                val additions = if (sender == self) (0 until selected.length()).map { index ->
                    val track = selected.getJSONObject(index)
                    val key = java.util.UUID.randomUUID().toString()
                    localReferences[key] = track.getString("id")
                    cleanTrack(track).put("key", key).put("owner", self)
                } else validateTracks(selected, sender)
                val old = queue.currentKey
                if (!frozen) desiredPlaying = snapshot.optBoolean("playing")
                queue.mutate(json, additions)
                localReferences.keys.retainAll(queue.tracks.map { it.getString("key") }.toSet())
                if (mode in setOf("replace", "jump")) desiredPlaying = true
                if (queue.currentKey != old || mode == "jump") {
                    switchJob?.cancel(); applyJob?.cancel()
                    switchingKey = queue.currentKey; switchingPosition = json.optLong("positionMs").coerceAtLeast(0)
                }
                if (queue.tracks.isEmpty()) {
                    switchJob?.cancel(); frozen = false; lastPlayback = null; desiredPlaying = false
                    playback("playback.command", JSONObject().put("command", "pause"))
                    sendQueue(); installQueue(); render(); return
                }
                sendQueue(); preload(); installQueue(); if (switchingKey.isNotBlank()) tick()
            }
            "play", "pause" -> {
                desiredPlaying = json.optString("operation") == "play"
                if (frozen) return
                playback("playback.command", JSONObject().put("command", if (desiredPlaying) "play" else "pause"))
                publishSync(force = true)
            }
            "next", "previous" -> {
                val destination = if (json.optString("operation") == "next") queue.index + 1 else queue.index - 1
                if (destination in queue.tracks.indices) executeCommand(JSONObject().put("operation", "queue").put("mode", "jump").put("index", destination), sender)
            }
            "seek" -> { playback("playback.command", JSONObject().put("command", "seek").put("positionMs", json.getLong("positionMs"))); publishSync(force = true) }
            "shuffle", "repeat" -> {
                val value = JSONObject(json.toString()).put("command", json.getString("operation"))
                playback("playback.command", value); publishSync(force = true)
            }
        }
    }
    private suspend fun localControl(json: JSONObject) {
        if (leader) { command(json); return }
        require(room?.optBoolean("controls") == true) { "The leader has group controls turned off" }
        val proposal = JSONObject(json.toString())
        if (json.optString("operation") == "queue") {
            val selected = json.optJSONArray("tracks") ?: JSONArray()
            val tracks = JSONArray()
            for (i in 0 until selected.length()) {
                val track = selected.getJSONObject(i)
                val key = java.util.UUID.randomUUID().toString()
                localReferences[key] = track.getString("id")
                tracks.put(cleanTrack(track).put("key", key).put("owner", self))
            }
            proposal.put("tracks", tracks)
        }
        transport(leaderId, "control", proposal)
    }
    private suspend fun prepare(track: JSONObject) {
        val key = track.getString("key")
        val reference = localReferences[key] ?: return
        if (track.optString("owner") != self || !preparing.add(key)) return
        val session = generation
        try {
            val hash = sourceHashes[reference]
            val pair = hash?.let { digest -> files[digest]?.let { it to digest } } ?: readAudio(reference)
            if (session != generation || queue.find(key) == null) { if (hash == null) pair.first.delete(); return }
            val digest = pair.second
            files[digest] = pair.first; sourceHashes[reference] = digest
            host.request("audio.register", JSONObject().put("sha256", digest).put("localRef", reference))
            libraryHashes += digest
            val activeTrack = queue.find(key) ?: return
            activeTrack.put("sha256", digest).put("bytes", pair.first.length())
            if (leader) { queue.changed(); sendQueue() }
            else if (announcedUploads.add(key)) transport(leaderId, "control", JSONObject().put("operation", "upload_meta")
                .put("key", key).put("sha256", digest).put("bytes", pair.first.length()))
            installQueue()
            if (!leader) { ready(digest); notices += "ready:$digest" }
        } finally { preparing.remove(key) }
    }
    private suspend fun uploadMetadata(sender: String, payload: JSONObject) {
        val track = queue.find(payload.optString("key")) ?: return
        require(track.optString("owner") == sender) { "This song belongs to another member" }
        val hash = payload.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        require(payload.getLong("bytes") in 1..256 * 1024 * 1024L)
        track.put("sha256", hash).put("bytes", payload.getLong("bytes")); queue.changed(); sendQueue()
        if (!available(hash)) { receiving += hash; transport(sender, "transfer_request", JSONObject().put("sha256", hash)) }
        preload()
    }
    private suspend fun preload() {
        if (room == null) return
        val candidates = listOfNotNull(current, next).distinctBy { it.optString("key") }
        candidates.forEach { track ->
            val key = track.getString("key")
            val hash = track.optString("sha256")
            if (track.optString("owner") == self && !available(hash) && key !in preparing) safe { prepare(track); preload(); tick() }
            else if (hash.isNotBlank()) {
                if (!available(hash) && receiving.add(hash)) safe {
                    val match = host.request("audio.find", JSONObject().put("sha256", hash).put("title", track.optString("title")).put("artist", track.optString("artist"))).json
                    if (match.optBoolean("found")) {
                        libraryHashes += hash; receiving.remove(hash); installQueue(); ready(hash); tick()
                    } else transport(if (leader) track.getString("owner") else leaderId, "transfer_request", JSONObject().put("sha256", hash))
                } else if (available(hash) && !leader && notices.add("ready:$hash")) ready(hash)
                if (leader && available(hash)) (memberIds - self).forEach { id ->
                    if (hash !in ready[id].orEmpty() && track.optString("owner") != id) sendFile(id, hash)
                }
            }
        }
    }
    private suspend fun readAudio(source: String): Pair<File, String> {
        val descriptor = requireNotNull(host.request("audio.read", JSONObject().put("trackId", source)).audio)
        return withContext(Dispatchers.IO) {
            val file = File(directory, "${java.util.UUID.randomUUID()}.part")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> file.outputStream().use { output ->
                    var total = 0L; val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive(); val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= 256 * 1024 * 1024L) { "This audio file exceeds the Jam transfer limit" }
                        require(directory.listFiles().orEmpty().sumOf { it.length() } <= 512 * 1024 * 1024L)
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                } }
                file to digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) { file.delete(); throw e }
        }
    }
    private suspend fun sendFile(id: String, hash: String) {
        if (!available(hash) || peers?.isOpen(id) != true) return
        val track = queue.tracks.firstOrNull { it.optString("sha256") == hash } ?: return
        val transfer = "$id:$hash"
        if (!transmitting.add(transfer)) return
        safe {
            try {
                val file = files[hash] ?: run {
                    val (copy, digest) = readAudio(localReferences[track.getString("key")] ?: track.getString("key"))
                    if (digest != hash) { copy.delete(); error("Audio changed since it was queued") }
                    files[hash] = copy; copy
                }
                peers?.send(id, track, file)
            } finally { transmitting.remove(transfer) }
        }
    }
    private suspend fun audioReceived(sender: String, track: JSONObject, file: File) {
        val authoritative = queue.tracks.firstOrNull { it.optString("sha256") == track.optString("sha256") } ?: return
        require(sender == leaderId || leader && authoritative.optString("owner") == sender)
        val session = generation
        val hash = authoritative.getString("sha256")
        if (!available(hash)) {
            val descriptor = requireNotNull(host.request("audio.receive", JSONObject().put("sha256", hash).put("bytes", file.length())).audio)
            withContext(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output -> file.inputStream().use { it.copyTo(output) } } }
            host.request("audio.commit", JSONObject().put("sha256", hash))
            if (session != generation || room == null) return
            receivedHashes += hash
            if (leader) withContext(Dispatchers.IO) { file.copyTo(File(directory, "$hash.audio"), true) }.let { files[hash] = it }
        }
        receiving.remove(hash); installQueue(); ready(hash); preload(); tick(); render()
    }
    private suspend fun installQueue() = queueInstallLock.withLock {
        val entries = JSONArray()
        queue.tracks.forEach { track ->
            val entry = JSONObject(track.toString())
            if (track.optString("owner") == self) localReferences[track.getString("key")]?.let { entry.put("localRef", it) }
            entry.put("available", available(track.optString("sha256")))
            entries.put(entry)
        }
        val nomination = if (leader) "" else next?.optString("key").orEmpty()
        val signature = entries.toString() + queue.currentKey + nomination
        if (signature == installedQueue) return@withLock
        playback("playback.queue", JSONObject().put("tracks", entries).put("key", queue.currentKey).put("nextKey", nomination))
        installedQueue = signature
    }
    private fun snapshotPosition(now: Long = SystemClock.elapsedRealtime()): Long {
        val base = snapshot.optLong("positionMs")
        val extra = if (snapshot.optBoolean("advancing")) ((now - snapshot.optLong("elapsedMs", now)).coerceAtLeast(0) * snapshot.optDouble("speed", 1.0)).toLong() else 0
        return (base + extra).coerceAtMost(snapshot.optLong("durationMs").takeIf { it > 0 } ?: Long.MAX_VALUE)
    }
    private suspend fun tick() {
        if (room == null || !tickLock.tryLock()) return
        try { tickSession() } finally { tickLock.unlock() }
    }
    private suspend fun tickSession() {
        if (!leader) {
            if (!clock.calibrated && networkReady && SystemClock.elapsedRealtime() - lastClockPing > 3000) ping()
            preload(); applySync(); trimCache(); render(); return
        }
        if (pendingControl) return
        val observed = snapshot.optJSONObject("track")?.optString("id").orEmpty()
        val nominated = next?.optString("key").orEmpty()
        if (nominated != lastNominatedNext) { lastNominatedNext = nominated; sendQueue() }
        if (switchingKey.isBlank() && queue.find(observed) != null && observed != queue.currentKey) {
            queue.currentKey = observed; queue.changed(); sendQueue(); lastPlayback = null
        }
        val track = current ?: return
        val changing = switchingKey.isNotBlank()
        if (changing && allReady(track) && !frozen) {
            installQueue()
            playback("playback.apply", JSONObject().put("key", track.getString("key")).put("positionMs", switchingPosition).put("playing", desiredPlaying).put("speed", 1))
            switchingKey = ""; lastPlayback = null
            publishSync(force = true, playing = desiredPlaying)
        } else if (changing || !allReady(track)) {
            if (!frozen) {
                desiredPlaying = if (changing) desiredPlaying else snapshot.optBoolean("playing")
                frozen = true
                playback("playback.command", JSONObject().put("command", "pause"))
                publishSync(force = true, playing = false)
            }
            if (allReady(track) && switchJob?.isActive != true) {
                val session = generation
                val key = track.getString("key")
                installQueue()
                if (changing) playback("playback.apply", JSONObject().put("key", key).put("playing", false).put("positionMs", switchingPosition))
                val position = if (changing) switchingPosition else snapshotPosition()
                val anchor = SystemClock.elapsedRealtime() + 600
                publishSync(force = true, playing = desiredPlaying, position = position, anchor = anchor)
                switchJob = safe {
                    delay(600)
                    if (session == generation && queue.currentKey == key) {
                        playback("playback.apply", JSONObject().put("key", key).put("playing", desiredPlaying))
                        frozen = false; switchingKey = ""; lastPlayback = null
                    }
                }
            }
        } else if (frozen && switchJob?.isActive != true) {
            val session = generation
            val anchor = SystemClock.elapsedRealtime() + 600
            publishSync(force = true, playing = desiredPlaying, position = snapshotPosition(), anchor = anchor)
            switchJob = safe { delay(600); if (session == generation) {
                playback("playback.command", JSONObject().put("command", if (desiredPlaying) "play" else "pause"))
                frozen = false; lastPlayback = null
            } }
        } else if (!frozen && switchJob?.isActive != true) publishSync()
        if (networkReady && SystemClock.elapsedRealtime() - lastCheckpoint > 15_000 && switchJob?.isActive != true && !frozen) {
            lastCheckpoint = SystemClock.elapsedRealtime(); publishSync(force = true)
        }
        preload(); trimCache(); render()
    }
    private suspend fun trimCache() {
        val keep = listOfNotNull(current, next).map { it.optString("sha256") }.filter { it.isNotBlank() }.toMutableSet()
        transmitting.forEach { keep += it.substringAfter(':') }
        receiving.forEach { keep += it }
        val signature = keep.sorted().joinToString()
        if (signature == lastCacheKeep || preparing.isNotEmpty()) return
        lastCacheKeep = signature
        val removed = (files.keys + receivedHashes + libraryHashes).filter { it !in keep }
        removed.forEach { hash ->
            files.remove(hash)?.delete(); receivedHashes.remove(hash); libraryHashes.remove(hash)
            notices.remove("ready:$hash")
            if (leader) ready.values.forEach { it.remove(hash) }
            else transport(leaderId, "ready", JSONObject().put("sha256", hash).put("available", false))
        }
        host.request("audio.release", JSONObject().put("keep", JSONArray(keep.toList())))
    }
    private suspend fun publishSync(force: Boolean = false, playing: Boolean = snapshot.optBoolean("playing"),
        position: Long = snapshotPosition(), anchor: Long = SystemClock.elapsedRealtime()) {
        if (!leader || current == null || !networkReady) return
        val previous = lastPlayback
        if (!force && previous != null && previous.optString("key") == queue.currentKey && previous.optBoolean("playing") == playing &&
            previous.optInt("shuffle") == (if (snapshot.optBoolean("shuffle")) 1 else 0) && previous.optInt("repeat") == snapshot.optInt("repeat") &&
            kotlin.math.abs(SyncClock.target(previous.optLong("positionMs"), previous.optLong("anchorMs"), anchor,
                previous.optBoolean("playing"), previous.optLong("durationMs")) - position) < 1500) return
        val value = JSONObject().put("protocol", 2).put("seq", ++sequence).put("key", queue.currentKey)
            .put("sha256", current!!.optString("sha256")).put("positionMs", position)
            .put("durationMs", snapshot.optLong("durationMs").takeIf { snapshot.optJSONObject("track")?.optString("id") == queue.currentKey && it > 0 } ?: current!!.optLong("durationMs"))
            .put("anchorMs", anchor).put("playing", playing).put("syncing", frozen && !playing)
            .put("shuffle", if (snapshot.optBoolean("shuffle")) 1 else 0).put("repeat", snapshot.optInt("repeat"))
            .put("nextKey", next?.optString("key").orEmpty())
        lastPlayback = value
        (memberIds - self).forEach { transport(it, "sync", value) }
    }
    private suspend fun sendSync(id: String) {
        if (!leader) return
        if (lastPlayback == null) publishSync(force = true) else transport(id, "sync", requireNotNull(lastPlayback))
    }
    private suspend fun applySync() {
        if (leader || !networkReady || !clock.calibrated || applyJob?.isActive == true) return
        val sync = lastSync ?: return
        val track = queue.find(sync.optString("key")) ?: return
        if (!available(track.optString("sha256"))) return
        if (sync.optBoolean("playing") && sync.getLong("anchorMs") - clock.leaderTime(SystemClock.elapsedRealtime()) > 5000) return
        val session = generation
        applyJob = safe {
            installQueue()
            val until = sync.getLong("anchorMs") - clock.leaderTime(SystemClock.elapsedRealtime())
            if (sync.optBoolean("playing") && until > 0) delay(until.coerceAtMost(5000))
            if (session != generation || leader || lastSync?.optLong("seq") != sync.optLong("seq")) return@safe
            val now = SystemClock.elapsedRealtime()
            val repeat = sync.optInt("repeat").coerceIn(0, 2)
            if (snapshot.optInt("repeat") != repeat) playback("playback.command", JSONObject().put("command", "repeat").put("mode", repeat))
            val shuffle = sync.optInt("shuffle") == 1
            if (snapshot.optBoolean("shuffle") != shuffle) playback("playback.command", JSONObject().put("command", "shuffle").put("enabled", shuffle))
            val action = playbackAnchor.action(sync, snapshot.optJSONObject("track")?.optString("id").orEmpty(), snapshotPosition(now),
                snapshot.optBoolean("advancing"), snapshot.optBoolean("playing"), snapshot.optBoolean("ready"), clock.leaderTime(now), now)
            if (action != null) playback("playback.apply", action)
        }
    }
    private suspend fun leave() {
        if (busy) return
        busy = true; render()
        try { if (room != null) realtime?.rpc("leave", JSONObject().put("room", roomId)) }
        finally { reset(); busy = false; error = null; render() }
    }
    private fun reset() {
        generation++; clock = SyncClock()
        stateJob?.cancel(); clockJob?.cancel(); applyJob?.cancel(); switchJob?.cancel()
        realtime?.close(); peers?.close(); peers = null
        getSharedPreferences("jam-session", 0).edit().remove("room").apply()
        room = null; queue.clear(); playbackAnchor.reset(); localReferences.clear(); announcedUploads.clear(); notices.clear(); installedQueue = ""; lastPlayback = null; switchingKey = ""; lastSync = null; lastSequence = -1; sequence = 0
        networkReady = false; frozen = false; desiredPlaying = false; switchingPosition = 0; lastCheckpoint = 0; lastClockPing = 0; lastCacheKeep = ""; lastNominatedNext = ""
        files.clear(); sourceHashes.clear(); ready.clear(); receivedHashes.clear(); libraryHashes.clear(); receiving.clear(); connections.clear(); transmitting.clear(); preparing.clear()
        safe { host.request("session.clear") }
        directory.deleteRecursively()
    }
    private fun render() {
        val items = JSONArray()
        fun item(type: String, label: String) = JSONObject().put("type", type).put("label", label)
        fun button(label: String, action: String, icon: String, style: String = "") = item("button", label).put("action", action).put("icon", icon).put("style", style).put("enabled", !busy)
        val r = room
        val syncing = r != null && (!networkReady || if (leader) frozen || current != null && current?.optString("sha256").orEmpty().isBlank() else
            current != null && (!clock.calibrated || !snapshot.optBoolean("ready") || current?.optString("sha256") !in receivedHashes + libraryHashes + files.keys || lastSync == null || lastSync?.optBoolean("syncing") == true))
        if (r == null) {
            items.put(item("text", "Bring up to four people into the same song. Start a Jam, or use a friend's invite."))
            items.put(button("Create a Jam", "create", "add"))
            items.put(item("field", "Invite code").put("id", "code").put("input", "code").put("maxLength", 4).put("value", invite))
            items.put(button("Join a Jam", "join", "join", "tonal"))
            if (busy) items.put(item("progress", "Getting your Jam ready"))
        } else {
            items.put(item("hero", r.getString("code")).put("eyebrow", "Invite code").put("supporting", "${memberIds.size} of 4 members"))
            if (syncing) items.put(item("progress", if (!networkReady) "Reconnecting to Jam" else "Syncing everyone's music"))
            val members = r.optJSONArray("members") ?: JSONArray()
            for (index in 0 until members.length()) {
                val member = members.getJSONObject(index)
                val id = member.getString("id")
                items.put(item("member", member.getString("name"))
                    .put("badge", if (id == self) "You" else if (id == leaderId) "Leader" else "")
                    .put("supporting", if (id == self) if (leader) "Leading this Jam" else "Listening together" else connections[id] ?: "Connecting"))
            }
            if (leader) items.put(item("toggle", "Group controls").put("action", "controls").put("checked", r.optBoolean("controls"))
                .put("supporting", "Let everyone choose songs, edit the queue and control playback.").put("enabled", !busy))
            else items.put(item("text", if (r.optBoolean("controls")) "Everyone can control the music." else "The leader controls the music."))
            if (memberIds.size == 1) items.put(item("text", "Jam ends after five minutes with no other members."))
            items.put(button(if (leader) "End Jam" else "Leave Jam", "leave", "leave")
                .put("placement", "footer").put("presentation", "icon"))
            items.put(button("Share invite", "share", "share").put("placement", "footer")
                .put("shareUrl", "https://jamklang.pages.dev/${r.getString("code")}"))
        }
        error?.let { items.put(item("error", it.take(500))) }
        val state = JSONObject().put("panel", JSONObject().put("title", "Jam").put("items", items))
            .put("interceptControls", r != null)
            .put("badge", JSONObject().put("visible", r != null).put("label", if (syncing) "Sync" else "Jam").put("loading", syncing))
        if (r != null && current?.optString("sha256") in receivedHashes && current?.optString("sha256") !in libraryHashes) {
            state.put("trackAction", JSONObject().put("trackId", current!!.getString("key")).put("action", "save").put("label", "Download this song"))
        }
        val source = state.toString()
        if (source != lastUi) { lastUi = source; runCatching { host.state(state) } }
    }
    override fun onDestroy() { reset(); host.close(); scope.cancel(); super.onDestroy() }
}
