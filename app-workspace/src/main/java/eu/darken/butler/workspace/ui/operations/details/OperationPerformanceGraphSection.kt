package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.ProGate
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.files.local.operations.core.PerformanceSample
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun OperationPerformanceGraphSection(
    operation: OperationDisplay,
    graphContent: @Composable (PerformanceGraphData) -> Unit = { OperationPerformanceGraph(graphData = it) },
) {
    OperationSection(
        title = stringResource(R.string.workspace_operations_performance_graph_label),
        initiallyExpanded = false,
    ) {
        // Inside the section: building the series walks the whole history, and a collapsed section
        // never composes this lambda.
        val graphState = remember(operation.state) { performanceGraphStateOf(operation) }
        when (graphState) {
            is PerformanceGraphState.Plottable -> ProGate { graphContent(graphState.data) }
            is PerformanceGraphState.Unavailable -> PerformanceGraphUnavailableInfo(reason = graphState.reason)
        }
    }
}

internal sealed interface PerformanceGraphState {
    data class Plottable(val data: PerformanceGraphData) : PerformanceGraphState
    data class Unavailable(val reason: PerformanceGraphUnavailability) : PerformanceGraphState
}

internal enum class PerformanceGraphUnavailability {
    NOT_STARTED,
    COLLECTING,
    INSUFFICIENT_DATA,
    NOT_AVAILABLE,
}

/**
 * Whether [operation] has something to plot, and if not, which explanation fits its state.
 *
 * A running operation is always [PerformanceGraphUnavailability.COLLECTING], whatever kept the
 * series empty: no history yet, too few samples, or progress that hasn't moved. All three can still
 * turn into a graph while it runs.
 */
internal fun performanceGraphStateOf(operation: OperationDisplay): PerformanceGraphState {
    val history = when (val state = operation.state) {
        is OperationDisplay.State.Running -> state.performanceHistory
        is OperationDisplay.State.Completed -> state.performanceHistory
        else -> null
    }

    val data = history?.let { PerformanceGraphData.from(it) }
    if (data != null) return PerformanceGraphState.Plottable(data)

    val reason = when (operation.state) {
        is OperationDisplay.State.Queued -> PerformanceGraphUnavailability.NOT_STARTED
        is OperationDisplay.State.Running -> PerformanceGraphUnavailability.COLLECTING
        is OperationDisplay.State.Completed -> when (history) {
            null -> PerformanceGraphUnavailability.NOT_AVAILABLE
            else -> PerformanceGraphUnavailability.INSUFFICIENT_DATA
        }
        else -> PerformanceGraphUnavailability.NOT_AVAILABLE
    }
    return PerformanceGraphState.Unavailable(reason)
}

@Composable
private fun PerformanceGraphUnavailableInfo(
    modifier: Modifier = Modifier,
    reason: PerformanceGraphUnavailability,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 24.dp),
            text = stringResource(reason.labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private val PerformanceGraphUnavailability.labelRes: Int
    get() = when (this) {
        PerformanceGraphUnavailability.NOT_STARTED -> R.string.workspace_operation_performance_unavailable_not_started
        PerformanceGraphUnavailability.COLLECTING -> R.string.workspace_operation_performance_unavailable_collecting
        PerformanceGraphUnavailability.INSUFFICIENT_DATA -> R.string.workspace_operation_performance_unavailable_insufficient
        PerformanceGraphUnavailability.NOT_AVAILABLE -> R.string.workspace_operation_performance_unavailable_not_available
    }

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun OperationPerformanceGraphSectionPreview() {
    val now = remember { Clock.System.now() }
    val history = remember {
        PerformanceHistory(
            samples = (0 until 20).map { i ->
                PerformanceSample(
                    timestamp = now + (i * 250).milliseconds,
                    bytesPerSecond = 50_000_000L,
                    itemsPerSecond = 5f,
                    totalBytesProcessed = i * 50_000_000L,
                    totalItemsProcessed = i,
                )
            },
            startTime = now,
            totalBytes = 1_000_000_000L,
            totalItems = 20,
        )
    }
    OperationPerformanceGraphSection(
        operation = OperationDisplay(
            id = Operation.Id(),
            startedAt = now,
            icon = Icons.TwoTone.ContentCopy,
            title = "Copying files".toCaString(),
            description = "3 of 10".toCaString(),
            state = OperationDisplay.State.Running(performanceHistory = history),
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PerformanceGraphUnavailableInfoNotStartedPreview() {
    PerformanceGraphUnavailableInfo(reason = PerformanceGraphUnavailability.NOT_STARTED)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PerformanceGraphUnavailableInfoCollectingPreview() {
    PerformanceGraphUnavailableInfo(reason = PerformanceGraphUnavailability.COLLECTING)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PerformanceGraphUnavailableInfoInsufficientDataPreview() {
    PerformanceGraphUnavailableInfo(reason = PerformanceGraphUnavailability.INSUFFICIENT_DATA)
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PerformanceGraphUnavailableInfoNotAvailablePreview() {
    PerformanceGraphUnavailableInfo(reason = PerformanceGraphUnavailability.NOT_AVAILABLE)
}
