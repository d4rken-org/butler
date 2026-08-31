package eu.darken.butler.common.debug.bugreport

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.nio.file.Files

class BugReportStorageTest : BaseTest() {

    @Test
    fun `payloadFiles returns the known report files in a fixed order`(@TempDir tempDir: File) {
        val reportDir = File(tempDir, "report").apply { mkdirs() }
        listOf("adb.log", "report.log", "meta.json", "root.log").forEach {
            File(reportDir, it).writeText(it)
        }

        BugReportStorage.payloadFiles(reportDir) shouldBe listOf(
            File(reportDir, "meta.json"),
            File(reportDir, "report.log"),
            File(reportDir, "root.log"),
            File(reportDir, "adb.log"),
        )
    }

    @Test
    fun `payloadFiles skips markers and anything not on the allowlist`(@TempDir tempDir: File) {
        val reportDir = File(tempDir, "report").apply { mkdirs() }
        File(reportDir, "meta.json").writeText("{}")
        File(reportDir, "report.log").writeText("log")
        File(reportDir, ".recording").createNewFile()
        File(reportDir, ".seen").createNewFile()
        File(reportDir, "injected.txt").writeText("not mine")

        BugReportStorage.payloadFiles(reportDir) shouldBe listOf(
            File(reportDir, "meta.json"),
            File(reportDir, "report.log"),
        )
    }

    @Test
    fun `payloadFiles skips an allowlisted name that is a symlink`(@TempDir tempDir: File) {
        val reportDir = File(tempDir, "report").apply { mkdirs() }
        File(reportDir, "meta.json").writeText("{}")
        val elsewhere = File(tempDir, "secret.txt").apply { writeText("secret") }
        // The helper processes write into the report directory as uid 0 / uid 2000, so a report file
        // may be a symlink someone else planted rather than a log we produced.
        Files.createSymbolicLink(File(reportDir, "root.log").toPath(), elsewhere.toPath())

        BugReportStorage.payloadFiles(reportDir) shouldBe listOf(File(reportDir, "meta.json"))
    }

    @Test
    fun `payloadFiles is empty for an empty directory`(@TempDir tempDir: File) {
        BugReportStorage.payloadFiles(tempDir) shouldBe emptyList()
    }
}
