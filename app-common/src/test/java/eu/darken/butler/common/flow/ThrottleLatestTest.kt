package eu.darken.butler.common.flow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
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
        val source = flow {
            emit("PROGRESS")
            delay(50.milliseconds)
            emit("PROGRESS") // Should be dropped
            delay(60.milliseconds) // Total 110ms > 100ms threshold
            emit("PROGRESS") // Should emit
            emit("RESULT")
        }

        val results = source
            .throttleLatest(
                delay = 100.milliseconds,
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
                .collect()
        }

        delay(100.milliseconds)
        job.cancel()
        job.join()

        job.isCancelled shouldBe true
    }

    @Test
    fun `maintains order of emissions`(): Unit = runBlocking {
        val source = flow {
            emit("A")
            emit("B")
            delay(150.milliseconds)
            emit("C")
            emit("D")
        }

        val results = source
            .throttleLatest(
                delay = 100.milliseconds,
                shouldThrottle = { it in listOf("A", "C") }
            )
            .toList()

        results shouldBe listOf("A", "B", "C", "D")
    }

    @Test
    fun `concurrent collectors work independently`(): Unit = runBlocking {
        val source = MutableSharedFlow<String>()

        val collector1 = async {
            source
                .throttleLatest(
                    delay = 100.milliseconds,
                    shouldThrottle = { true }
                )
                .take(3)
                .toList()
        }

        val collector2 = async {
            source
                .throttleLatest(
                    delay = 200.milliseconds,
                    shouldThrottle = { true }
                )
                .take(2)
                .toList()
        }

        delay(50.milliseconds)
        source.emit("ITEM1")
        delay(150.milliseconds) // 200ms total
        source.emit("ITEM2")
        delay(100.milliseconds) // 300ms total
        source.emit("ITEM3")

        val results1 = collector1.await()
        val results2 = collector2.await()

        results1 shouldBe listOf("ITEM1", "ITEM2", "ITEM3")
        results2 shouldBe listOf("ITEM1", "ITEM3") // ITEM2 dropped, ITEM3 at 300ms > 200ms threshold
    }

    @Test
    fun `works with high frequency emissions`(): Unit = runBlocking {
        val source = flow {
            repeat(1000) { index ->
                emit("PROGRESS_$index")
                delay(1.milliseconds)
            }
            emit("RESULT")
        }

        val results = source
            .throttleLatest(
                delay = 50.milliseconds,
                shouldThrottle = { it.startsWith("PROGRESS") }
            )
            .toList()

        // Should get approximately 20 progress items (1000ms / 50ms) plus RESULT
        results.size shouldBeInRange 18..23
        results.last() shouldBe "RESULT"
        results.all { it.startsWith("PROGRESS") || it == "RESULT" } shouldBe true
    }

    @Test
    fun `works with high frequency emissions2`(): Unit = runBlocking {
        val source = flow {
            repeat(100) { index ->  // Reduced from 1000
                emit("PROGRESS_$index")
                delay(10.milliseconds)  // Increased from 1ms
            }
            emit("RESULT")
        }

        val results = source
            .throttleLatest(
                delay = 50.milliseconds,
                shouldThrottle = { it.startsWith("PROGRESS") }
            )
            .toList()

        // 100 items * 10ms = 1000ms total
        // With 50ms throttle: expect ~20 items plus RESULT
        println("Results size: ${results.size}")
        println("Results: $results")

        results.size shouldBeInRange 18..23
        results.last() shouldBe "RESULT"
    }
}