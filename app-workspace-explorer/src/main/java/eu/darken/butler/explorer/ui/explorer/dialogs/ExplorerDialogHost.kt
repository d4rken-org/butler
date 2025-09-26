package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.runtime.Composable
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel

@Composable
fun ExplorerDialogHost(
    dialogState: ExplorerDialogState,
    vm: ExplorerWorkspaceViewModel?,
) {
    when (dialogState) {
        is ExplorerDialogState.None -> {
            // No dialog to show
        }
        
        is ExplorerDialogState.CreateItem -> {
            CreateItemDialog(
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { result -> vm?.onCreateItem(result) }
            )
        }
        
        is ExplorerDialogState.DeleteConfirmation -> {
            DeleteConfirmationDialog(
                items = dialogState.items,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { result -> vm?.onDeleteConfirmed(result) }
            )
        }
        
        is ExplorerDialogState.Rename -> {
            RenameDialog(
                item = dialogState.item,
                currentName = dialogState.item.name,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { result -> vm?.onRename(result) }
            )
        }

        is ExplorerDialogState.EditSortOptions -> {
            SortOptionsDialog(
                currentSortSettings = dialogState.currentSortSettings,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { result -> vm?.onSortOptions(result) }
            )
        }

        is ExplorerDialogState.FileOptions -> {
            FileOptionsBottomSheet(
                item = dialogState.item,
                onDismiss = { vm?.dismissDialog() },
                onOpenInEditor = { vm?.openFileInEditor(dialogState.item) },
                onOpenWith = { vm?.openFileWith(dialogState.item) },
                onShare = { vm?.shareFile(dialogState.item) },
                onCopy = { vm?.copyFile(dialogState.item) },
                onCut = { vm?.cutFile(dialogState.item) },
                onRename = { vm?.renameFile(dialogState.item) },
                onDelete = { vm?.deleteFile(dialogState.item) },
                onProperties = { vm?.showFileProperties(dialogState.item) },
            )
        }
    }
}