package app.lynli.klang.jam

import android.content.ContextWrapper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class PeersConnectionTest {
    @Test fun twoPeersNegotiateAndTransferChunksWithoutTurnThenLeaveAndReconnect() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "connection-test").apply { deleteRecursively(); mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, failure ->
            Log.e("JamPeerTest", "Peer callback failed", failure)
        })
        val failures = mutableListOf<String>()
        val bytes = ByteArray(512 * 1024 + 17) { (it * 31).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = File(root, "source").apply { writeBytes(bytes) }
        val metadata = JSONObject().put("sha256", hash).put("bytes", bytes.size)
        fun device(name: String) = object : ContextWrapper(context) {
            override fun getCacheDir() = File(root, name).apply { mkdirs() }
        }
        try {
            repeat(2) {
                val hostOpened = CompletableDeferred<Unit>()
                val guestOpened = CompletableDeferred<Unit>()
                val received = CompletableDeferred<ByteArray>()
                val uploaded = CompletableDeferred<ByteArray>()
                val controlReceived = CompletableDeferred<String>()
                lateinit var host: Peers
                lateinit var guest: Peers
                withContext(Dispatchers.Main) {
                    host = Peers(device("host"), scope, JSONObject().put("stunUrl", ""),
                        { _, event, payload -> Log.i("JamPeerTest", "host sends $event"); guest.signal("host", event, payload) }, { sender, meta -> sender == "guest" && meta.optString("sha256") == hash && meta.optLong("bytes") == bytes.size.toLong() },
                        { _, _, incoming -> uploaded.complete(incoming.readBytes()) },
                        { _, status -> Log.i("JamPeerTest", "host: $status"); if (status.contains("failed", true)) failures.add(status) },
                        { hostOpened.complete(Unit) },
                        { _, kind, payload -> if (kind == "control") controlReceived.complete(payload.getString("operation")) })
                    guest = Peers(device("guest"), scope, JSONObject().put("stunUrl", ""),
                        { _, event, payload -> Log.i("JamPeerTest", "guest sends $event"); host.signal("guest", event, payload) },
                        { sender, meta -> sender == "host" && meta.optString("sha256") == hash && meta.optLong("bytes") == bytes.size.toLong() },
                        { _, _, incoming -> received.complete(incoming.readBytes()) },
                        { _, status -> Log.i("JamPeerTest", "guest: $status"); if (status.contains("failed", true)) failures.add(status) },
                        { guestOpened.complete(Unit) })
                }
                try {
                    withContext(Dispatchers.Main) { host.ensure("guest", true) }
                    withTimeout(30_000) { hostOpened.await(); guestOpened.await() }
                    withContext(Dispatchers.Main) { withTimeout(30_000) { host.send("guest", metadata, file) } }
                    assertArrayEquals(bytes, withTimeout(5000) { received.await() })
                    withContext(Dispatchers.Main) {
                        withTimeout(30_000) {
                            coroutineScope {
                                val upload = async { guest.send("host", metadata, file) }
                                delay(10)
                                assertTrue(guest.control("host", "control", JSONObject().put("operation", "pause")))
                                upload.await()
                            }
                        }
                    }
                    assertArrayEquals(bytes, withTimeout(5000) { uploaded.await() })
                    assertEquals("pause", withTimeout(5000) { controlReceived.await() })
                    assertTrue(failures.joinToString(), failures.isEmpty())
                } finally {
                    withContext(Dispatchers.Main) { host.close(); guest.close() }
                    // Let queued native callbacks run after disposal before the next session.
                    delay(250)
                }
            }
        } finally { scope.cancel(); root.deleteRecursively() }
    }
}
