package eu.darken.butler.common.flow

import eu.darken.butler.common.debug.logging.Logging
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CommonEventHandlersTest {

    private class CapturingLogger : Logging.Logger {
        val lines = mutableListOf<String>()
        override fun log(priority: Logging.Priority, tag: String, message: String, metaData: Map<String, Any>?) {
            lines += message
        }
    }

    private data class Listing(val path: String, val items: List<String>)

    private lateinit var logger: CapturingLogger

    @BeforeEach
    fun setup() {
        Logging.clearAll()
        logger = CapturingLogger()
        Logging.install(logger)
        // install() logs its own line through the logger it just installed.
        logger.lines.clear()
    }

    @AfterEach
    fun tearDown() = Logging.clearAll()

    private enum class Mode { ACTIVE }

    @Test
    fun `booleans are logged by value`() = runTest {
        flowOf(true).setupCommonEventHandlers("test") { "flow" }.launchIn(this)
        runCurrent()
        logger.lines.single { it.contains(".onEach()") } shouldContain "onEach(): true"
    }

    @Test
    fun `enums log their name`() = runTest {
        flowOf(Mode.ACTIVE).setupCommonEventHandlers("test") { "flow" }.launchIn(this)
        runCurrent()
        logger.lines.single { it.contains(".onEach()") } shouldContain "onEach(): ACTIVE"
    }

    /** A bare number could be a count or an account id; the generic helper cannot tell them apart. */
    @Test
    fun `numbers are reduced to their type`() = runTest {
        flowOf(4815162342L).setupCommonEventHandlers("test") { "flow" }.launchIn(this)
        runCurrent()

        val line = logger.lines.single { it.contains(".onEach()") }
        line shouldContain "onEach(): Long"
        line shouldNotContain "4815162342"
    }

    /**
     * The emission reaches logcat and the bug-report file a user attaches to an issue, so a payload
     * that carries their paths and filenames must not survive interpolation.
     */
    @Test
    fun `object emissions are reduced to their type`() = runTest {
        val listing = Listing("/storage/emulated/0/Taxes", listOf("2025-return.pdf"))
        flowOf(listing).setupCommonEventHandlers("test") { "flow" }.launchIn(this)
        runCurrent()

        val line = logger.lines.single { it.contains(".onEach()") }
        line shouldContain "onEach(): Listing"
        line shouldNotContain "Taxes"
        line shouldNotContain "2025-return.pdf"
    }

    @Test
    fun `collections report size instead of contents`() = runTest {
        flowOf(listOf("alpha", "beta")).setupCommonEventHandlers("test") { "flow" }.launchIn(this)
        runCurrent()

        val line = logger.lines.single { it.contains(".onEach()") }
        line shouldContain "(2)"
        line shouldNotContain "alpha"
    }

    /**
     * Guards the regression this parameter already suffered once: it was declared, three call sites
     * passed it, and the body never read it.
     */
    @Test
    fun `enabled false suppresses emission logging`() = runTest {
        flowOf(1, 2).setupCommonEventHandlers("test", enabled = { false }) { "flow" }.launchIn(this)
        runCurrent()
        logger.lines.shouldContainExactly(emptyList())
    }

    /**
     * Callers gate on debug flags the user toggles long after DI built the flow, so the predicate is
     * read per emission rather than captured once.
     */
    @Test
    fun `enabled is re-read per emission`() = runTest {
        var calls = 0
        flowOf(1, 2, 3)
            .setupCommonEventHandlers("test", enabled = { calls++; true }) { "flow" }
            .launchIn(this)
        runCurrent()

        // onStart + one per emission + onCompletion. A predicate captured at construction would
        // have been read once.
        calls shouldBe 5
    }

    /** A gate that opens mid-stream must change which emissions are logged, not just be re-read. */
    @Test
    fun `a gate that opens mid-stream logs only later emissions`() = runTest {
        var allowed = false
        flowOf(1, 2, 3)
            .setupCommonEventHandlers("test", enabled = { allowed }) { "flow" }
            .onEach { if (it == 1) allowed = true }
            .launchIn(this)
        runCurrent()

        // The gate is still closed when the first emission passes the handler, and open for the
        // other two.
        logger.lines.count { it.contains(".onEach()") } shouldBe 2
    }
}
