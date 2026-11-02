package eu.darken.butler.searcher.ui.search.dialogs

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.core.OpenSelectionMode
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.clipboard.details.ClipboardInfoBottomSheet
import eu.darken.butler.workspace.ui.dialogs.DeleteConfirmationDialog
import eu.darken.butler.workspace.ui.dialogs.FileInfo
import eu.darken.butler.workspace.ui.dialogs.FileInfoBottomSheet
import eu.darken.butler.workspace.ui.dialogs.OpenSelectionDialog
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialog
import eu.darken.butler.workspace.ui.issues.PathIssueRenameDialog
import eu.darken.butler.workspace.ui.issues.initialRenameSelection

@Composable
fun SearcherDialogHost(
    modifier: Modifier = Modifier,
    dialogState: SearcherDialogState,
    viewStyle: SearcherViewStyle,
    trashEnabled: Boolean,
    onDismiss: () -> Unit,
    onDeleteConfirmed: (items: Set<APath<*>>, forcePermDelete: Boolean) -> Unit,
    onCopyToClipboard: (String) -> Unit,
    onNavigateToClipboardSource: (ClipboardClip) -> Unit,
    onRemoveClipboardEntry: (ClipboardClip) -> Unit,
    onOpenSelectionModePicked: (SearcherDialogState.OpenSelection, OpenSelectionMode) -> Unit,
    onSortOptionsConfirmed: (SearchSortOptionsResult) -> Unit = {},
    onClearHistoryConfirmed: () -> Unit = {},
    onConditionApply: (existing: FilterCondition?, new: FilterCondition) -> Unit = { _, _ -> },
    onViewStyleApplyToTab: (SearcherViewStyle) -> Unit = {},
    onViewStyleSetAsDefault: (SearcherViewStyle) -> Unit = {},
    onValidateName: (name: String, parent: APath<*>) -> FilenameValidator.ValidationResult = { _, _ ->
        FilenameValidator.ValidationResult.Valid
    },
    onRenameConfirmed: (result: SearchItem, newName: String) -> Unit = { _, _ -> },
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
                initialPermanentDelete = dialogState.initialPermanentDelete,
                onDismiss = onDismiss,
                onConfirm = onDeleteConfirmed,
            )
        }
        is SearcherDialogState.Rename -> {
            val result = dialogState.result
            PathIssueRenameDialog(
                currentName = result.name,
                initialSelection = initialRenameSelection(result.name),
                onValidate = { name ->
                    result.path.parent?.let { onValidateName(name, it) } ?: FilenameValidator.ValidationResult.Valid
                },
                onConfirm = { newName -> onRenameConfirmed(result, newName) },
                onDismiss = onDismiss,
                dialogTitle = stringResource(eu.darken.butler.common.R.string.general_rename_action),
            )
        }
        is SearcherDialogState.OpenSelection -> {
            OpenSelectionDialog(
                itemCount = dialogState.results.size,
                viewerModesAvailable = dialogState.viewerModesAvailable,
                onDismiss = onDismiss,
                onSelect = { mode -> onOpenSelectionModePicked(dialogState, mode) },
            )
        }
        is SearcherDialogState.ClearHistoryConfirmation -> {
            PaneBoundAlertDialog(
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
                topInset = topInset,
                bottomInset = bottomInset,
            )
        }
        is SearcherDialogState.ShowItemProperties -> {
            FileInfoBottomSheet(
                fileInfo = FileInfo(
                    lookup = dialogState.result.lookup,
                    ownership = dialogState.result.lookup.ownership,
                    permissions = dialogState.result.lookup.permissions,
                    createdAt = dialogState.result.lookup.createdAt,
                ),
                onDismiss = onDismiss,
                onCopyToClipboard = onCopyToClipboard,
                topInset = topInset,
                bottomInset = bottomInset,
            )
        }
        // Non-destructive dialogs handled by pane-scoped bottom sheets below
        is SearcherDialogState.EditSortOptions,
        is SearcherDialogState.EditViewStyle,
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

    ViewStyleOptionsSheet(
        visible = dialogState is SearcherDialogState.EditViewStyle,
        currentViewStyle = viewStyle,
        onApplyToTab = onViewStyleApplyToTab,
        onSetAsDefault = onViewStyleSetAsDefault,
        onDismiss = onDismiss,
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SearcherDialogHostRenamePreview() {
    SearcherDialogHost(
        dialogState = SearcherDialogState.Rename(
            SearchItem.fromLookup(
                lookup = LocalPathLookup(
                    lookedUp = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg"),
                    fileType = FileType.FILE,
                    size = 2048L,
                    modifiedAt = null,
                ),
                matchedQuery = "photo",
            )
        ),
        viewStyle = SearcherViewStyle.default(),
        trashEnabled = false,
        onDismiss = {},
        onDeleteConfirmed = { _, _ -> },
        onCopyToClipboard = {},
        onNavigateToClipboardSource = {},
        onRemoveClipboardEntry = {},
        onOpenSelectionModePicked = { _, _ -> },
    )
}
