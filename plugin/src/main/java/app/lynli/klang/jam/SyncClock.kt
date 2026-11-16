package app.lynli.klang.jam

import kotlin.math.abs

/** Four-timestamp NTP exchange uses monotonic clocks and chooses the lowest RTT sample. */
internal class SyncClock {
    private var bestRtt = Long.MAX_VALUE
    var offsetMs = 0L; private set
    var calibrated = false; private set
    fun sample(t0: Long, t1: Long, t2: Long, t3: Long): Boolean {
        val rtt = (t3 - t0) - (t2 - t1)
        if (rtt !in 0..5000 || rtt > bestRtt + 20) return false
        bestRtt = minOf(bestRtt, rtt)
        offsetMs = ((t1 - t0) + (t2 - t3)) / 2
        calibrated = true
        return true
    }
    fun leaderTime(localMs: Long) = localMs + offsetMs
    fun resetWindow() { bestRtt = Long.MAX_VALUE }
    companion object {
        fun target(position: Long, anchor: Long, now: Long, playing: Boolean, duration: Long): Long =
            (position + if (playing) (now - anchor).coerceAtLeast(0) else 0).coerceIn(0, duration.coerceAtLeast(position))
        fun correction(errorMs: Long): Float = if (abs(errorMs) < 30) 1f else if (errorMs > 0) 1.015f else 0.985f
    }
}
