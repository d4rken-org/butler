package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.runtime.Composable
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.workspace.ui.clipboard.details.ClipboardInfoBottomSheet
import eu.darken.butler.workspace.ui.dialogs.DeleteConfirmationDialog
import eu.darken.butler.workspace.ui.dialogs.FileInfo
import eu.darken.butler.workspace.ui.dialogs.FileInfoBottomSheet
import eu.darken.butler.workspace.ui.dialogs.MultipleItemsInfoBottomSheet

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
                onValidate = vm?.let { vm::validateFilename } ?: { FilenameValidator.ValidationResult.Valid },
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { result -> vm?.onCreateItem(result) }
            )
        }

        is ExplorerDialogState.DeleteConfirmation -> {
            DeleteConfirmationDialog(
                items = dialogState.items,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { vm?.onDeleteConfirmed(dialogState.items) }
            )
        }

        is ExplorerDialogState.RemoveLocationConfirmation -> {
            RemoveLocationConfirmationDialog(
                items = dialogState.items,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { vm?.onRemoveLocationConfirmed() }
            )
        }

        is ExplorerDialogState.Rename -> {
            RenameDialog(
                item = dialogState.item,
                currentName = dialogState.item.name,
                onValidate = vm?.let { vm::validateFilename } ?: { FilenameValidator.ValidationResult.Valid },
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

        is ExplorerDialogState.FilterOptions -> {
            FilterOptionsDialog(
                includePattern = dialogState.includePattern,
                excludePattern = dialogState.excludePattern,
                fileTypeFilter = dialogState.fileTypeFilter,
                useRegexPatterns = dialogState.useRegexPatterns,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { result -> vm?.onFilterOptions(result) }
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

        is ExplorerDialogState.ClipboardInfo -> {
            ClipboardInfoBottomSheet(
                clip = dialogState.clip,
                onDismiss = { vm?.dismissDialog() },
                onNavigateToSource = { vm?.navigateToClipboardSource(dialogState.clip) },
                onPaste = { vm?.pasteClipboard(dialogState.clip) },
                onRemove = { vm?.removeClipboardEntry(dialogState.clip) },
                onCopyPath = { path -> vm?.copyPathToSystemClipboard(path) }
            )
        }

        is ExplorerDialogState.LocationStorageName -> {
            LocationStorageNameDialog(
                currentName = dialogState.currentName,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { name -> vm?.onLocationStorageName(name) }
            )
        }

        is ExplorerDialogState.ItemInfo -> {
            when (val context = dialogState.context) {
                is ExplorerDialogState.ItemInfo.InfoContext.SingleFile -> {
                    // Use shared component for single files
                    FileInfoBottomSheet(
                        fileInfo = FileInfo(
                            lookup = context.item.lookup,
                            ownership = context.item.ownership,
                            permissions = context.item.permissions,
                            createdAt = context.item.createdAt,
                            mimeInfo = context.item.mimeType,
                        ),
                        onDismiss = { vm?.dismissDialog() },
                        onCopyToClipboard = { text -> vm?.copyPathToSystemClipboard(text) },
                    )
                }
                is ExplorerDialogState.ItemInfo.InfoContext.SingleDirectory -> {
                    // Use shared component for single directories
                    FileInfoBottomSheet(
                        fileInfo = FileInfo(
                            lookup = context.item.lookup,
                            ownership = context.item.ownership,
                            permissions = context.item.permissions,
                            createdAt = context.item.createdAt,
                            childCount = context.item.childCount,
                        ),
                        onDismiss = { vm?.dismissDialog() },
                        onCopyToClipboard = { text -> vm?.copyPathToSystemClipboard(text) },
                    )
                }
                is ExplorerDialogState.ItemInfo.InfoContext.MultipleItems -> {
                    // Use shared component for multiple items
                    MultipleItemsInfoBottomSheet(
                        totalCount = context.selectedItems.size,
                        fileCount = context.fileCount,
                        directoryCount = context.directoryCount,
                        totalSize = context.totalSize,
                        onDismiss = { vm?.dismissDialog() },
                    )
                }
                // Keep Explorer-specific contexts with original ItemInfoBottomSheet
                is ExplorerDialogState.ItemInfo.InfoContext.SingleSAF,
                is ExplorerDialogState.ItemInfo.InfoContext.DeviceView,
                is ExplorerDialogState.ItemInfo.InfoContext.HomeView -> {
                    ItemInfoBottomSheet(
                        context = context,
                        onDismiss = { vm?.dismissDialog() },
                        onCopyToClipboard = { text -> vm?.copyPathToSystemClipboard(text) }
                    )
                }
            }
        }
    }
}