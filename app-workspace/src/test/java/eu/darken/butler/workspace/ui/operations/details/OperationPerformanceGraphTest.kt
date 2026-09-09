package eu.darken.butler.workspace.ui.operations.details

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import eu.darken.butler.common.formatByteSpeed
import eu.darken.butler.workspace.R
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class OperationPerformanceGraphTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val graphTag = "test-performance-graph"

    private val startTime = Instant.fromEpochMilliseconds(1000)

    private val bytesPerSecond = 50_000_000L

    private fun history(withBytes: Boolean) = PerformanceHistory(
        samples = (0 until 20).map { i ->
            PerformanceSample(
                timestamp = startTime + 1.seconds * i,
                bytesPerSecond = if (withBytes) bytesPerSecond else 0L,
                itemsPerSecond = 5f,
                totalBytesProcessed = if (withBytes) i * bytesPerSecond else 0L,
                totalItemsProcessed = i,
            )
        },
        startTime = startTime,
    )

    private fun graphData(withBytes: Boolean, scope: PerformanceGraphScope = PerformanceGraphScope.OVERVIEW) =
        PerformanceGraphData.from(history(withBytes), scope, startTime + 30.seconds)!!

    @Test
    fun `byte data appearing mid operation keeps the graph composed`() {
        val itemsOnly = graphData(withBytes = false)
        itemsOnly.byteSpeeds shouldBe null
        val withBytes = graphData(withBytes = true)
        withBytes.byteSpeeds.shouldNotBeNull()

        val graphData = mutableStateOf(itemsOnly)

        composeTestRule.setContent {
            PreviewWrapper {
                OperationPerformanceGraph(
                    modifier = Modifier.testTag(graphTag),
                    graphData = graphData.value,
                )
            }
        }

        composeTestRule.onNodeWithTag(graphTag).assertIsDisplayed()

        graphData.value = withBytes
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(graphTag).assertIsDisplayed()
        composeTestRule.onNodeWithText(formatByteSpeed(context, bytesPerSecond)).assertIsDisplayed()
    }

    @Test
    fun `a live window is labelled with the span it covers`() {
        val live = graphData(withBytes = true, scope = PerformanceGraphScope.LIVE)
        live.windowSpanSeconds shouldBe 30

        composeTestRule.setContent {
            PreviewWrapper {
                OperationPerformanceGraph(modifier = Modifier.testTag(graphTag), graphData = live)
            }
        }

        val label = context.getString(R.string.workspace_operation_performance_window_seconds, 30)
        composeTestRule.onNodeWithText(label).assertIsDisplayed()
    }

    @Test
    fun `a completed overview carries no window label`() {
        val overview = graphData(withBytes = true)
        overview.windowSpanSeconds shouldBe null

        composeTestRule.setContent {
            PreviewWrapper {
                OperationPerformanceGraph(modifier = Modifier.testTag(graphTag), graphData = overview)
            }
        }

        val label = context.getString(R.string.workspace_operation_performance_window_seconds, 30)
        composeTestRule.onNodeWithText(label).assertDoesNotExist()
    }
}
