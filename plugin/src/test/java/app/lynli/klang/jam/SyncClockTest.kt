package app.lynli.klang.jam
import org.junit.Assert.*
import org.junit.Test
class SyncClockTest {
    @Test fun offsetsIgnoreWallClocksAndSlowSamples() {
        val clock = SyncClock()
        assertTrue(clock.sample(1000, 6030, 6040, 1070))
        assertEquals(5000, clock.offsetMs)
        assertFalse(clock.sample(2000, 7500, 7510, 3010))
        assertEquals(5000, clock.offsetMs)
        assertEquals(9000, clock.leaderTime(4000))
    }
    @Test fun lateDownloadCatchesUpWithoutDelayingEveryone() {
        assertEquals(12000, SyncClock.target(10000, 5000, 7000, true, 180000))
        assertEquals(10000, SyncClock.target(10000, 5000, 7000, false, 180000))
        assertEquals(10000, SyncClock.target(10000, 7000, 5000, true, 180000))
        assertEquals(180000, SyncClock.target(179000, 5000, 7000, true, 180000))
    }
    @Test fun driftCorrectionConvergesWithoutPitchJumpsFromSeeks() {
        assertEquals(1f, SyncClock.correction(20))
        assertTrue(SyncClock.correction(60)>1f)
        assertTrue(SyncClock.correction(-60)<1f)
    }
}
