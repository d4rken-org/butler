package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    trashEnabled: Boolean,
    fileOpenActionsEnabled: Boolean = true,
    vm: ExplorerWorkspaceViewModel?,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
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
                trashEnabled = trashEnabled,
                initialPermanentDelete = dialogState.initialPermanentDelete,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { items, forcePermDelete ->
                    vm?.onDeleteConfirmed(items, forcePermDelete)
                },
            )
        }

        is ExplorerDialogState.RemoveLocationConfirmation -> {
            RemoveLocationConfirmationDialog(
                items = dialogState.items,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { vm?.onRemoveLocationConfirmed() }
            )
        }

        is ExplorerDialogState.SmbLocationForm -> {
            SmbLocationFormSheet(
                state = dialogState,
                onDismiss = { vm?.dismissDialog() },
                onSubmit = { input -> vm?.onSmbLocationFormSubmit(input) },
                topInset = topInset,
                bottomInset = bottomInset,
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
            SortOptionsSheet(
                state = dialogState,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { result -> vm?.onSortOptions(result) },
                onClearTabOverrides = { vm?.clearTabSortOverrides() },
                topInset = topInset,
                bottomInset = bottomInset,
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
                trashEnabled = trashEnabled,
                openActionsEnabled = fileOpenActionsEnabled,
                onDismiss = { vm?.dismissDialog() },
                onAction = { action -> vm?.executeAction(action) },
                topInset = topInset,
                bottomInset = bottomInset,
            )
        }

        is ExplorerDialogState.CompressOptions -> {
            CompressOptionsSheet(
                suggestedName = dialogState.suggestedName,
                defaultFormat = dialogState.defaultFormat,
                defaultPreset = dialogState.defaultPreset,
                onValidate = vm?.let { vm::validateFilename } ?: { FilenameValidator.ValidationResult.Valid },
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { name, format, preset, password ->
                    vm?.onCompressConfirmed(
                        sources = dialogState.sources,
                        destinationDir = dialogState.destinationDir,
                        archiveName = name,
                        format = format,
                        preset = preset,
                        password = password,
                    )
                },
                topInset = topInset,
                bottomInset = bottomInset,
            )
        }

        is ExplorerDialogState.CompressOverwriteConfirmation -> {
            CompressOverwriteDialog(
                archiveName = dialogState.pending.archiveName,
                onDismiss = { vm?.onCompressOverwriteCancelled(dialogState.pending) },
                onConfirm = { vm?.onCompressOverwriteConfirmed(dialogState.pending) },
            )
        }

        is ExplorerDialogState.TrashItemOptions -> {
            TrashItemDetailsBottomSheet(
                item = dialogState.item,
                onAction = { action -> vm?.executeAction(action) },
                onCopyToClipboard = { text -> vm?.copyPathToSystemClipboard(text) },
                onDismiss = { vm?.dismissDialog() },
                topInset = topInset,
                bottomInset = bottomInset,
            )
        }

        is ExplorerDialogState.TrashNestedItemOptions -> {
            TrashNestedItemDetailsBottomSheet(
                item = dialogState.item,
                onAction = { action -> vm?.executeAction(action) },
                onCopyToClipboard = { text -> vm?.copyPathToSystemClipboard(text) },
                onDismiss = { vm?.dismissDialog() },
                topInset = topInset,
                bottomInset = bottomInset,
            )
        }

        is ExplorerDialogState.EmptyTrashConfirmation -> {
            EmptyTrashConfirmationDialog(
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { vm?.onEmptyTrashConfirmed() }
            )
        }

        is ExplorerDialogState.DropConfirmation -> {
            DropConfirmationDialog(
                payload = dialogState.payload,
                destination = dialogState.destination,
                onDismiss = { vm?.dismissDialog() },
                onCopy = { vm?.onDropConfirmed(dialogState.payload, dialogState.destination, move = false) },
                onMove = { vm?.onDropConfirmed(dialogState.payload, dialogState.destination, move = true) },
            )
        }

        is ExplorerDialogState.TrashDropConfirmation -> {
            TrashDropConfirmationDialog(
                payload = dialogState.payload,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { vm?.onTrashDropConfirmed(dialogState.payload) },
            )
        }

        is ExplorerDialogState.ClipboardInfo -> {
            ClipboardInfoBottomSheet(
                clip = dialogState.clip,
                onDismiss = { vm?.dismissDialog() },
                onNavigateToSource = { vm?.navigateToClipboardSource(dialogState.clip) },
                onPaste = { vm?.pasteClipboard(dialogState.clip) },
                onRemove = { vm?.removeClipboardEntry(dialogState.clip) },
                onCopyPath = { path -> vm?.copyPathToSystemClipboard(path) },
                topInset = topInset,
                bottomInset = bottomInset,
            )
        }

        is ExplorerDialogState.LocationStorageName -> {
            LocationStorageNameDialog(
                currentName = dialogState.currentName,
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { name -> vm?.onLocationStorageName(name) }
            )
        }

        is ExplorerDialogState.CreateFileFromText -> {
            CreateFileFromTextDialog(
                clip = dialogState.clip,
                onValidate = vm?.let { vm::validateFilename } ?: { FilenameValidator.ValidationResult.Valid },
                onDismiss = { vm?.dismissDialog() },
                onConfirm = { clip, filename -> vm?.onCreateFileFromText(clip, filename) }
            )
        }

        is ExplorerDialogState.ItemInfo -> {
            when (val context = dialogState.context) {
                is ExplorerDialogState.ItemInfo.InfoContext.SingleFile -> {
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
                        topInset = topInset,
                        bottomInset = bottomInset,
                    )
                }
                is ExplorerDialogState.ItemInfo.InfoContext.SingleDirectory -> {
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
                        topInset = topInset,
                        bottomInset = bottomInset,
                    )
                }
                is ExplorerDialogState.ItemInfo.InfoContext.MultipleItems -> {
                    MultipleItemsInfoBottomSheet(
                        totalCount = context.selectedItems.size,
                        fileCount = context.fileCount,
                        directoryCount = context.directoryCount,
                        totalSize = context.totalSize,
                        onDismiss = { vm?.dismissDialog() },
                        topInset = topInset,
                        bottomInset = bottomInset,
                    )
                }
                // Keep Explorer-specific contexts with original ItemInfoBottomSheet
                is ExplorerDialogState.ItemInfo.InfoContext.SingleSAF,
                is ExplorerDialogState.ItemInfo.InfoContext.SingleLocalStorage,
                is ExplorerDialogState.ItemInfo.InfoContext.SingleNetwork,
                is ExplorerDialogState.ItemInfo.InfoContext.DeviceView,
                is ExplorerDialogState.ItemInfo.InfoContext.HomeView -> {
                    ItemInfoBottomSheet(
                        context = context,
                        onDismiss = { vm?.dismissDialog() },
                        onCopyToClipboard = { text -> vm?.copyPathToSystemClipboard(text) },
                        topInset = topInset,
                        bottomInset = bottomInset,
                    )
                }
            }
        }
    }
}