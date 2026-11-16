package app.lynli.klang.jam

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JamQueueTest {
    private fun song(key: String, owner: String = "leader") = JSONObject().put("key", key).put("owner", owner)
    private fun command(mode: String, index: Int = 0) = JSONObject().put("mode", mode).put("index", index)
    @Test fun membersAppendInsertReorderRemoveAndChooseTheirOwnSongs() {
        val queue = JamQueue()
        queue.mutate(command("replace"), listOf(song("a"), song("b")))
        queue.mutate(command("next"), listOf(song("guest", "member")))
        assertEquals(listOf("a", "guest", "b"), queue.tracks.map { it.getString("key") })
        queue.mutate(command("move", 1).put("to", 2))
        assertEquals("a", queue.currentKey)
        queue.mutate(command("jump", 2))
        assertEquals("member", queue.current!!.getString("owner"))
        queue.mutate(command("remove", 0))
        assertEquals("guest", queue.currentKey)
        queue.mutate(command("clear")); assertNull(queue.current)
    }
    @Test fun rejectedOversizedEditLeavesQueueAndRevisionUntouched() {
        val queue = JamQueue()
        queue.mutate(command("replace"), (0 until 32).map { song("$it") })
        val before = queue.payload().toString()
        assertTrue(runCatching { queue.mutate(command("append"), listOf(song("extra"))) }.isFailure)
        assertEquals(before, queue.payload().toString())
    }
    @Test fun invalidOrStaleRemoteQueuesDoNotDestroyPreparedQueue() {
        val queue = JamQueue(); queue.mutate(command("replace"), listOf(song("a")))
        val before = queue.payload().toString()
        assertTrue(runCatching { queue.receive(JSONObject().put("revision", 2).put("key", "missing").put("tracks", JSONArray().put(song("a")))) }.isFailure)
        assertEquals(before, queue.payload().toString())
        queue.receive(JSONObject().put("revision", 0))
        assertEquals(before, queue.payload().toString())
    }
    @Test fun rangeReplacementPreservesPlayingEntryAndAcceptsDuplicateAudioWithUniqueKeys() {
        val queue = JamQueue(); queue.mutate(command("replace"), listOf(song("a"), song("b"), song("c")))
        queue.mutate(command("replace_range", 1).put("end", 3), listOf(song("new")))
        assertEquals(listOf("a", "new"), queue.tracks.map { it.getString("key") })
        assertEquals("a", queue.currentKey)
    }
}
