package eu.darken.butler.developer.core

import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logviewer.core.LogHistoryRecorder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class DeveloperLogRepoTest : BaseTest() {

    private lateinit var recorder: LogHistoryRecorder
    private lateinit var repo: DeveloperLogRepo

    @BeforeEach
    fun setup() {
        Logging.clearAll()
        recorder = LogHistoryRecorder()
        repo = DeveloperLogRepo(recorder)
    }

    @AfterEach
    fun tearDown() = Logging.clearAll()

    private fun emit(message: String, priority: Logging.Priority = Logging.Priority.DEBUG) =
        recorder.log(priority, "TestTag", message, null)

    @Test
    fun `logLines emits current content immediately in tab format`() = runTest {
        emit("hello")
        emit("boom", Logging.Priority.ERROR)

        repo.logLines.first() shouldBe listOf("D/TestTag: hello", "E/TestTag: boom")
    }

    @Test
    fun `logLines is capped to the newest 500 lines`() = runTest {
        repeat(600) { emit("line $it") }

        val lines = repo.logLines.first()
        lines shouldHaveSize 500
        lines.first() shouldBe "D/TestTag: line 100"
        lines.last() shouldBe "D/TestTag: line 599"
    }

    @Test
    fun `currentLogLines matches the flow rendering`() = runTest {
        emit("a")
        emit("b")

        repo.currentLogLines shouldBe repo.logLines.first()
    }

    @Test
    fun `clear empties the shared buffer`() = runTest {
        emit("a")
        repo.currentLogLines shouldHaveSize 1

        repo.clear()

        repo.currentLogLines shouldHaveSize 0
        recorder.snapshot() shouldHaveSize 0
    }

    @Test
    fun `install and uninstall delegate to the recorder ref-count`() = runTest {
        Logging.loggers.contains(recorder) shouldBe false

        repo.install()
        Logging.loggers.contains(recorder) shouldBe true

        // A second owner (e.g. the floating panel) keeps it installed after one uninstall.
        recorder.acquire()
        repo.uninstall()
        Logging.loggers.contains(recorder) shouldBe true

        recorder.release()
        Logging.loggers.contains(recorder) shouldBe false
    }
}
