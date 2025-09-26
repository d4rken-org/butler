package eu.darken.butler.workspace.ui.operations.details

import eu.darken.butler.workspace.core.operations.Operation

sealed interface OperationDialogState {
    data object None : OperationDialogState

    data class OperationDetails(val operationId: Operation.Id) : OperationDialogState
}