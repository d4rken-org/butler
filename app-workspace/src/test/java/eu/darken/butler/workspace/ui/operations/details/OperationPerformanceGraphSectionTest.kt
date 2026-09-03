package eu.darken.butler.workspace.ui.operations.details

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class OperationPerformanceGraphSectionTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val title = context.getString(R.string.workspace_operations_performance_graph_label)
    private val expandLabel = context.getString(R.string.operations_details_section_expand, title)
    private val collapseLabel = context.getString(R.string.operations_details_section_collapse, title)
    private val graphTag = "test-graph"

    private val notStartedText = context.getString(R.string.workspace_operation_performance_unavailable_not_started)
    private val collectingText = context.getString(R.string.workspace_operation_performance_unavailable_collecting)
    private val insufficientText = context.getString(R.string.workspace_operation_performance_unavailable_insufficient)
    private val notAvailableText = context.getString(R.string.workspace_operation_performance_unavailable_not_available)

    private val startTime = Instant.fromEpochMilliseconds(1000)

    private fun history(
        sampleCount: Int = 20,
        totalBytes: Long = 1_000_000_000L,
        totalItems: Int = 20,
        spacing: Duration = 250.milliseconds,
        advancing: Boolean = true,
    ) = PerformanceHistory(
        samples = (0 until sampleCount).map { i ->
            PerformanceSample(
                timestamp = startTime + spacing * i,
                bytesPerSecond = 50_000_000L,
                itemsPerSecond = 5f,
                totalBytesProcessed = if (advancing) i * 50_000_000L else 0L,
                totalItemsProcessed = if (advancing) i else 0,
            )
        },
        startTime = startTime,
        totalBytes = totalBytes,
        totalItems = totalItems,
    )

    private fun operation(state: OperationDisplay.State) = OperationDisplay(
        id = Operation.Id(),
        startedAt = Clock.System.now(),
        icon = Icons.TwoTone.ContentCopy,
        title = "Copying files".toCaString(),
        description = "3 of 10".toCaString(),
        state = state,
    )

    private fun running(history: PerformanceHistory?) = OperationDisplay.State.Running(
        performanceHistory = history,
    )

    private fun completed(history: PerformanceHistory?) = OperationDisplay.State.Completed(
        summary = "Copied 10 files".toCaString(),
        completedAt = startTime,
        report = null,
        performanceHistory = history,
    )

    private fun waiting() = OperationDisplay.State.Waiting(reason = "Waiting for space".toCaString())

    private fun failed() = OperationDisplay.State.Failed(
        summary = "Copy failed".toCaString(),
        completedAt = startTime,
        report = null,
    )

    private fun cancelled() = OperationDisplay.State.Cancelled(completedAt = startTime, report = null)

    private fun setSection(state: OperationDisplay.State) {
        composeTestRule.setContent {
            PreviewWrapper {
                OperationPerformanceGraphSection(
                    operation = operation(state),
                    graphContent = { Text(text = "graph", modifier = Modifier.testTag(graphTag)) },
                )
            }
        }
    }

    @Test
    fun `toggling the section flips the expand affordance`() {
        setSection(running(history()))

        composeTestRule.onNodeWithContentDescription(expandLabel).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithContentDescription(collapseLabel).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(collapseLabel).performClick()
        composeTestRule.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
    }

    @Test
    fun `graph is only composed while expanded`() {
        setSection(running(history()))

        composeTestRule.onNodeWithTag(graphTag).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithTag(graphTag).assertIsDisplayed()
    }

    @Test
    fun `real graph renders for a byte and item history`() {
        composeTestRule.setContent {
            PreviewWrapper {
                OperationPerformanceGraphSection(operation = operation(running(history())))
            }
        }

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithContentDescription(collapseLabel).assertIsDisplayed()
    }

    @Test
    fun `a plottable history shows the graph without an explanation`() {
        setSection(running(history()))

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()

        composeTestRule.onNodeWithTag(graphTag).assertIsDisplayed()
        composeTestRule.onNodeWithText(notStartedText).assertDoesNotExist()
        composeTestRule.onNodeWithText(collectingText).assertDoesNotExist()
        composeTestRule.onNodeWithText(insufficientText).assertDoesNotExist()
        composeTestRule.onNodeWithText(notAvailableText).assertDoesNotExist()
    }

    @Test
    fun `queued operations get the section`() {
        setSection(OperationDisplay.State.Queued)

        composeTestRule.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
    }

    @Test
    fun `waiting operations get the section`() {
        setSection(waiting())

        composeTestRule.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
    }

    @Test
    fun `failed operations get the section`() {
        setSection(failed())

        composeTestRule.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
    }

    @Test
    fun `cancelled operations get the section`() {
        setSection(cancelled())

        composeTestRule.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
    }

    @Test
    fun `running operations without a history get the section`() {
        setSection(running(null))

        composeTestRule.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
    }

    @Test
    fun `completed operations without a history get the section`() {
        setSection(completed(null))

        composeTestRule.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
    }

    @Test
    fun `a queued operation has not started yet`() {
        setSection(OperationDisplay.State.Queued)

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithText(notStartedText).assertIsDisplayed()
    }

    @Test
    fun `a running operation without a history is still collecting`() {
        setSection(running(null))

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithText(collectingText).assertIsDisplayed()
    }

    @Test
    fun `a running operation with too few samples is still collecting`() {
        setSection(running(history(sampleCount = 5)))

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithText(collectingText).assertIsDisplayed()
    }

    @Test
    fun `a running operation stuck on one progress step is still collecting`() {
        setSection(running(history(totalItems = 0, advancing = false)))

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithText(collectingText).assertIsDisplayed()
    }

    @Test
    fun `a completed operation with too few samples has insufficient data`() {
        setSection(completed(history(sampleCount = 5)))

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithText(insufficientText).assertIsDisplayed()
    }

    @Test
    fun `a completed operation without totals has insufficient data`() {
        setSection(completed(history(totalBytes = 0L, totalItems = 0)))

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithText(insufficientText).assertIsDisplayed()
    }

    @Test
    fun `a long completed operation with few samples has insufficient data`() {
        setSection(completed(history(sampleCount = 5, spacing = 2.minutes)))

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithText(insufficientText).assertIsDisplayed()
    }

    @Test
    fun `a completed operation without a history has no data available`() {
        setSection(completed(null))

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithText(notAvailableText).assertIsDisplayed()
    }

    @Test
    fun `a waiting operation has no data available`() {
        setSection(waiting())

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithText(notAvailableText).assertIsDisplayed()
    }

    @Test
    fun `a failed operation has no data available`() {
        setSection(failed())

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithText(notAvailableText).assertIsDisplayed()
    }

    @Test
    fun `a cancelled operation has no data available`() {
        setSection(cancelled())

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithText(notAvailableText).assertIsDisplayed()
    }
}
