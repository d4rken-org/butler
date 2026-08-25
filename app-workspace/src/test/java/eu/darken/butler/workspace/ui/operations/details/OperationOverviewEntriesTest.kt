package eu.darken.butler.workspace.ui.operations.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class OperationOverviewEntriesTest : BaseTest() {

    private fun entries(resultValue: String? = "Moved 12 files") = buildOverviewEntries(
        statusLabel = "Status",
        statusValue = "Successful",
        timeLabel = "Completed",
        timeValue = "2 minutes ago",
        durationLabel = "Duration",
        durationValue = "12s",
        resultLabel = "Result",
        resultValue = resultValue,
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
}
