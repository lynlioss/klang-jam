package app.lynli.klang.jam

import org.json.JSONObject
import kotlin.math.abs

/** A playback command is applied once; snapshots never replay play/pause or preparation. */
internal class PlaybackAnchor {
    private var appliedSequence = -1L
    private var lastCorrection = 0L
    fun reset() { appliedSequence = -1; lastCorrection = 0 }
    fun action(sync: JSONObject, key: String, position: Long, advancing: Boolean, playWhenReady: Boolean,
        ready: Boolean, leaderNow: Long, localNow: Long): JSONObject? {
        val seq = sync.getLong("seq")
        val playing = sync.optBoolean("playing")
        if (playing && leaderNow < sync.getLong("anchorMs")) return null
        val target = SyncClock.target(sync.getLong("positionMs"), sync.getLong("anchorMs"), leaderNow,
            playing, sync.optLong("durationMs"))
        val changed = key != sync.getString("key")
        val command = seq != appliedSequence
        // Native playback may advance before the leader's transition message arrives.
        if (changed && !command) return null
        val error = target - position
        if (!changed && !command && localNow - lastCorrection < 5000) return null
        if (!ready && !changed && !command) return null
        if (seq < appliedSequence) return null
        val result = JSONObject().put("key", sync.getString("key"))
        if (changed || command && playing != playWhenReady) result.put("playing", playing)
        if (changed || command && (!ready || abs(error) > 250) || ready && advancing && abs(error) > 1500) {
            result.put("positionMs", target).put("speed", 1)
        } else if (ready && advancing) {
            result.put("speed", if (abs(error) <= 100) 1.0 else if (error > 0) 1.005 else 0.995)
        }
        appliedSequence = seq; lastCorrection = localNow
        return result.takeIf { it.length() > 1 }
    }
}
