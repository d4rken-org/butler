package eu.darken.butler.workspace.ui.operations.details

import eu.darken.butler.common.compose.InfoEntry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class OperationOverviewEntriesTest : BaseTest() {

    private fun entries(
        resultValue: String? = "Moved 12 files",
        destinationLabel: String? = null,
        destinationValue: String? = null,
    ) = buildOverviewEntries(
        statusLabel = "Status",
        statusValue = "Successful",
        timeLabel = "Completed",
        timeValue = "2 minutes ago",
        durationLabel = "Duration",
        durationValue = "12s",
        destinationLabel = destinationLabel,
        destinationValue = destinationValue,
        resultLabel = "Result",
        resultValue = resultValue,
    )

    private fun withFolder() = entries(
        destinationLabel = "Destination folder",
        destinationValue = "/sdcard/Backup",
    )

    private fun withPath() = entries(
        destinationLabel = "Destination path",
        destinationValue = "/sdcard/Backup/renamed.txt",
    )

    @Test
    fun `the result value is never truncated and never shares a row`() {
        val result = entries().last()

        result.label shouldBe "Result"
        result.pairable shouldBe false
        result.valueMaxLines shouldBe Int.MAX_VALUE
    }

    @Test
    fun `the short fields pair up and keep the default cap`() {
        entries().take(3).forEach {
            it.pairable shouldBe true
            it.valueMaxLines shouldBe 2
        }
    }

    @Test
    fun `an operation without a report has no result entry`() {
        entries(resultValue = null).map { it.label } shouldBe listOf("Status", "Completed", "Duration")
    }

    @Test
    fun `entries are ordered status, time, duration, result`() {
        entries().map { it.label } shouldBe listOf("Status", "Completed", "Duration", "Result")
    }

    @Test
    fun `an operation without a destination has no path-styled entry`() {
        entries().none { it.valueStyle == InfoEntry.ValueStyle.PATH } shouldBe true
    }

    @Test
    fun `the destination sits between duration and result`() {
        withFolder().map { it.label } shouldBe
            listOf("Status", "Completed", "Duration", "Destination folder", "Result")
    }

    @Test
    fun `a folder destination renders as a full-width path`() {
        val destination = withFolder()[3]

        destination.value shouldBe "/sdcard/Backup"
        destination.pairable shouldBe false
        destination.valueStyle shouldBe InfoEntry.ValueStyle.PATH
    }

    @Test
    fun `a requested-target destination renders as a full-width path`() {
        val destination = withPath()[3]

        destination.label shouldBe "Destination path"
        destination.value shouldBe "/sdcard/Backup/renamed.txt"
        destination.pairable shouldBe false
        destination.valueStyle shouldBe InfoEntry.ValueStyle.PATH
    }

    @Test
    fun `a destination survives an operation that has no report`() {
        entries(
            resultValue = null,
            destinationLabel = "Destination folder",
            destinationValue = "/sdcard/Backup",
        ).map { it.label } shouldBe listOf("Status", "Completed", "Duration", "Destination folder")
    }
}
