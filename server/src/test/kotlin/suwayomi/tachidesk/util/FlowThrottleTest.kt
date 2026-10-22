package suwayomi.tachidesk.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FlowThrottleTest {
    @Test
    fun `idle signal flow schedules no timer before or after a burst`() =
        runTest {
            val signals = Channel<Int>(Channel.CONFLATED)
            val received = mutableListOf<Int>()
            val collector = launch { signals.receiveAsFlow().sampleWhenPending(1.seconds).collect { received += it } }
            runCurrent()
            advanceUntilIdle()
            assertEquals(0L, testScheduler.currentTime)
            signals.send(1)
            runCurrent()
            advanceTimeBy(999)
            runCurrent()
            assertEquals(emptyList(), received)
            signals.send(2)
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(2), received)
            advanceUntilIdle()
            assertEquals(1000L, testScheduler.currentTime)
            collector.cancelAndJoin()
        }

    @Test
    fun `continuous progress delivers bounded updates and the final value`() =
        runTest {
            val received = mutableListOf<Pair<Long, Int>>()
            flow {
                repeat(35) {
                    emit(it)
                    delay(100)
                }
            }.sampleWhenPending(1.seconds).collect { received += testScheduler.currentTime to it }
            assertEquals(4, received.size)
            assertEquals(34, received.last().second)
            assertEquals(listOf(1000L, 2000L, 3000L, 4000L), received.map { it.first })
        }

    @Test
    fun `finite burst delivers trailing value instead of losing completion`() =
        runTest {
            val received = mutableListOf<Int>()
            (1..100).asFlow().sampleWhenPending(1.seconds).collect { received += it }
            assertEquals(listOf(100), received)
        }

    @Test
    fun `reentrant capacity release rearms work after delivery`() =
        runTest {
            val signals = Channel<Int>(Channel.CONFLATED)
            signals.send(1)
            val received = mutableListOf<Int>()
            val collector =
                launch {
                    signals.receiveAsFlow().sampleWhenPending(1.seconds).collect {
                        received += it
                        if (it == 1) signals.send(2) else signals.close()
                    }
                }
            advanceUntilIdle()
            collector.join()
            assertEquals(listOf(1, 2), received)
            assertEquals(2000L, testScheduler.currentTime)
        }

    @Test
    fun `cancellation drops pending work and leaves no delayed emission`() =
        runTest {
            val signals = Channel<Int>(Channel.CONFLATED)
            val received = mutableListOf<Int>()
            val collector = launch { signals.receiveAsFlow().sampleWhenPending(1.seconds).collect { received += it } }
            signals.send(1)
            runCurrent()
            collector.cancelAndJoin()
            advanceUntilIdle()
            assertEquals(emptyList(), received)
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `upstream failure propagates without hanging the consumer`() =
        runTest {
            assertFailsWith<IllegalStateException> {
                flow<Int> { throw IllegalStateException("fixture") }.sampleWhenPending(1.seconds).collect {}
            }
        }
}
