package eu.darken.butler.common.flow

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transform
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource


fun <T> Flow<T>.throttleLatest(
    delay: Duration,
): Flow<T> = this
    .conflate()
    .transform {
        emit(it)
        delay(delay)
    }

fun <T> Flow<T>.throttleLatest(
    delay: Duration,
    timeSource: TimeSource = TimeSource.Monotonic,
    shouldThrottle: (T) -> Boolean = { true }
): Flow<T> = flow {
    require(!delay.isNegative()) { "Delay must be non-negative" }

    if (delay == Duration.ZERO) {
        return@flow collect { emit(it) }
    }

    var lastThrottledEmitMark: TimeMark? = null

    collect { item ->
        if (shouldThrottle(item)) {
            val currentMark = timeSource.markNow()
            val shouldEmit = lastThrottledEmitMark?.let {
                it.elapsedNow() >= delay
            } ?: true

            if (shouldEmit) {
                emit(item)
                lastThrottledEmitMark = currentMark
            }
        } else {
            emit(item)
        }
    }
}