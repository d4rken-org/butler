package eu.darken.butler.common.flow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ThrottleLatestTest : BaseTest() {
    @Test
    fun `first throttled item emits immediately`(): Unit = runBlocking {
        val source = flowOf("PROGRESS", "RESULT")

        val results = source
            .throttleLatest(
                delay = 100.milliseconds,
                shouldThrottle = { it == "PROGRESS" }
            )
            .toList()

        results shouldBe listOf("PROGRESS", "RESULT")
    }

    @Test
    fun `subsequent throttled items within delay window are dropped`(): Unit = runBlocking {
        val timeSource = TestTimeSource()
        val source = flow {
            emit("PROGRESS")
            timeSource += 50.milliseconds
            emit("PROGRESS") // Should be dropped
            timeSource += 60.milliseconds // Total 110ms > 100ms threshold
            emit("PROGRESS") // Should emit
            emit("RESULT")
        }

        val results = source
            .throttleLatest(
                delay = 100.milliseconds,
                timeSource = timeSource,
                shouldThrottle = { it == "PROGRESS" }
            )
            .toList()

        results shouldBe listOf("PROGRESS", "PROGRESS", "RESULT")
    }

    @Test
    fun `non-throttled items always pass through`(): Unit = runBlocking {
        val source = flow {
            emit("PROGRESS")
            emit("RESULT1")
            emit("RESULT2")
            emit("PROGRESS")
            emit("RESULT3")
        }

        val results = source
            .throttleLatest(
                delay = 1.seconds,
                shouldThrottle = { it.startsWith("PROGRESS") }
            )
            .toList()

        results shouldBe listOf("PROGRESS", "RESULT1", "RESULT2", "RESULT3")
    }

    @Test
    fun `zero delay disables throttling`(): Unit = runBlocking {
        val source = flowOf("PROGRESS", "PROGRESS", "PROGRESS", "RESULT")

        val results = source
            .throttleLatest(
                delay = Duration.ZERO,
                shouldThrottle = { it == "PROGRESS" }
            )
            .toList()

        results shouldBe listOf("PROGRESS", "PROGRESS", "PROGRESS", "RESULT")
    }

    @Test
    fun `negative delay throws IllegalArgumentException`(): Unit = runBlocking {
        val source = flowOf("TEST")

        shouldThrow<IllegalArgumentException> {
            source
                .throttleLatest(
                    delay = (-100).milliseconds,
                    shouldThrottle = { true }
                )
                .collect()
        }
    }

    @Test
    fun `exception in predicate propagates`(): Unit = runBlocking {
        val source = flowOf("TEST")

        shouldThrow<IOException> {
            source
                .throttleLatest(
                    delay = 100.milliseconds,
                    shouldThrottle = { throw IOException("Test error") }
                )
                .collect()
        }
    }

    @Test
    fun `cancellation propagates correctly`(): Unit = runBlocking {
        val firstEmission = CompletableDeferred<Unit>()
        val source = flow {
            emit("PROGRESS")
            delay(1.seconds)
            emit("SHOULD_NOT_EMIT")
        }

        val job = launch {
            source
                .throttleLatest(
                    delay = 100.milliseconds,
                    shouldThrottle = { true }
                )
                .collect { firstEmission.complete(Unit) }
        }

        // Explicit signal instead of a fixed delay: cancel once the flow is actually running.
        firstEmission.await()
        job.cancel()
        job.join()

        job.isCancelled shouldBe true
    }

    @Test
    fun `maintains order of emissions`(): Unit = runBlocking {
        val timeSource = TestTimeSource()
        val source = flow {
            emit("A")
            emit("B")
            timeSource += 150.milliseconds
            emit("C")
            emit("D")
        }

        val results = source
            .throttleLatest(
                delay = 100.milliseconds,
                timeSource = timeSource,
                shouldThrottle = { it in listOf("A", "C") }
            )
            .toList()

        results shouldBe listOf("A", "B", "C", "D")
    }

    @Test
    fun `concurrent collectors work independently`(): Unit = runBlocking {
        val timeSource = TestTimeSource()
        val source = MutableSharedFlow<String>()

        val collector1 = async {
            source
                .throttleLatest(
                    delay = 100.milliseconds,
                    timeSource = timeSource,
                    shouldThrottle = { true }
                )
                .take(3)
                .toList()
        }

        val collector2 = async {
            source
                .throttleLatest(
                    delay = 200.milliseconds,
                    timeSource = timeSource,
                    shouldThrottle = { true }
                )
                .take(2)
                .toList()
        }

        // Explicit signal instead of a timing guess: emit only once both collectors are subscribed.
        // The unbuffered SharedFlow then makes each emit() a rendezvous, so both collectors have
        // processed an item before the clock is moved on.
        source.subscriptionCount.first { it == 2 }

        timeSource += 50.milliseconds
        source.emit("ITEM1")
        timeSource += 150.milliseconds // 200ms total
        source.emit("ITEM2")
        timeSource += 100.milliseconds // 300ms total
        source.emit("ITEM3")

        val results1 = collector1.await()
        val results2 = collector2.await()

        results1 shouldBe listOf("ITEM1", "ITEM2", "ITEM3")
        results2 shouldBe listOf("ITEM1", "ITEM3") // ITEM2 dropped, ITEM3 at 300ms > 200ms threshold
    }

    @Test
    fun `works with high frequency emissions`(): Unit = runBlocking {
        val timeSource = TestTimeSource()
        val source = flow {
            repeat(1000) { index ->
                emit("PROGRESS_$index")
                timeSource += 1.milliseconds
            }
            emit("RESULT")
        }

        val results = source
            .throttleLatest(
                delay = 50.milliseconds,
                timeSource = timeSource,
                shouldThrottle = { it.startsWith("PROGRESS") }
            )
            .toList()

        // 1000 items, 1ms apart, 50ms throttle: the first item passes, then exactly every 50th.
        // Asserting the exact retained set, not a range, so removing the throttle fails the test.
        results shouldBe (0..950 step 50).map { "PROGRESS_$it" } + "RESULT"
    }

    @Test
    fun `works with high frequency emissions2`(): Unit = runBlocking {
        val timeSource = TestTimeSource()
        val source = flow {
            repeat(100) { index ->
                emit("PROGRESS_$index")
                timeSource += 10.milliseconds
            }
            emit("RESULT")
        }

        val results = source
            .throttleLatest(
                delay = 50.milliseconds,
                timeSource = timeSource,
                shouldThrottle = { it.startsWith("PROGRESS") }
            )
            .toList()

        // 100 items, 10ms apart, 50ms throttle: the first item passes, then exactly every 5th.
        results shouldBe (0..95 step 5).map { "PROGRESS_$it" } + "RESULT"
    }
}