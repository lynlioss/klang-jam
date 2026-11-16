package app.lynli.klang.jam

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackAnchorTest {
    private fun sync(seq: Long = 1, key: String = "song", playing: Boolean = true, anchor: Long = 1000) = JSONObject()
        .put("seq", seq).put("key", key).put("playing", playing).put("anchorMs", anchor)
        .put("positionMs", 0).put("durationMs", 180000)

    @Test fun repeatedSnapshotsDoNotReplayPausePlayOrSeek() {
        val tracker = PlaybackAnchor()
        assertNotNull(tracker.action(sync(), "song", 0, false, false, true, 1000, 1000))
        repeat(100) { index ->
            val now = 1100L + index * 250
            val action = tracker.action(sync(), "song", now - 1000, true, true, true, now, now)
            assertFalse(action?.has("playing") == true)
            assertFalse(action?.has("positionMs") == true)
        }
    }
    @Test fun lateFullDownloadSeeksToAnchorPositionOnce() {
        val tracker = PlaybackAnchor()
        val action = tracker.action(sync(), "previous", 0, false, false, false, 3000, 3000)!!
        assertEquals(2000L, action.getLong("positionMs"))
        assertTrue(action.getBoolean("playing"))
        assertNull(tracker.action(sync(), "song", 2000, true, true, true, 3000, 3001))
    }
    @Test fun futureStartWaitsAndPauseIsAppliedOnlyOnce() {
        val tracker = PlaybackAnchor()
        assertNull(tracker.action(sync(anchor = 2000), "song", 0, false, false, true, 1000, 1000))
        assertTrue(tracker.action(sync(anchor = 2000), "song", 0, false, false, true, 2000, 2000)!!.getBoolean("playing"))
        assertFalse(tracker.action(sync(seq = 2, playing = false), "song", 0, true, true, true, 2100, 2100)!!.getBoolean("playing"))
        assertNull(tracker.action(sync(seq = 2, playing = false), "song", 0, false, false, true, 2200, 2200))
    }
    @Test fun cachedAutomaticTransitionDoesNotSeekOrPause() {
        val tracker = PlaybackAnchor()
        tracker.action(sync(), "song", 0, true, true, true, 1000, 1000)
        val action = tracker.action(sync(seq = 2, key = "next", anchor = 5000), "next", 30, true, true, true, 5030, 5030)
        assertFalse(action?.has("playing") == true)
        assertFalse(action?.has("positionMs") == true)
    }
    @Test fun automaticAdvanceNeverRewindsToAnOldAnchor() {
        val tracker = PlaybackAnchor()
        tracker.action(sync(), "song", 0, true, true, true, 1000, 1000)
        assertNull(tracker.action(sync(), "next", 20, true, true, true, 181020, 181020))
    }
    @Test fun idleCachedFirstItemCanPrepareAndSmallDriftUsesSpeedOnly() {
        val tracker = PlaybackAnchor()
        assertNotNull(tracker.action(sync(), "song", 0, false, false, false, 1000, 1000))
        val action = tracker.action(sync(), "song", 4700, true, true, true, 6000, 6000)!!
        assertEquals(1.005, action.getDouble("speed"), 0.00001)
        assertFalse(action.has("playing")); assertFalse(action.has("positionMs"))
    }
}
