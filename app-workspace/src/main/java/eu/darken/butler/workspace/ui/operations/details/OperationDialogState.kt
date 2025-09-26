package eu.darken.butler.workspace.ui.operations.details

import eu.darken.butler.workspace.ui.operations.OperationDisplay

sealed interface OperationDialogState {
    data object None : OperationDialogState

    data class OperationDetails(val operation: OperationDisplay) : OperationDialogState
}