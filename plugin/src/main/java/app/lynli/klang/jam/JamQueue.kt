package app.lynli.klang.jam

import org.json.JSONArray
import org.json.JSONObject

/** The leader serializes queue edits; keys identify entries independently of file hashes. */
internal class JamQueue {
    val tracks = mutableListOf<JSONObject>()
    var currentKey = ""
    var nextKey = ""
    var revision = 0L; private set
    val current get() = tracks.firstOrNull { it.optString("key") == currentKey }
    val index get() = tracks.indexOfFirst { it.optString("key") == currentKey }.coerceAtLeast(0)
    val next get() = tracks.getOrNull(index + 1)
    fun find(key: String) = tracks.firstOrNull { it.optString("key") == key }
    fun mutate(command: JSONObject, additions: List<JSONObject> = emptyList()) {
        val updated = tracks.toMutableList()
        val oldKey = currentKey
        val index = command.optInt("index", 0)
        try {
        tracks.clear(); tracks.addAll(updated)
        when (command.optString("mode")) {
            "replace" -> { tracks.clear(); tracks.addAll(additions); currentKey = tracks.getOrNull(index)?.getString("key") ?: tracks.firstOrNull()?.getString("key").orEmpty() }
            "append" -> tracks.addAll(additions)
            "next", "insert" -> tracks.addAll((if (command.optString("mode") == "next") this.index + 1 else index).coerceIn(0, tracks.size), additions)
            "replace_range" -> { val end = command.optInt("end", index + 1); require(index in 0..end && end <= tracks.size); tracks.subList(index, end).clear(); tracks.addAll(index, additions) }
            "remove" -> { val end = command.optInt("end", index + 1).coerceAtMost(tracks.size); require(index in 0..end); tracks.subList(index, end).clear() }
            "move" -> { require(index in tracks.indices); val end = command.optInt("end", index + 1).coerceIn(index + 1, tracks.size); val moved = tracks.subList(index, end).toList(); tracks.subList(index, end).clear(); tracks.addAll(command.getInt("to").coerceIn(0, tracks.size), moved) }
            "jump" -> currentKey = tracks.getOrNull(index)?.getString("key") ?: error("Song is outside the Jam queue")
            "clear" -> { tracks.clear(); currentKey = "" }
            else -> error("Unsupported Jam queue action")
        }
        require(tracks.size <= 32) { "The Jam queue holds up to 32 songs" }
        if (find(currentKey) == null) currentKey = tracks.firstOrNull()?.getString("key").orEmpty()
        require(tracks.map { it.getString("key") }.distinct().size == tracks.size) { "Duplicate queue entries" }
        revision++
        } catch (failure: Exception) { tracks.clear(); tracks.addAll(updated); currentKey = oldKey; throw failure }
    }
    fun changed() { revision++ }
    fun payload(): JSONObject = JSONObject().put("revision", revision).put("key", currentKey).put("tracks", JSONArray(tracks))
    fun receive(payload: JSONObject) {
        if (payload.getLong("revision") < revision) return
        val entries = payload.getJSONArray("tracks"); require(entries.length() <= 32)
        val incoming = (0 until entries.length()).map { entries.getJSONObject(it) }
        val keys = incoming.map { it.getString("key") }
        require(keys.distinct().size == keys.size)
        val key = payload.optString("key")
        require(key in keys || key.isBlank() && keys.isEmpty())
        tracks.clear(); tracks.addAll(incoming)
        currentKey = key; nextKey = payload.optString("nextKey"); revision = payload.getLong("revision")
    }
    fun clear() { tracks.clear(); currentKey = ""; nextKey = ""; revision = 0 }
}
