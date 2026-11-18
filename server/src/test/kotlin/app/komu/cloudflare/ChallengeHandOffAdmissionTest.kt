package app.komu.cloudflare

import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChallengeHandOffAdmissionTest {
    private lateinit var server: ServerSocket
    private val accepted = AtomicInteger()
    private val client = OkHttpClient()

    @BeforeEach
    fun setUp() {
        // A loopback endpoint that accepts and never answers: a send stays in flight.
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                runCatching { server.accept() }.onSuccess { accepted.incrementAndGet() }
            }
        }
        ChallengeHandOffAdmission.hostManaged = true
        ChallengeHandOffAdmission.settings = {
            ChallengeHandOffAdmission.Settings(true, "http://127.0.0.1:${server.localPort}", "session")
        }
        ChallengeHandOffAdmission.closeAndDrain(1_000)
    }

    @AfterEach
    fun tearDown() {
        ChallengeHandOffAdmission.closeAndDrain(1_000)
        server.close()
    }

    private suspend fun send(): String =
        ChallengeHandOffAdmission.send({ url, _ -> client.newCall(Request.Builder().url("$url/v1").build()) }) {
            it.awaitSuccess().use { response -> response.code.toString() }
        }

    @Test
    fun `a host-managed hand-off admits nothing before the host opens it`() {
        assertFailsWith<IOException> { runBlocking { send() } }
        assertEquals(0, accepted.get())
    }

    @Test
    fun `the switch-off cancels and awaits the admitted sends before it returns`() =
        runBlocking {
            ChallengeHandOffAdmission.open()
            val started = CompletableDeferred<Unit>()
            val pending =
                async(Dispatchers.IO) {
                    runCatching {
                        started.complete(Unit)
                        send()
                    }
                }
            started.await()
            withTimeout(5_000) { while (ChallengeHandOffAdmission.inFlightCount() == 0) Thread.sleep(10) }
            assertTrue(ChallengeHandOffAdmission.closeAndDrain(2_000))
            assertEquals(0, ChallengeHandOffAdmission.inFlightCount())
            assertTrue(pending.await().isFailure)
            // Closed: a request that was waiting for its turn is refused.
            assertFailsWith<IOException> { send() }
            Unit
        }

    @Test
    fun `desktop admission follows the setting alone`() {
        ChallengeHandOffAdmission.hostManaged = false
        try {
            ChallengeHandOffAdmission.settings = {
                ChallengeHandOffAdmission.Settings(false, "http://127.0.0.1:${server.localPort}", "session")
            }
            assertFailsWith<IOException> { runBlocking { send() } }
        } finally {
            ChallengeHandOffAdmission.hostManaged = true
        }
    }
}
