package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay

@Composable
fun OperationDialogHost(
    dialogState: OperationDialogState,
    operations: List<OperationDisplay>,
    onDismissDialog: () -> Unit,
    onCancelOperation: ((Operation.Id) -> Unit)? = null,
    onShareError: ((Operation.Id) -> Unit)? = null,
    onHandleIssue: ((Operation.Id) -> Unit)? = null,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
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
                    topInset = topInset,
                    bottomInset = bottomInset,
                    onCancel = if (currentOperation.canCancel && currentOperation.state is OperationDisplay.State.Running) {
                        {
                            onCancelOperation?.invoke(currentOperation.id)
                        }
                    } else null,
                    onShareError = if (currentOperation.state is OperationDisplay.State.Failed) {
                        {
                            onShareError?.invoke(currentOperation.id)
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