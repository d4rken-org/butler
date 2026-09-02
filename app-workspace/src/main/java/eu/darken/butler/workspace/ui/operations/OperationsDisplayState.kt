package eu.darken.butler.workspace.ui.operations

import androidx.compose.runtime.Stable
import eu.darken.butler.workspace.core.operations.ManagedOperation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Stable
data class OperationsDisplayState(
    val operations: List<OperationDisplay> = emptyList(),
    /**
     * Whether the Operation History is recording. False hides the paths into it, which would
     * otherwise lead to a tab that can never show the operation.
     */
    val historyEnabled: Boolean = false,
)

internal val operationDisplayComparator: Comparator<OperationDisplay> =
    compareBy<OperationDisplay> { op ->
        when (op.state) {
            is OperationDisplay.State.Running -> 0
            is OperationDisplay.State.Waiting -> 1
            is OperationDisplay.State.Queued -> 2
            is OperationDisplay.State.Failed -> 3
            is OperationDisplay.State.Cancelled -> 4
            is OperationDisplay.State.Completed -> 5
        }
    }.thenByDescending { it.startedAt }

fun Flow<Collection<ManagedOperation>>.toOperationsDisplayState(): Flow<OperationsDisplayState> = this
    .map { managedOps ->
        val ops = managedOps
            .map { it.toDisplayModel() }
            .sortedWith(operationDisplayComparator)
        OperationsDisplayState(operations = ops)
    }
    .onStart { emit(OperationsDisplayState()) }
    .distinctUntilChanged()
