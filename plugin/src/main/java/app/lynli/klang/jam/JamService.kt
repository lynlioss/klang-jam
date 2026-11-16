package app.lynli.klang.jam

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Entire Jam session, synchronization and UI model live in this separately installed plugin. */
class JamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val host = Host()
    private var callerUid = -1
    private var callerToken = ""
    private var settings = JSONObject()
    private var realtime: Realtime? = null
    private var peers: Peers? = null
    private var room: JSONObject? = null
    private var snapshot = JSONObject()
    private var current: JSONObject? = null
    private var next: JSONObject? = null
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
    private var resumeAfterSync = false
    private var lastPublished = 0L
    private var generation = 0
    private var lastUi = ""
    private var name = ""
    private var lastTouch = 0L
    private var lastObservedSource = ""
    private var localPreparedHash = ""
    private var tickRunning = false
    private var invite = ""
    private var pendingControl = false
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
                    1 -> { settings = json.optJSONObject("settings") ?: JSONObject(); render() }
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
    private fun safe(block: suspend () -> Unit) = scope.launch {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = e.message ?: "Jam could not complete this action"; render() }
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
                if (leader) command(json)
                else if (room?.optBoolean("controls") == true) requireNotNull(realtime).signal(leaderId, "control", json)
                else { error = "The leader has group controls turned off"; render() }
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
                    if (leader) broadcastState() else {
                        ping(); realtime?.signal(leaderId, "request_sync", JSONObject())
                    }
                }
                render()
            }
            realtime = network
            network.authenticate(settings.optString("supabaseUrl").ifBlank { BuildConfig.SUPABASE_URL },
                settings.optString("supabaseKey").ifBlank { BuildConfig.SUPABASE_KEY })
            val savedRoom = getSharedPreferences("jam-session", 0).getString("room", "").orEmpty()
            if (savedRoom.isNotBlank()) runCatching { network.rpc("leave", JSONObject().put("room", savedRoom)) }
            name = Names.random()
            val result = network.rpc("enter", JSONObject().put("invite", code).put("member_name", name))
            room = result
            getSharedPreferences("jam-session", 0).edit().putString("room", result.getString("id")).apply()
            lastTouch = SystemClock.elapsedRealtime()
            generation++
            peers = Peers(this, scope, settings, { target, event, payload -> network.signal(target, event, payload) },
                { sender, meta -> sender == leaderId && listOfNotNull(current, next).any { it.optString("sha256") == meta.optString("sha256") && it.optLong("bytes") == meta.optLong("bytes") } },
                ::audioReceived, { id, status -> connections[id] = status; render() }, { id -> safe {
                    if (leader) {
                        ready.remove(id)
                        sendTracks(id)
                    } else { ping(); realtime?.signal(leaderId, "request_sync", JSONObject()) }
                } })
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
            clockJob = scope.launch { while (isActive && room != null) { if (!leader) { clock.resetWindow(); repeat(5) { ping(); delay(200) } }; delay(10_000) } }
            if (leader) prepareCurrent()
        } catch (e: Exception) {
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
    private fun event(event: String, envelope: JSONObject) {
        if (room == null) return
        safe {
            if (event == "disband") { reset(); error = "This Jam has ended"; render(); return@safe }
            if (event == "membership") { updateRoom(requireNotNull(realtime).rpc("touch", JSONObject().put("room", roomId))); return@safe }
            if (event == "error") { error = envelope.optString("message"); render(); return@safe }
            val sender = envelope.optString("sender")
            val target = envelope.optString("target")
            if (target.isNotBlank() && target != "null" && target != self || sender !in memberIds || sender == self) return@safe
            val payload = envelope.optJSONObject("payload") ?: return@safe
            when (event) {
                "offer", "answer", "ice" -> if (leader || sender == leaderId) peers?.signal(sender, event, payload)
                "clock_ping" -> if (leader) {
                    val t1 = SystemClock.elapsedRealtime()
                    realtime?.signal(sender, "clock_pong", JSONObject().put("t0", payload.getLong("t0")).put("t1", t1).put("t2", SystemClock.elapsedRealtime()))
                }
                "clock_pong" -> if (!leader && sender == leaderId) {
                    clock.sample(payload.getLong("t0"), payload.getLong("t1"), payload.getLong("t2"), SystemClock.elapsedRealtime())
                    applySync()
                }
                "request_sync" -> if (leader) { peers?.retryOffer(sender); sendTracks(sender) }
                "transfer_request" -> if (leader) sendTracks(sender, payload.optString("sha256"))
                "track" -> if (!leader && sender == leaderId) {
                    val hash = payload.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                    require(payload.getLong("bytes") in 1..256 * 1024 * 1024L)
                    val isNext = payload.optBoolean("next")
                    if (isNext) next = payload else current = payload
                    if (hash in receivedHashes || hash in libraryHashes) ready(hash)
                    else if (receiving.add(hash)) {
                        val match = host.request("audio.find", JSONObject().put("sha256", hash).put("title", payload.optString("title")).put("artist", payload.optString("artist"))).json
                        if (match.optBoolean("found")) { libraryHashes += hash; receiving.remove(hash); ready(hash) }
                        else realtime?.signal(leaderId, "transfer_request", JSONObject().put("sha256", hash))
                    } else realtime?.signal(leaderId, "transfer_request", JSONObject().put("sha256", hash))
                    applySync(); render()
                }
                "ready" -> if (leader) {
                    ready.getOrPut(sender) { mutableSetOf() } += payload.getString("sha256")
                    connections[sender] = "Listening together"
                    render()
                    if (!tickRunning) { tickRunning = true; try { tick() } finally { tickRunning = false } }
                }
                "sync" -> if (!leader && sender == leaderId) {
                    val seq = payload.getLong("seq")
                    if (seq > lastSequence) { lastSequence = seq; lastSync = payload; applySync() }
                }
                "control" -> if (leader && (room?.optBoolean("controls") == true || sender == self)) command(payload)
            }
        }
    }
    private suspend fun ping() { if (!leader && room != null && networkReady) realtime?.signal(leaderId, "clock_ping", JSONObject().put("t0", SystemClock.elapsedRealtime())) }
    private suspend fun ready(hash: String) { realtime?.signal(leaderId, "ready", JSONObject().put("sha256", hash)) }
    private suspend fun command(json: JSONObject) {
        val operation = json.optString("operation")
        if (operation == "queue") { error = "Choose songs on the leader's device while sharing a Jam"; render(); return }
        if (frozen && operation in setOf("play", "pause")) { resumeAfterSync = operation == "play"; render(); return }
        val command = JSONObject().put("command", operation)
        if (json.has("positionMs")) command.put("positionMs", json.getLong("positionMs"))
        if (json.has("enabled")) command.put("enabled", json.getBoolean("enabled"))
        if (json.has("mode")) command.put("mode", json.getInt("mode"))
        pendingControl = true
        try { host.request("playback.command", command); lastPublished = 0 }
        finally { pendingControl = false }
    }
    private suspend fun prepareCurrent() {
        val track = snapshot.optJSONObject("track") ?: return
        val source = track.getString("id")
        val hash = sourceHashes[source]
        if (hash != null) {
            if (lastObservedSource != source || current?.optString("sha256") != hash) {
                current = metadata(track, hash, files.getValue(hash).length(), false)
                lastObservedSource = source
                frozen = false; resumeAfterSync = false; lastPublished = 0
                memberIds.filter { it != self }.forEach { sendTracks(it) }
            }
            return
        }
        if (!preparing.add(source)) return
        val session = generation
        try {
            val (file, digest) = readAudio(source)
            if (session != generation || room == null) { file.delete(); return }
            files.put(digest, file)?.takeIf { it != file }?.delete(); sourceHashes[source] = digest
            current = metadata(track, digest, file.length(), false)
            lastObservedSource = source
            frozen = false; resumeAfterSync = false; lastPublished = 0
            memberIds.filter { it != self }.forEach { sendTracks(it) }
        } finally { preparing.remove(source); render() }
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
    private fun metadata(track: JSONObject, hash: String, bytes: Long, isNext: Boolean) = JSONObject()
        .put("sha256", hash).put("bytes", bytes).put("title", track.optString("title").take(500))
        .put("artist", track.optString("artist").take(500)).put("album", track.optString("album").take(500))
        .put("durationMs", snapshot.optLong("durationMs")).put("next", isNext)
    private suspend fun sendTracks(id: String, requestedHash: String? = null) {
        if (!leader || id !in memberIds) return
        listOfNotNull(current, next).distinctBy { it.optString("sha256") }.forEach { track ->
            if (requestedHash == null) realtime?.signal(id, "track", track)
            val hash = track.getString("sha256")
            if (requestedHash == hash && peers?.isOpen(id) == true && hash !in ready[id].orEmpty() && transmitting.add("$id:$hash")) {
                safe {
                    try { peers?.send(id, track, requireNotNull(files[hash])) }
                    finally { transmitting.remove("$id:$hash"); render() }
                }
            }
        }
        if (networkReady && requestedHash == null) broadcastState(id)
    }
    private suspend fun audioReceived(sender: String, track: JSONObject, file: File) {
        require(!leader && sender == leaderId)
        val session = generation
        val hash = track.getString("sha256")
        if (hash !in receivedHashes && hash !in libraryHashes) {
            val descriptor = requireNotNull(host.request("audio.receive", JSONObject().put("sha256", hash).put("bytes", file.length())).audio)
            withContext(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output -> file.inputStream().use { it.copyTo(output) } } }
            host.request("audio.commit", JSONObject().put("sha256", hash))
            if (session != generation || room == null) return
            receivedHashes += hash
        }
        receiving.remove(hash); ready(hash); applySync(); render()
    }
    private suspend fun trimCache() {
        val keep = listOfNotNull(current?.optString("sha256"), next?.optString("sha256"), lastSync?.optString("sha256"), snapshot.optJSONObject("track")?.optString("id"))
            .filter { it.isNotBlank() }.toSet()
        if (leader) {
            val protected = transmitting.map { it.substringAfter(':') }.toSet() + keep
            files.keys.toList().filter { it !in protected }.forEach { hash ->
                files.remove(hash)?.delete(); sourceHashes.entries.removeAll { it.value == hash }
                ready.values.forEach { it.remove(hash) }
            }
        } else {
            host.request("audio.release", JSONObject().put("keep", JSONArray(keep.toList())))
            receivedHashes.retainAll(keep); libraryHashes.retainAll(keep)
        }
    }
    private suspend fun tick() {
        if (room == null) return
        if (!leader) { applySync(); trimCache(); render(); return }
        if (pendingControl) return
        prepareCurrent()
        val track = current ?: return
        val hash = track.getString("sha256")
        val others = memberIds - self
        val allReady = others.all { hash in ready[it].orEmpty() }
        if (!allReady && !frozen) {
            resumeAfterSync = snapshot.optBoolean("playing")
            frozen = true
            val paused = host.request("playback.command", JSONObject().put("command", "pause")).json
            snapshot.put("positionMs", paused.optLong("positionMs", snapshot.optLong("positionMs"))).put("elapsedMs", paused.optLong("elapsedMs")).put("playing", false)
            lastPublished = 0
        } else if (allReady && frozen && switchJob?.isActive != true) {
            if (resumeAfterSync) {
                val anchor = SystemClock.elapsedRealtime() + 750
                broadcastState(playing = true, anchor = anchor)
                val session = generation
                switchJob = scope.launch {
                    delay(750)
                    if (session == generation && room != null && leader) {
                        host.request("playback.command", JSONObject().put("command", "play")); frozen = false; resumeAfterSync = false; render()
                    }
                }
            } else { frozen = false; broadcastState(playing = false) }
        }
        val now = SystemClock.elapsedRealtime()
        if (networkReady && now - lastPublished >= 1000 && switchJob?.isActive != true) { lastPublished = now; broadcastState() }
        val remaining = snapshot.optLong("durationMs") - snapshot.optLong("positionMs")
        if (remaining in 1..10_000) {
            val following = snapshot.optJSONObject("next")
            val source = following?.optString("id")
            if (!source.isNullOrBlank() && source !in sourceHashes && preparing.add(source)) safe {
                val session = generation
                try {
                    val (file, digest) = readAudio(source)
                    if (session != generation) { file.delete(); return@safe }
                    files.put(digest, file)?.takeIf { it != file }?.delete(); sourceHashes[source] = digest
                    next = metadata(requireNotNull(following), digest, file.length(), true)
                    others.forEach { sendTracks(it) }
                } finally { preparing.remove(source) }
            } else if (source != null && source in sourceHashes && next?.optString("sha256") != sourceHashes[source]) {
                val digest = sourceHashes.getValue(source)
                next = metadata(requireNotNull(following), digest, files.getValue(digest).length(), true); others.forEach { sendTracks(it) }
            }
        }
        trimCache(); render()
    }
    private suspend fun broadcastState(target: String? = null, playing: Boolean = snapshot.optBoolean("playing") && !frozen,
        anchor: Long = SystemClock.elapsedRealtime()) {
        if (!leader || !networkReady || current == null) return
        val now = SystemClock.elapsedRealtime()
        val position = SyncClock.target(snapshot.optLong("positionMs"), snapshot.optLong("elapsedMs", now), now,
            snapshot.optBoolean("playing") && !frozen, snapshot.optLong("durationMs"))
        realtime?.signal(target, "sync", JSONObject().put("seq", ++sequence).put("sha256", current!!.getString("sha256"))
            .put("positionMs", position).put("durationMs", snapshot.optLong("durationMs"))
            .put("anchorMs", anchor).put("playing", playing).put("syncing", frozen && !playing)
            .put("title", current!!.optString("title")).put("artist", current!!.optString("artist")).put("album", current!!.optString("album")))
    }
    private suspend fun applySync() {
        if (leader || !networkReady || !clock.calibrated || applyJob?.isActive == true) return
        val sync = lastSync ?: return
        val hash = sync.getString("sha256")
        if (hash !in receivedHashes && hash !in libraryHashes) return
        if (current?.optString("sha256") != hash) {
            if (next?.optString("sha256") != hash) return
            current = next; next = null
        }
        val session = generation
        applyJob = scope.launch {
            if (snapshot.optJSONObject("track")?.optString("id") != hash && localPreparedHash != hash) {
                try {
                    host.request("playback.apply", JSONObject().put("sha256", hash).put("playing", false).put("positionMs", 0)
                        .put("title", sync.optString("title")).put("artist", sync.optString("artist")).put("album", sync.optString("album")))
                    localPreparedHash = hash
                } catch (e: Exception) { error = e.message; render(); return@launch }
            }
            val leaderNow = clock.leaderTime(SystemClock.elapsedRealtime())
            val until = sync.getLong("anchorMs") - leaderNow
            if (sync.optBoolean("playing") && until > 0) delay(until.coerceAtMost(5000))
            if (session != generation || room == null || leader) return@launch
            val latest = lastSync ?: return@launch
            if (latest.getString("sha256") != hash) return@launch
            val now = clock.leaderTime(SystemClock.elapsedRealtime())
            val target = SyncClock.target(latest.getLong("positionMs"), latest.getLong("anchorMs"), now, latest.optBoolean("playing"), latest.optLong("durationMs"))
            val localNow = SystemClock.elapsedRealtime()
            val local = SyncClock.target(snapshot.optLong("positionMs"), snapshot.optLong("elapsedMs", localNow), localNow,
                snapshot.optBoolean("playing") && snapshot.optBoolean("ready"), snapshot.optLong("durationMs"))
            val trackChanged = snapshot.optJSONObject("track")?.optString("id") != hash
            val delta = target - local
            val payload = JSONObject().put("sha256", hash).put("playing", latest.optBoolean("playing"))
                .put("title", latest.optString("title")).put("artist", latest.optString("artist")).put("album", latest.optString("album"))
            if (trackChanged || abs(delta) > 80 || !latest.optBoolean("playing")) payload.put("positionMs", target).put("speed", 1)
            else payload.put("speed", SyncClock.correction(delta).toDouble())
            try { host.request("playback.apply", payload) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { error = e.message; render() }
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
        room = null; current = null; next = null; lastSync = null; lastSequence = -1; sequence = 0
        networkReady = false; localPreparedHash = ""; lastObservedSource = ""; frozen = false; resumeAfterSync = false
        files.clear(); sourceHashes.clear(); ready.clear(); receivedHashes.clear(); libraryHashes.clear(); receiving.clear(); connections.clear(); transmitting.clear(); preparing.clear()
        safe { host.request("session.clear") }
        directory.deleteRecursively()
    }
    private fun render() {
        val items = JSONArray()
        fun item(type: String, label: String) = JSONObject().put("type", type).put("label", label)
        fun button(label: String, action: String, icon: String, style: String = "") = item("button", label).put("action", action).put("icon", icon).put("style", style).put("enabled", !busy)
        val r = room
        val syncing = r != null && (!networkReady || current == null || if (leader) frozen || preparing.isNotEmpty() else
            !clock.calibrated || !snapshot.optBoolean("ready") || current?.optString("sha256") !in receivedHashes + libraryHashes || lastSync == null || lastSync?.optBoolean("syncing") == true)
        if (r == null) {
            items.put(item("hero", "Together").put("supporting", "YOUR MUSIC · ONE MOMENT"))
            items.put(item("text", "Bring up to four people into the same song. Start a Jam, or use a friend's invite."))
            items.put(button("Create a Jam", "create", "add"))
            items.put(item("field", "Invite code").put("id", "code").put("input", "code").put("maxLength", 4).put("value", invite))
            items.put(button("Join a Jam", "join", "join", "tonal"))
            if (busy) items.put(item("progress", "Getting your Jam ready"))
        } else {
            items.put(item("hero", r.getString("code")).put("supporting", "${memberIds.size} OF 4 · INVITE YOUR PEOPLE"))
            items.put(button("Share invite", "share", "share", "tonal").put("shareUrl", "https://jamklang.pages.dev/${r.getString("code")}"))
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
                .put("supporting", "Let everyone play, pause, skip and seek.").put("enabled", !busy))
            else items.put(item("text", if (r.optBoolean("controls")) "Everyone can control the music." else "The leader controls the music."))
            if (memberIds.size == 1) items.put(item("text", "Your Jam ends after five minutes alone."))
            items.put(button(if (leader) "Disband Jam" else "Leave Jam", "leave", "leave", if (leader) "danger" else "tonal"))
        }
        error?.let { items.put(item("error", it.take(500))) }
        val state = JSONObject().put("panel", JSONObject().put("title", "Jam").put("items", items))
            .put("interceptControls", r != null)
            .put("badge", JSONObject().put("visible", r != null).put("label", if (syncing) "Sync" else "Jam").put("loading", syncing))
        if (r != null && !leader && current?.optString("sha256") in receivedHashes && current?.optString("sha256") !in libraryHashes) {
            state.put("trackAction", JSONObject().put("trackId", current!!.getString("sha256")).put("action", "save").put("label", "Download this song"))
        }
        val source = state.toString()
        if (source != lastUi) { lastUi = source; runCatching { host.state(state) } }
    }
    override fun onDestroy() { reset(); host.close(); scope.cancel(); super.onDestroy() }
}
