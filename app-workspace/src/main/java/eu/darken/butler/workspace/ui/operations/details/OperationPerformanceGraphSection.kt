package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.compose.ProGate
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.ui.operations.OperationDisplay

@Composable
internal fun OperationPerformanceGraphSection(
    operation: OperationDisplay,
    graphContent: @Composable (PerformanceHistory) -> Unit = { OperationPerformanceGraph(performanceHistory = it) },
) {
    // Extract performance history from the operation state
    val performanceHistory = when (val state = operation.state) {
        is OperationDisplay.State.Running -> state.performanceHistory
        is OperationDisplay.State.Completed -> state.performanceHistory
        else -> null
    }

    // Return early if no data or insufficient samples
    if (performanceHistory?.canShowGraph != true) return

    OperationSection(
        title = stringResource(R.string.workspace_operations_performance_graph_label),
        initiallyExpanded = false,
    ) {
        ProGate {
            graphContent(performanceHistory)
        }
    }
}
