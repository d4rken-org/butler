package eu.darken.butler.searcher.ui.search.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.details.ClipboardInfoBottomSheet
import eu.darken.butler.workspace.ui.dialogs.DeleteConfirmationDialog
import eu.darken.butler.workspace.ui.dialogs.FileInfo
import eu.darken.butler.workspace.ui.dialogs.FileInfoBottomSheet
import eu.darken.butler.workspace.ui.dialogs.OpenInNewTabsConfirmationDialog

@Composable
fun SearcherDialogHost(
    modifier: Modifier = Modifier,
    dialogState: SearcherDialogState,
    onDismiss: () -> Unit,
    onDeleteConfirmed: (items: Set<APath<*>>) -> Unit,
    onCopyToClipboard: (String) -> Unit,
    onNavigateToClipboardSource: (ClipboardClip) -> Unit,
    onRemoveClipboardEntry: (ClipboardClip) -> Unit,
    onConfirmOpenInNewTabs: () -> Unit = {},
) {
    when (dialogState) {
        is SearcherDialogState.None -> {
            // No dialog to show
        }
        is SearcherDialogState.DeleteConfirmation -> {
            DeleteConfirmationDialog(
                items = dialogState.paths,
                onDismiss = onDismiss,
                onConfirm = onDeleteConfirmed
            )
        }
        is SearcherDialogState.FileInfo -> {
            FileInfoBottomSheet(
                fileInfo = FileInfo(
                    lookup = dialogState.result.lookup,
                    // Searcher doesn't have extended info (ownership, permissions, etc.)
                ),
                onDismiss = onDismiss,
                onCopyToClipboard = onCopyToClipboard,
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
        is SearcherDialogState.OpenInNewTabsConfirmation -> {
            OpenInNewTabsConfirmationDialog(
                analysis = dialogState.analysis,
                onDismiss = onDismiss,
                onConfirm = onConfirmOpenInNewTabs,
            )
        }
    }
}
