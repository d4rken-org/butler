package eu.darken.butler.common.flow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class ChunkedFlowTest : BaseTest() {

    @Test
    fun `size cap triggers emission and remainder is flushed on completion`(): Unit = runTest {
        val chunks = flowOf(1, 2, 3, 4, 5)
            .chunked(maxSize = 2, maxInterval = 1.hours, timeSource = testScheduler.timeSource)
            .toList()

        chunks shouldBe listOf(listOf(1, 2), listOf(3, 4), listOf(5))
    }

    @Test
    fun `lone element is emitted after interval even when upstream stays silent`(): Unit = runTest {
        val chunks = flow {
            emit(1)
            delay(10.hours)
            emit(2)
        }
            .chunked(maxSize = 10, maxInterval = 100.milliseconds, timeSource = testScheduler.timeSource)
            .toList()

        chunks shouldBe listOf(listOf(1), listOf(2))
    }

    @Test
    fun `interval is measured from the chunk's first element`(): Unit = runTest {
        val chunks = flow {
            emit(1)
            delay(60.milliseconds)
            emit(2)
            delay(60.milliseconds) // 120ms after first element: chunk closed at 100ms
            emit(3)
        }
            .chunked(maxSize = 10, maxInterval = 100.milliseconds, timeSource = testScheduler.timeSource)
            .toList()

        chunks shouldBe listOf(listOf(1, 2), listOf(3))
    }

    @Test
    fun `partial chunk is flushed before upstream failure is rethrown`(): Unit = runTest {
        val received = mutableListOf<List<Int>>()

        shouldThrow<IOException> {
            flow {
                emit(1)
                emit(2)
                throw IOException("boom")
            }
                .chunked(maxSize = 10, maxInterval = 1.hours, timeSource = testScheduler.timeSource)
                .collect { received += it }
        }

        received shouldBe listOf(listOf(1, 2))
    }

    @Test
    fun `partial chunk is flushed before upstream cancellation is rethrown`(): Unit = runTest {
        val received = mutableListOf<List<Int>>()

        shouldThrow<kotlinx.coroutines.CancellationException> {
            channelFlow {
                send(1)
                send(2)
                // Let the elements reach the operator before cancelling — cancelling a
                // channelFlow drops anything still sitting in its own buffer.
                delay(10.milliseconds)
                cancel("max results reached")
                awaitClose()
            }
                .chunked(maxSize = 10, maxInterval = 1.hours, timeSource = testScheduler.timeSource)
                .collect { received += it }
        }

        received shouldBe listOf(listOf(1, 2))
    }

    @Test
    fun `invalid arguments are rejected`(): Unit = runTest {
        shouldThrow<IllegalArgumentException> {
            flowOf(1).chunked(maxSize = 0, maxInterval = 1.hours)
        }
        shouldThrow<IllegalArgumentException> {
            flowOf(1).chunked(maxSize = 1, maxInterval = 0.milliseconds)
        }
    }
}
