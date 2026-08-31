package eu.darken.butler.common.debug

import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.io.File

/**
 * Drives the path state machine through `null → A → B → null` plus a failed start, the sequence a
 * privileged host sees when it outlives more than one recording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HostRecorderLogTest : BaseTest() {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun hostLog(dir: File) = File(dir, LOG_NAME).readText()

    @Test
    fun `the log follows the published path and every switch closes the previous one`() {
        val dirA = tempFolder.newFolder("report_a")
        val dirB = tempFolder.newFolder("report_b")
        val loggersBefore = Logging.loggers
        val recorderLog = HostRecorderLog(LOG_NAME, TAG)

        try {
            recorderLog.update(null)
            File(dirA, LOG_NAME).exists() shouldBe false

            recorderLog.update(dirA.path)
            log(TAG, INFO) { "line-a" }
            hostLog(dirA) shouldContain "line-a"

            // Straight from one report to the next, without passing through null.
            recorderLog.update(dirB.path)
            log(TAG, INFO) { "line-b" }

            // The END marker is only written by stop(), so it proves the old logger was closed and
            // not merely detached from the bus.
            hostLog(dirA) shouldContain "=== END ==="
            hostLog(dirA) shouldNotContain "line-b"
            hostLog(dirB) shouldContain "line-b"

            recorderLog.update(null)
            log(TAG, INFO) { "line-c" }

            hostLog(dirB) shouldContain "=== END ==="
            hostLog(dirB) shouldNotContain "line-c"
            Logging.loggers shouldBe loggersBefore
        } finally {
            recorderLog.update(null)
        }
    }

    @Test
    fun `a logger that cannot start is not installed and does not wedge the next report`() {
        val dirA = tempFolder.newFolder("report_a")
        // A directory where the log file belongs: the writer cannot be opened on it.
        File(dirA, LOG_NAME).mkdirs()
        val dirB = tempFolder.newFolder("report_b")
        val loggersBefore = Logging.loggers
        val recorderLog = HostRecorderLog(LOG_NAME, TAG)

        try {
            recorderLog.update(dirA.path)

            Logging.loggers shouldBe loggersBefore

            recorderLog.update(dirB.path)
            log(TAG, INFO) { "line-b" }

            hostLog(dirB) shouldContain "line-b"
        } finally {
            recorderLog.update(null)
        }

        Logging.loggers shouldBe loggersBefore
    }

    companion object {
        private const val LOG_NAME = "root.log"
        private val TAG = logTag("Test", "HostRecorderLog")
    }
}
