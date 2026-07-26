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
 * @param operations `null` while the host's operations flow has not emitted yet, which is a
 *        different thing from an empty list and must stay distinguishable here. The pending id
 *        comes from durable ViewModel state and is already set on the first frame this slot is
 *        composed; judging it against a list that has not arrived would retire the request before
 *        the dialog was ever on screen. Once the list *has* arrived, an id that is absent, finished
 *        or not cancelable is retired at once — whether it was ever observed running is irrelevant,
 *        because a cancel requested just as the operation completed never is.
 */
@Composable
fun CancelOperationConfirmationHost(
    pendingId: Operation.Id?,
    operations: List<OperationDisplay>?,
    onDismiss: () -> Unit,
    onConfirm: (Operation.Id) -> Unit,
) {
    val pending = pendingId ?: return
    val loaded = operations != null
    val cancelable = operations?.any { it.id == pending && it.canCancel && !it.state.isFinished } == true
    val gone = loaded && !cancelable

    LaunchedEffect(pending, gone) {
        if (gone) onDismiss()
    }

    if (gone) return

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
