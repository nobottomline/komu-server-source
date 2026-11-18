package app.komu.library

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryCommitGateTest {
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private val committed = AtomicBoolean(false)

    private val host =
        object : LibraryCommitGate.Host {
            override fun prepared() {
                events += "prepared"
            }

            override fun committed(): Boolean = committed.get()
        }

    @AfterEach
    fun tearDown() {
        LibraryCommitGate.host = null
    }

    @Test
    fun `the host hears of the library only after the checkpoint`() {
        LibraryCommitGate.host = host
        LibraryCommitGate.publishPrepared { events += "checkpoint" }
        assertEquals(listOf("checkpoint", "prepared"), events)
    }

    @Test
    fun `a failed checkpoint is never reported as prepared`() {
        LibraryCommitGate.host = host
        assertFailsWith<IllegalStateException> { LibraryCommitGate.publishPrepared { error("sync failed") } }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `background work waits for the host's commit`() {
        LibraryCommitGate.host = host
        LibraryCommitGate.pollMillis = 10
        val ran = CountDownLatch(1)
        LibraryCommitGate.afterCommit("test") { ran.countDown() }
        assertFalse(ran.await(200, TimeUnit.MILLISECONDS))
        committed.set(true)
        assertTrue(ran.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `desktop has no host and runs everything at once`() {
        LibraryCommitGate.host = null
        var checkpointed = false
        LibraryCommitGate.publishPrepared { checkpointed = true }
        assertFalse(checkpointed)
        var ran = false
        LibraryCommitGate.afterCommit("test") { ran = true }
        assertTrue(ran)
    }
}
