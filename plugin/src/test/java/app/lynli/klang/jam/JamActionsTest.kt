package app.lynli.klang.jam

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class JamActionsTest {
    @Test fun networkAndNativeLoadingFailuresAreReportedWithoutStoppingFurtherActions() = runBlocking {
        val reported = mutableListOf<Throwable>()
        val network = IOException("Offline")
        val native = UnsatisfiedLinkError("Missing WebRTC library")
        jamAction(reported::add) { throw network }
        jamAction(reported::add) { throw native }
        var completed = false
        jamAction(reported::add) { completed = true }
        assertEquals(listOf(network, native), reported)
        assertTrue(completed)
    }
    @Test fun cancellationStopsSessionWorkWithoutShowingAnError() = runBlocking {
        val cancelled = CancellationException("Jam ended")
        try {
            jamAction({ fail("Cancellation must not appear as an error") }) { throw cancelled }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }
    @Test fun fatalVmErrorsAreNotTreatedAsRecoverable() = runBlocking {
        val fatal = OutOfMemoryError("Fatal")
        try {
            jamAction({ fail("Fatal VM errors must propagate") }) { throw fatal }
            fail("Fatal VM error must propagate")
        } catch (actual: OutOfMemoryError) { assertSame(fatal, actual) }
    }
}
