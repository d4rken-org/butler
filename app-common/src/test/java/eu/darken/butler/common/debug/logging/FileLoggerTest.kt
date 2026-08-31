package eu.darken.butler.common.debug.logging

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FileLoggerTest : BaseTest() {

    @Test
    fun `a failed start preserves a pre-existing log file`() {
        // The resume path reattaches to a log that already holds the pre-death recording; a failed
        // reattach must not destroy it.
        val dir = Files.createTempDirectory("filelogger-test").toFile()
        val logFile = File(dir, "report.log").apply { writeText("precious pre-death log") }
        logFile.setWritable(false)
        try {
            val logger = FileLogger(logFile, worldReadable = false)

            logger.start() shouldBe false

            logFile.exists() shouldBe true
            logFile.readText() shouldBe "precious pre-death log"
        } finally {
            logFile.setWritable(true)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `start appends to an existing log file`() {
        val dir = Files.createTempDirectory("filelogger-test").toFile()
        val logFile = File(dir, "report.log").apply { writeText("existing line\n") }
        try {
            val logger = FileLogger(logFile, worldReadable = false)

            logger.start() shouldBe true
            logger.stop()

            val content = logFile.readText()
            content.startsWith("existing line\n") shouldBe true
            content.contains("=== BEGIN") shouldBe true
            content.trimEnd().endsWith(FileLogger.END_MARKER) shouldBe true
        } finally {
            dir.deleteRecursively()
        }
    }
}
