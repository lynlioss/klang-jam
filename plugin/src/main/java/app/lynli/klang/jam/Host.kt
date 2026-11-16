package app.lynli.klang.jam

import android.os.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

internal data class HostResult(val json: JSONObject, val audio: ParcelFileDescriptor?)
internal class Host {
    private var messenger: Messenger? = null
    private var token = ""
    private val waiting = mutableMapOf<String, CompletableDeferred<HostResult>>()
    fun attach(message: Message) { messenger = message.replyTo; token = message.data.getString("token").orEmpty() }
    fun state(json: JSONObject) = send(10, json)
    fun result(message: Message) {
        val json = JSONObject(message.data.getString("json").orEmpty())
        val fd = message.data.getParcelable("audio", ParcelFileDescriptor::class.java)
        val deferred = waiting.remove(json.optString("requestId"))
        if (deferred == null) { fd?.close(); return }
        if (json.has("error")) { fd?.close(); deferred.completeExceptionally(IllegalStateException(json.getString("error"))) }
        else deferred.complete(HostResult(json, fd))
    }
    suspend fun request(operation: String, json: JSONObject = JSONObject()): HostResult {
        val id = UUID.randomUUID().toString()
        val result = CompletableDeferred<HostResult>()
        waiting[id] = result
        try {
            send(11, json.put("operation", operation).put("requestId", id))
            return withTimeout(130_000) { result.await() }
        } finally { waiting.remove(id) }
    }
    private fun send(what: Int, json: JSONObject) {
        messenger?.send(Message.obtain(null, what).apply { data = Bundle().apply { putString("token", token); putString("json", json.toString()) } })
    }
    fun close() { waiting.values.forEach { it.cancel() }; waiting.clear(); messenger = null }
}
