package eu.darken.butler.common.flow

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.TimeSource

fun <T> Flow<T>.chunked(chunkSize: Int): Flow<List<T>> {
    val buffer = mutableListOf<T>()
    return flow {
        this@chunked.collect {
            buffer.add(it)
            if (buffer.size == chunkSize) {
                emit(buffer.toList())
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) {
            emit(buffer.toList())
        }
    }
}

private sealed interface ChunkSignal<out T> {
    data class Element<T>(val value: T) : ChunkSignal<T>
    data class Failure(val error: Throwable) : ChunkSignal<Nothing>
}

/**
 * Buffers upstream elements into chunks, emitting when [maxSize] elements have accumulated or
 * [maxInterval] has elapsed since the chunk's first element, whichever comes first. A lone
 * element is emitted [maxInterval] after it was buffered even if the upstream then stays silent.
 *
 * A partial chunk is flushed when the upstream completes, and also before an upstream failure or
 * upstream cancellation is rethrown, so already-collected elements are never dropped.
 *
 * [timeSource] only exists as a seam for virtual-time tests.
 */
fun <T> Flow<T>.chunked(
    maxSize: Int,
    maxInterval: Duration,
    timeSource: TimeSource = TimeSource.Monotonic,
): Flow<List<T>> {
    require(maxSize > 0) { "maxSize must be positive" }
    require(maxInterval.isPositive()) { "maxInterval must be positive" }
    return flow {
        coroutineScope {
            // Errors are materialized so the producer coroutine never fails (which would cancel
            // this scope before the final flush could happen).
            val upstream = this@chunked
                .map<T, ChunkSignal<T>> { ChunkSignal.Element(it) }
                .catch { emit(ChunkSignal.Failure(it)) }
                .produceIn(this)

            val buffer = ArrayList<T>(maxSize)
            var upstreamError: Throwable? = null
            var open = true

            while (open) {
                val first = upstream.receiveCatching()
                if (first.isClosed) break
                when (val signal = first.getOrThrow()) {
                    is ChunkSignal.Element -> buffer += signal.value
                    is ChunkSignal.Failure -> {
                        upstreamError = signal.error
                        open = false
                    }
                }

                if (open) {
                    val chunkStart = timeSource.markNow()
                    while (buffer.size < maxSize) {
                        val remaining = maxInterval - chunkStart.elapsedNow()
                        if (!remaining.isPositive()) break
                        val timedOut = select {
                            upstream.onReceiveCatching { result ->
                                when {
                                    result.isClosed -> open = false
                                    else -> when (val signal = result.getOrThrow()) {
                                        is ChunkSignal.Element -> buffer += signal.value
                                        is ChunkSignal.Failure -> {
                                            upstreamError = signal.error
                                            open = false
                                        }
                                    }
                                }
                                false
                            }
                            onTimeout(remaining) { true }
                        }
                        if (timedOut || !open) break
                    }
                }

                if (buffer.isNotEmpty()) {
                    emit(buffer.toList())
                    buffer.clear()
                }
            }

            upstreamError?.let { throw it }
        }
    }
}
