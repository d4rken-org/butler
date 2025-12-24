package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.runtime.Composable
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay

@Composable
fun OperationDialogHost(
    dialogState: OperationDialogState,
    operations: List<OperationDisplay>,
    onDismissDialog: () -> Unit,
    onCancelOperation: ((Operation.Id) -> Unit)? = null,
    onCopyError: ((Operation.Id) -> Unit)? = null,
    onHandleIssue: ((Operation.Id) -> Unit)? = null,
) {
    when (dialogState) {
        is OperationDialogState.None -> {
            // No dialog to show
        }

        is OperationDialogState.OperationDetails -> {
            // Find the current operation from the operations list
            val currentOperation = operations.find { it.id == dialogState.operationId }

            if (currentOperation != null) {
                OperationDetailsSheet(
                    operation = currentOperation,
                    onDismiss = onDismissDialog,
                    onCancel = if (currentOperation.canCancel && currentOperation.state is OperationDisplay.State.Running) {
                        {
                            onCancelOperation?.invoke(currentOperation.id)
                        }
                    } else null,
                    onCopyError = if (currentOperation.state is OperationDisplay.State.Failed) {
                        {
                            onCopyError?.invoke(currentOperation.id)
                            onDismissDialog()
                        }
                    } else null,
                    onHandleIssue = if (currentOperation.state is OperationDisplay.State.Waiting) {
                        {
                            onHandleIssue?.invoke(currentOperation.id)
                            onDismissDialog()
                        }
                    } else null,
                )
            } else {
                // Operation not found, dismiss the dialog
                onDismissDialog()
            }
        }
    }
}