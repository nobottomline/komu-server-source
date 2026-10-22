package suwayomi.tachidesk.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Coalesces updates in windows armed by the first pending event. Unlike Flow.sample,
 * there is no timer while idle; unlike debounce, sustained progress cannot starve delivery.
 * A finite upstream's final value is delivered before completion. Cancellation discards pending work.
 * Only the latest pending value is retained, so callers must accumulate deltas before signalling.
 */
internal fun <T> Flow<T>.sampleWhenPending(period: Duration): Flow<T> {
    require(period.isPositive() && period.isFinite())
    return flow {
        coroutineScope {
            val pending = Channel<T>(Channel.CONFLATED)
            val producer =
                launch {
                    try {
                        this@sampleWhenPending.collect { pending.send(it) }
                    } finally {
                        pending.close()
                    }
                }
            try {
                for (first in pending) {
                    delay(period)
                    // One conflated receive is sufficient. Draining in a loop could starve
                    // delivery if another thread produces faster than this collector.
                    val update = pending.tryReceive()
                    val latest = if (update.isSuccess) update.getOrThrow() else first
                    emit(latest)
                }
            } finally {
                producer.cancel()
                pending.cancel()
            }
        }
    }
}
