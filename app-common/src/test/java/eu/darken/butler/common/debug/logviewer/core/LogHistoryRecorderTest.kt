package eu.darken.butler.common.debug.logviewer.core

import eu.darken.butler.common.debug.logging.Logging
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class LogHistoryRecorderTest : BaseTest() {

    @BeforeEach
    fun setup() = Logging.clearAll()

    @AfterEach
    fun tearDown() = Logging.clearAll()

    private fun create() = LogHistoryRecorder()

    private fun LogHistoryRecorder.emit(message: String, priority: Logging.Priority = Logging.Priority.DEBUG) =
        log(priority, "TestTag", message, null)

    @Test
    fun `ring buffer caps at BUFFER_CAP and drops oldest`() {
        val recorder = create()
        val total = LogHistoryRecorder.BUFFER_CAP + 500
        repeat(total) { recorder.emit("line $it") }

        val snapshot = recorder.snapshot()
        snapshot shouldHaveSize LogHistoryRecorder.BUFFER_CAP
        snapshot.first().id shouldBe (total - LogHistoryRecorder.BUFFER_CAP).toLong()
        snapshot.last().id shouldBe (total - 1).toLong()
    }

    @Test
    fun `multiline messages are split into separate lines`() {
        val recorder = create()
        recorder.emit("line1\nline2\nline3")

        val snapshot = recorder.snapshot()
        snapshot shouldHaveSize 3
        snapshot.map { it.message } shouldBe listOf("line1", "line2", "line3")
    }

    @Test
    fun `oversized messages are truncated before the multiline split`() {
        val recorder = create()
        // The newline sits beyond the cap, so truncation must remove it: exactly one row results.
        recorder.emit("a".repeat(LogHistoryRecorder.MAX_ENTRY_CHARS) + "\nb")

        val snapshot = recorder.snapshot()
        snapshot shouldHaveSize 1
        snapshot.single().message shouldEndWith "…[truncated]"
        // The marker fits WITHIN the cap, it doesn't extend it.
        snapshot.single().message.length shouldBe LogHistoryRecorder.MAX_ENTRY_CHARS
    }

    @Test
    fun `total char budget evicts oldest rows`() {
        val recorder = create()
        val bigLine = "x".repeat(LogHistoryRecorder.MAX_ENTRY_CHARS - 100)
        val total = 600
        repeat(total) { recorder.emit(bigLine) }

        val snapshot = recorder.snapshot()
        snapshot.size shouldBeLessThan total
        val retainedChars = snapshot.sumOf { (it.message.length + it.tag.length).toLong() }
        retainedChars shouldBeLessThanOrEqual LogHistoryRecorder.MAX_TOTAL_CHARS
        // Newest survives, oldest went first.
        snapshot.last().id shouldBe (total - 1).toLong()
    }

    @Test
    fun `verbose is filtered by default, others are loggable`() {
        val recorder = create()
        recorder.isLoggable(Logging.Priority.VERBOSE) shouldBe false
        recorder.isLoggable(Logging.Priority.DEBUG) shouldBe true
        recorder.isLoggable(Logging.Priority.ERROR) shouldBe true
    }

    @Test
    fun `setMinPriority refreshes the logging mask while installed`() {
        val recorder = create()
        try {
            recorder.acquire()
            Logging.isLoggable(Logging.Priority.VERBOSE).shouldBeFalse()

            recorder.setMinPriority(Logging.Priority.VERBOSE)
            Logging.isLoggable(Logging.Priority.VERBOSE).shouldBeTrue()

            recorder.setMinPriority(Logging.Priority.WARN)
            Logging.isLoggable(Logging.Priority.DEBUG).shouldBeFalse()
            Logging.isLoggable(Logging.Priority.WARN).shouldBeTrue()
        } finally {
            recorder.release()
        }
    }

    @Test
    fun `read counts every physical line ever recorded`() {
        val recorder = create()
        recorder.emit("a\nb")
        recorder.emit("c")
        recorder.read().totalLines shouldBe 3L

        recorder.clear()
        recorder.read().lines shouldHaveSize 0
        recorder.read().totalLines shouldBe 3L

        recorder.emit("d")
        recorder.read().totalLines shouldBe 4L
    }

    @Test
    fun `clear empties the buffer but keeps ids monotonic`() {
        val recorder = create()
        recorder.emit("a")
        recorder.emit("b")
        recorder.snapshot() shouldHaveSize 2

        recorder.clear()
        recorder.snapshot() shouldHaveSize 0

        recorder.emit("c")
        val snapshot = recorder.snapshot()
        snapshot shouldHaveSize 1
        snapshot.single().message shouldBe "c"
        // ids keep advancing across the clear: a=0, b=1, c=2
        snapshot.single().id shouldBe 2L
    }

    @Test
    fun `concurrent logging keeps ids unique and monotonic`() {
        val recorder = create()
        val threads = 8
        val perThread = 1000
        val latch = CountDownLatch(1)

        val workers = (0 until threads).map {
            thread(start = false) {
                latch.await()
                repeat(perThread) { recorder.emit("msg") }
            }
        }
        workers.forEach { it.start() }
        latch.countDown()
        workers.forEach { it.join() }

        val ids = recorder.snapshot().map { it.id }
        ids shouldHaveSize LogHistoryRecorder.BUFFER_CAP
        // Ids are assigned under the lock, so insertion order is strictly increasing & unique.
        ids shouldBe ids.sorted()
        ids.toSet() shouldHaveSize ids.size
        ids.last() shouldBe (threads.toLong() * perThread - 1)
    }

    @Test
    fun `acquire installs and release removes, ref-counted`() {
        val recorder = create()
        try {
            recorder.acquire()
            Logging.loggers.contains(recorder) shouldBe true

            // Second owner keeps it installed after a single release.
            recorder.acquire()
            recorder.release()
            Logging.loggers.contains(recorder) shouldBe true
        } finally {
            recorder.release()
        }
        Logging.loggers.contains(recorder) shouldBe false

        // An extra release is a no-op and never drives the count negative.
        recorder.release()
        Logging.loggers.contains(recorder) shouldBe false
    }

    @Test
    fun `concurrent acquire-release pairs never leave a zero-owner logger installed`() {
        val recorder = create()
        val threads = 8
        val iterations = 250

        fun raceOwners() {
            val latch = CountDownLatch(1)
            val workers = (0 until threads).map {
                thread(start = false) {
                    latch.await()
                    repeat(iterations) {
                        recorder.acquire()
                        recorder.release()
                    }
                }
            }
            workers.forEach { it.start() }
            latch.countDown()
            workers.forEach { it.join() }
        }

        raceOwners()
        Logging.loggers.contains(recorder) shouldBe false

        // A persistent owner survives racing acquire/release pairs from other owners.
        recorder.acquire()
        raceOwners()
        Logging.loggers.contains(recorder) shouldBe true

        recorder.release()
        Logging.loggers.contains(recorder) shouldBe false
    }
}
