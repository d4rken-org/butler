package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.WorkspaceAction.Rename.Companion.MAX_CUSTOM_TITLE_LENGTH
import eu.darken.butler.workspace.ui.modal.PaneLayerHost

/**
 * Sets or clears the user-set name of a workspace.
 *
 * One composable for every caller: the host follows from where it is composed. Reached from inside
 * a pane, the Templates tab's headline edit icon, it is pane-bound, so it leaves the other panes
 * alone and takes part in that pane's back, focus and accessibility containment. Reached from the tab rail or
 * the tab manager, which act on the whole screen, it is a window dialog.
 */
@Composable
fun WorkspaceRenameDialog(
    currentCustomTitle: String?,
    autoTitle: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    CustomNameDialog(
        currentName = currentCustomTitle,
        autoName = autoTitle,
        dialogTitle = stringResource(R.string.workspace_rename_dialog_title),
        fieldLabel = stringResource(R.string.workspace_rename_name_label),
        fieldHint = stringResource(R.string.workspace_rename_name_hint),
        maxLength = MAX_CUSTOM_TITLE_LENGTH,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceRenameDialogPreview() {
    WorkspaceRenameDialog(
        currentCustomTitle = null,
        autoTitle = "/storage/emulated/0/Download",
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceRenameDialogNamedPreview() {
    WorkspaceRenameDialog(
        currentCustomTitle = "Holiday photos",
        autoTitle = "/storage/emulated/0/DCIM/Camera",
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceRenameDialogLongNamePreview() {
    WorkspaceRenameDialog(
        currentCustomTitle = "Holiday photos from the summer trip",
        autoTitle = "New tab",
        onConfirm = {},
        onDismiss = {},
    )
}

/** The same dialog inside a pane: composing it under a [PaneLayerHost] is what makes it pane-bound. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBoundWorkspaceRenameDialogPreview() {
    PreviewWrapper {
        PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
            WorkspaceRenameDialog(
                currentCustomTitle = "Holiday photos",
                autoTitle = "New tab",
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneBoundWorkspaceRenameDialogUnnamedPreview() {
    PreviewWrapper {
        PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
            WorkspaceRenameDialog(
                currentCustomTitle = null,
                autoTitle = "New tab",
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}
