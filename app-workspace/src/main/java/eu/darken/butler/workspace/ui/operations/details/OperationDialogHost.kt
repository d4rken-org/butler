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
    onShowInHistory: ((Operation.Id) -> Unit)? = null,
    historyEnabled: Boolean = false,
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
                    // Only what the Operation History has actually recorded: an operation without a
                    // kind is never written there, and neither is anything at all while recording
                    // is off, so there'd be nothing for the History tab to open on.
                    onShowInHistory = if (onShowInHistory != null && historyEnabled && currentOperation.isInHistory) {
                        {
                            onShowInHistory.invoke(currentOperation.id)
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

private val OperationDisplay.isInHistory: Boolean
    get() = kind != null && when (state) {
        is OperationDisplay.State.Completed,
        is OperationDisplay.State.Failed,
        is OperationDisplay.State.Cancelled,
            -> true

        else -> false
    }
