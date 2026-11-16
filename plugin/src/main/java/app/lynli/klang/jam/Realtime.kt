package app.lynli.klang.jam

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Supabase Auth, guarded RPCs and receive-only private Realtime broadcasts.
 * Audio never touches Supabase. The backend authors sender/target envelopes. */
internal class Realtime(private val context: Context, private val scope: CoroutineScope,
    private val event: (String, JSONObject) -> Unit, private val status: (Boolean) -> Unit) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private var base = ""
    private var key = ""
    private var jwt = ""
    private var refresh = ""
    private var expires = 0L
    var userId = ""; private set
    private var room = ""
    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var generation = 0
    private var ref = 0
    private var closed = false
    private var reconnects = 0
    private var joinRef = ""
    private val authLock = kotlinx.coroutines.sync.Mutex()
    suspend fun authenticate(url: String, publishableKey: String) {
        val parsed = url.toHttpUrlOrNull() ?: error("Set a valid Supabase project URL in plugin settings")
        require(parsed.isHttps && parsed.encodedPath == "/" && parsed.username.isBlank() && parsed.password.isBlank())
        require(publishableKey.isNotBlank()) { "Set the Supabase publishable key in plugin settings" }
        base = url.trimEnd('/'); key = publishableKey
        val prefs = context.getSharedPreferences("jam-auth", 0)
        if (prefs.getString("project", "") == base) refresh = prefs.getString("refresh", "").orEmpty()
        renew()
    }
    private suspend fun renew() {
        authLock.lock()
        try {
            if (jwt.isNotBlank() && System.currentTimeMillis() < expires - 120_000) return
            val response = if (refresh.isNotBlank()) runCatching {
                post("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh), false)
            }.getOrNull() else null
            val auth = response ?: post("/auth/v1/signup", JSONObject().put("data", JSONObject().put("app", "klang-jam")), false)
            jwt = auth.getString("access_token"); refresh = auth.getString("refresh_token")
            userId = auth.getJSONObject("user").getString("id")
            expires = System.currentTimeMillis() + auth.optLong("expires_in", 3600) * 1000
            context.getSharedPreferences("jam-auth", 0).edit().putString("project", base).putString("refresh", refresh).apply()
            socket?.let { send("access_token", JSONObject().put("access_token", jwt)) }
        } finally { authLock.unlock() }
    }
    private suspend fun post(path: String, payload: JSONObject, authorized: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(base + path).header("apikey", key)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (authorized) request.header("Authorization", "Bearer $jwt")
        http.newCall(request.build()).execute().use { response ->
            val bytes = response.body?.byteStream()?.use { it.readNBytes(256_001) } ?: error("Empty Supabase response")
            require(bytes.size <= 256_000)
            val source = bytes.toString(Charsets.UTF_8)
            val json = runCatching { JSONObject(source) }.getOrElse { error("Invalid Supabase response (${response.code})") }
            require(response.isSuccessful) { json.optString("msg", json.optString("message", "Supabase request failed (${response.code})")) }
            json.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
            json
        }
    }
    suspend fun rpc(name: String, payload: JSONObject): JSONObject { renew(); return post("/rest/v1/rpc/jam_$name", payload) }
    suspend fun signal(target: String?, event: String, payload: JSONObject) {
        if (room.isBlank()) return
        rpc("signal", JSONObject().put("room", room).put("target", target ?: JSONObject.NULL).put("event", event).put("payload", payload))
    }
    fun connect(roomId: String) {
        room = roomId; closed = false; reconnects = 0
        openSocket()
    }
    private fun openSocket() {
        val attempt = ++generation
        val url = base.replaceFirst("https://", "wss://") + "/realtime/v1/websocket?apikey=$key&vsn=1.0.0"
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    if (attempt != generation || closed) return@launch
                    joinRef = (ref + 1).toString()
                    send("phx_join", JSONObject().put("config", JSONObject()
                        .put("broadcast", JSONObject().put("self", false).put("ack", false))
                        .put("presence", JSONObject().put("enabled", false)).put("private", true))
                        .put("access_token", jwt))
                    heartbeat?.cancel()
                    heartbeat = scope.launch {
                        while (isActive && !closed && attempt == generation) {
                            delay(20_000)
                            try { renew(); send("heartbeat", JSONObject(), "phoenix") }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                socket?.cancel(); retry(attempt); break
                            }
                        }
                    }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 100_000) return
                scope.launch {
                    if (attempt != generation || closed) return@launch
                    runCatching {
                        val json = JSONObject(text)
                        val payload = json.optJSONObject("payload") ?: JSONObject()
                        when (json.optString("event")) {
                            "phx_reply" -> if (json.optString("ref") != joinRef) Unit else if (payload.optString("status") == "ok") {
                                reconnects = 0; status(true)
                            } else { event("error", JSONObject().put("message", "Private Realtime channel rejected. Check the Jam SQL policies.")); webSocket.close(1000, "Rejected") }
                            "broadcast" -> event(payload.getString("event"), payload.getJSONObject("payload"))
                            "phx_error", "phx_close" -> webSocket.close(1000, "Reconnect")
                        }
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { retry(attempt) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { retry(attempt) }
        })
    }
    private fun retry(attempt: Int) {
        scope.launch {
            if (attempt != generation || closed) return@launch
            heartbeat?.cancel(); status(false)
            delay((1000L shl reconnects.coerceAtMost(4)).coerceAtMost(15_000)); reconnects++
            if (attempt == generation && !closed) { runCatching { renew() }; openSocket() }
        }
    }
    private fun send(event: String, payload: JSONObject, topic: String = "realtime:jam:$room") {
        socket?.send(JSONObject().put("topic", topic).put("event", event).put("payload", payload).put("ref", (++ref).toString()).toString())
    }
    fun close() { closed = true; generation++; heartbeat?.cancel(); socket?.close(1000, "Jam ended"); socket = null; room = "" }
}
