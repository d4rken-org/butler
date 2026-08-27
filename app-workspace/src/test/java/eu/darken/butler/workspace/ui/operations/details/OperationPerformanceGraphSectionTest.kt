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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class OperationPerformanceGraphSectionTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val expandLabel = context.getString(R.string.workspace_operation_performance_graph_expand)
    private val collapseLabel = context.getString(R.string.workspace_operation_performance_graph_collapse)
    private val graphTag = "test-graph"

    private val startTime = Instant.fromEpochMilliseconds(1000)

    private fun history() = PerformanceHistory(
        samples = (0 until 20).map { i ->
            PerformanceSample(
                timestamp = startTime + (i * 250).milliseconds,
                bytesPerSecond = 50_000_000L,
                itemsPerSecond = 5f,
                totalBytesProcessed = i * 50_000_000L,
                totalItemsProcessed = i,
            )
        },
        startTime = startTime,
        totalBytes = 1_000_000_000L,
        totalItems = 20,
    )

    private fun operation(history: PerformanceHistory) = OperationDisplay(
        id = Operation.Id(),
        startedAt = Clock.System.now(),
        icon = Icons.TwoTone.ContentCopy,
        title = "Copying files".toCaString(),
        description = "3 of 10".toCaString(),
        state = OperationDisplay.State.Running(performanceHistory = history),
    )

    @Test
    fun `toggling the section flips the expand affordance`() {
        composeTestRule.setContent {
            PreviewWrapper {
                OperationPerformanceGraphSection(
                    operation = operation(history()),
                    graphContent = { Text(text = "graph", modifier = Modifier.testTag(graphTag)) },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(expandLabel).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithContentDescription(collapseLabel).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(collapseLabel).performClick()
        composeTestRule.onNodeWithContentDescription(expandLabel).assertIsDisplayed()
    }

    @Test
    fun `graph is only composed while expanded`() {
        composeTestRule.setContent {
            PreviewWrapper {
                OperationPerformanceGraphSection(
                    operation = operation(history()),
                    graphContent = { Text(text = "graph", modifier = Modifier.testTag(graphTag)) },
                )
            }
        }

        composeTestRule.onNodeWithTag(graphTag).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithTag(graphTag).assertIsDisplayed()
    }

    @Test
    fun `real graph renders for a byte and item history`() {
        composeTestRule.setContent {
            PreviewWrapper {
                OperationPerformanceGraphSection(operation = operation(history()))
            }
        }

        composeTestRule.onNodeWithContentDescription(expandLabel).performClick()
        composeTestRule.onNodeWithContentDescription(collapseLabel).assertIsDisplayed()
    }
}
