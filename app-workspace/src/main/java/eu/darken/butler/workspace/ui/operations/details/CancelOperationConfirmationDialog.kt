package eu.darken.butler.workspace.ui.operations.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.common.R as CommonR

/**
 * Renders the cancel confirmation for [pendingId], if there is one and it can still be cancelled.
 *
 * The confirmation is owned by the page's overlay slot rather than by the operations bar it is
 * triggered from, so it now outlives that bar. An operation that finishes while the confirmation is
 * up would otherwise leave a dialog that cancels nothing, so an operation that stops being
 * cancelable takes its own confirmation down.
 *
 * "Not in [operations]" is deliberately *not* read as "gone". Every host collects its operations
 * with a null initial value and substitutes an empty list, so the list is empty for the first frame
 * after this slot is composed while [pendingId] is already restored from durable ViewModel state.
 * Retiring the request on that frame would throw it away before the dialog was ever on screen.
 */
@Composable
fun CancelOperationConfirmationHost(
    pendingId: Operation.Id?,
    operations: List<OperationDisplay>,
    onDismiss: () -> Unit,
    onConfirm: (Operation.Id) -> Unit,
) {
    val pending = pendingId ?: return
    val cancelable = operations.any { it.id == pending && it.canCancel && !it.state.isFinished }

    // Reset per pending id, so the previous request's history cannot retire the next one.
    var wasCancelable by remember(pending) { mutableStateOf(false) }

    LaunchedEffect(pending, cancelable) {
        when {
            cancelable -> wasCancelable = true
            // Only an operation we actually saw running can have finished or been cleared
            wasCancelable -> onDismiss()
        }
    }

    if (wasCancelable && !cancelable) return

    CancelOperationConfirmationDialog(
        onDismiss = onDismiss,
        onConfirm = { onConfirm(pending) },
    )
}

private val OperationDisplay.State.isFinished: Boolean
    get() = this is OperationDisplay.State.Completed ||
        this is OperationDisplay.State.Failed ||
        this is OperationDisplay.State.Cancelled

@Composable
fun CancelOperationConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    PaneBoundAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.operations_cancel_dialog_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.operations_cancel_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.operations_cancel_dialog_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm
            ) {
                Text(
                    stringResource(R.string.operations_cancel_operation),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CommonR.string.general_cancel_action))
            }
        }
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun CancelOperationConfirmationDialogPreview() {
    CancelOperationConfirmationDialog(
        onDismiss = {},
        onConfirm = {}
    )
}
