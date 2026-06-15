package eu.darken.butler.common.debug.logging

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class RingLogBufferTest {

    private fun create() = RingLogBuffer()

    @Test
    fun `default threshold is INFO`() {
        val buffer = create()
        buffer.isLoggable(Logging.Priority.VERBOSE) shouldBe false
        buffer.isLoggable(Logging.Priority.DEBUG) shouldBe false
        buffer.isLoggable(Logging.Priority.INFO) shouldBe true
        buffer.isLoggable(Logging.Priority.WARN) shouldBe true
        buffer.isLoggable(Logging.Priority.ERROR) shouldBe true
    }

    @Test
    fun `threshold can be lowered to DEBUG`() {
        val buffer = create()
        buffer.setThreshold(Logging.Priority.DEBUG)
        buffer.isLoggable(Logging.Priority.DEBUG) shouldBe true
        buffer.isLoggable(Logging.Priority.VERBOSE) shouldBe false
    }

    @Test
    fun `snapshot preserves order, oldest first`() {
        val buffer = create()
        buffer.log(Logging.Priority.INFO, "Tag", "first", null)
        buffer.log(Logging.Priority.INFO, "Tag", "second", null)
        buffer.log(Logging.Priority.INFO, "Tag", "third", null)

        val lines = buffer.snapshot().lines()
        lines.size shouldBe 3
        lines[0] shouldContain "first"
        lines[1] shouldContain "second"
        lines[2] shouldContain "third"
    }

    @Test
    fun `oversized entries are truncated`() {
        val buffer = create()
        buffer.log(Logging.Priority.INFO, "Tag", "x".repeat(10_000), null)

        val snapshot = buffer.snapshot()
        snapshot shouldContain "…[truncated]"
        (snapshot.length < 5_000) shouldBe true
    }

    @Test
    fun `oldest entries are evicted once the byte budget is exceeded`() {
        val buffer = create()
        buffer.log(Logging.Priority.INFO, "Tag", "MARKER_FIRST", null)
        // ~600 KB total, above the 512 KB budget, so the first line must be evicted.
        repeat(300) { buffer.log(Logging.Priority.INFO, "Tag", "y".repeat(2_000), null) }

        val snapshot = buffer.snapshot()
        snapshot shouldNotContain "MARKER_FIRST"
    }

    @Test
    fun `clear empties the buffer`() {
        val buffer = create()
        buffer.log(Logging.Priority.INFO, "Tag", "something", null)
        buffer.clear()
        buffer.snapshot() shouldBe ""
    }
}
