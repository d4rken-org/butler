package eu.darken.butler.searcher.ui.search.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.files.APath
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.FilterCondition
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.details.ClipboardInfoBottomSheet
import eu.darken.butler.workspace.ui.dialogs.DeleteConfirmationDialog

@Composable
fun SearcherDialogHost(
    modifier: Modifier = Modifier,
    dialogState: SearcherDialogState,
    trashEnabled: Boolean,
    onDismiss: () -> Unit,
    onDeleteConfirmed: (items: Set<APath<*>>, forcePermDelete: Boolean) -> Unit,
    onCopyToClipboard: (String) -> Unit,
    onNavigateToClipboardSource: (ClipboardClip) -> Unit,
    onRemoveClipboardEntry: (ClipboardClip) -> Unit,
    onSortOptionsConfirmed: (SearchSortOptionsResult) -> Unit = {},
    onClearHistoryConfirmed: () -> Unit = {},
    onConditionApply: (existing: FilterCondition?, new: FilterCondition) -> Unit = { _, _ -> },
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    // AlertDialog-based dialogs (destructive actions - full screen overlay)
    when (dialogState) {
        is SearcherDialogState.None -> {
            // No dialog to show
        }
        is SearcherDialogState.DeleteConfirmation -> {
            DeleteConfirmationDialog(
                items = dialogState.paths,
                trashEnabled = trashEnabled,
                forcePermDelete = dialogState.forcePermDelete,
                onDismiss = onDismiss,
                onConfirm = onDeleteConfirmed,
            )
        }
        is SearcherDialogState.ClearHistoryConfirmation -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(text = stringResource(R.string.searcher_history_clear_dialog_title))
                },
                text = {
                    Text(text = stringResource(R.string.searcher_history_clear_dialog_message))
                },
                confirmButton = {
                    TextButton(onClick = onClearHistoryConfirmed) {
                        Text(
                            text = stringResource(R.string.searcher_history_clear_confirm_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(eu.darken.butler.common.R.string.general_cancel_action))
                    }
                },
            )
        }
        is SearcherDialogState.ClipboardInfo -> {
            ClipboardInfoBottomSheet(
                clip = dialogState.clip,
                onDismiss = onDismiss,
                onNavigateToSource = { onNavigateToClipboardSource(dialogState.clip) },
                onPaste = null, // Not applicable for searcher
                onRemove = { onRemoveClipboardEntry(dialogState.clip) },
                onCopyPath = onCopyToClipboard,
                bottomInset = bottomInset,
            )
        }
        // Non-destructive dialogs handled by pane-scoped bottom sheets below
        is SearcherDialogState.EditSortOptions,
        is SearcherDialogState.EditSizeCondition,
        is SearcherDialogState.EditDateCondition,
        is SearcherDialogState.EditTypeCondition -> {
            // Handled by pane-scoped sheets below
        }
    }

    // Pane-scoped bottom sheets (non-destructive - local to pane)
    // Sort options sheet
    val sortState = dialogState as? SearcherDialogState.EditSortOptions
    SearchSortOptionsSheet(
        visible = sortState != null,
        currentSortSettings = sortState?.currentSortSettings ?: eu.darken.butler.searcher.core.SearchSortSettings(),
        onDismiss = onDismiss,
        onConfirm = onSortOptionsConfirmed,
        topInset = topInset,
        bottomInset = bottomInset,
    )

    // Filter condition sheets
    val sizeState = dialogState as? SearcherDialogState.EditSizeCondition
    SizeConditionEditSheet(
        visible = sizeState != null,
        existingCondition = sizeState?.existing,
        onDismiss = onDismiss,
        onApply = { onConditionApply(sizeState?.existing, it) },
        topInset = topInset,
        bottomInset = bottomInset,
    )

    val dateState = dialogState as? SearcherDialogState.EditDateCondition
    DateConditionEditSheet(
        visible = dateState != null,
        existingCondition = dateState?.existing,
        onDismiss = onDismiss,
        onApply = { onConditionApply(dateState?.existing, it) },
        topInset = topInset,
        bottomInset = bottomInset,
    )

    val typeState = dialogState as? SearcherDialogState.EditTypeCondition
    TypeConditionEditSheet(
        visible = typeState != null,
        existingCondition = typeState?.existing,
        onDismiss = onDismiss,
        onApply = { onConditionApply(typeState?.existing, it) },
        topInset = topInset,
        bottomInset = bottomInset,
    )
}
