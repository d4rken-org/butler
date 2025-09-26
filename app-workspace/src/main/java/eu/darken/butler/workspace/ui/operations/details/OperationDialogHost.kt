package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.runtime.Composable
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.operations.OperationDisplay

@Composable
fun OperationDialogHost(
    dialogState: OperationDialogState,
    onDismissDialog: () -> Unit,
    onCancelOperation: ((Operation.Id) -> Unit)? = null,
    onCopyError: ((Operation.Id) -> Unit)? = null,
) {
    when (dialogState) {
        is OperationDialogState.None -> {
            // No dialog to show
        }

        is OperationDialogState.OperationDetails -> {
            OperationDetailsSheet(
                operation = dialogState.operation,
                onDismiss = onDismissDialog,
                onCancel = if (dialogState.operation.canCancel && dialogState.operation.state is OperationDisplay.State.Running) {
                    {
                        onDismissDialog()
                        onCancelOperation?.invoke(dialogState.operation.id)
                    }
                } else null,
                onCopyError = if (dialogState.operation.state is OperationDisplay.State.Failed) {
                    {
                        onCopyError?.invoke(dialogState.operation.id)
                        onDismissDialog()
                    }
                } else null,
            )
        }
    }
}