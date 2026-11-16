package app.lynli.klang.jam

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeersStartupTest {
    @Test fun nativeDataChannelStartupWorksWithoutMicrophonePermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        instrumentation.runOnMainSync {
            val peers = Peers(context, scope, JSONObject(), { _, _, _ -> },
                { _, _ -> false }, { _, _, _ -> }, { _, _ -> }, {})
            try { peers.ensure("test-peer", false) }
            finally { peers.close(); scope.cancel() }
        }
    }

    @Test fun soloRoomCanCloseBeforeNativeStartup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        instrumentation.runOnMainSync {
            val peers = Peers(instrumentation.targetContext, scope, JSONObject(), { _, _, _ -> },
                { _, _ -> false }, { _, _, _ -> }, { _, _ -> }, {})
            try { assertFalse(peers.isOpen("absent-peer")) }
            finally { peers.close(); scope.cancel() }
        }
    }
}
