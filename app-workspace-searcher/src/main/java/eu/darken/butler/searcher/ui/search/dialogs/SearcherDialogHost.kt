package eu.darken.butler.searcher.ui.search.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.common.files.APath
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
) {
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
        is SearcherDialogState.ClipboardInfo -> {
            ClipboardInfoBottomSheet(
                clip = dialogState.clip,
                onDismiss = onDismiss,
                onNavigateToSource = { onNavigateToClipboardSource(dialogState.clip) },
                onPaste = null, // Not applicable for searcher
                onRemove = { onRemoveClipboardEntry(dialogState.clip) },
                onCopyPath = onCopyToClipboard,
            )
        }
        is SearcherDialogState.EditSortOptions -> {
            SearchSortOptionsDialog(
                currentSortSettings = dialogState.currentSortSettings,
                onDismiss = onDismiss,
                onConfirm = onSortOptionsConfirmed,
            )
        }
        is SearcherDialogState.EditSizeCondition -> {
            // Handled separately via SizeConditionEditSheet in SearcherWorkspacePage
        }
        is SearcherDialogState.EditDateCondition -> {
            // Handled separately via DateConditionEditSheet in SearcherWorkspacePage
        }
        is SearcherDialogState.EditTypeCondition -> {
            // Handled separately via TypeConditionEditSheet in SearcherWorkspacePage
        }
    }
}
