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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class OperationPerformanceGraphTest : ComposeTest() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val graphTag = "test-performance-graph"

    private val startTime = Instant.fromEpochMilliseconds(1000)

    private val bytesPerSecond = 50_000_000L

    private fun history(withBytes: Boolean) = PerformanceHistory(
        samples = (0 until 20).map { i ->
            PerformanceSample(
                timestamp = startTime + 250.milliseconds * i,
                bytesPerSecond = if (withBytes) bytesPerSecond else 0L,
                itemsPerSecond = 5f,
                totalBytesProcessed = if (withBytes) i * bytesPerSecond else 0L,
                totalItemsProcessed = i,
            )
        },
        startTime = startTime,
        totalBytes = if (withBytes) 1_000_000_000L else 0L,
        totalItems = 20,
    )

    private fun graphData(withBytes: Boolean) = PerformanceGraphData.from(history(withBytes))!!

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
}
