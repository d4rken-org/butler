package eu.darken.butler.editor.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.editor.ui.editor.dialogs.CloseConfirmDialog
import eu.darken.butler.editor.ui.editor.dialogs.EncodingDialog
import eu.darken.butler.editor.ui.editor.dialogs.GoToLineDialog
import eu.darken.butler.editor.ui.editor.dialogs.LargeDeleteConfirmDialog
import eu.darken.butler.editor.ui.editor.dialogs.LineEndingDialog
import eu.darken.butler.editor.ui.editor.dialogs.ReloadConfirmDialog
import eu.darken.butler.editor.ui.editor.dialogs.SaveAsOverwriteDialog
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.clipboard.details.ClipboardInfoBottomSheet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Overlay slot of the editor page.
 *
 * Shares the ViewModel with [EditorWorkspacePageHost] — every host-level side effect (navigation
 * handling, clipboard refresh, external-change polling) stays there and must not be repeated here.
 * The error handler lives here instead, because it renders a dialog that has to be pane-bound.
 */
@Composable
fun EditorWorkspaceOverlaysHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: EditorWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: EditorWorkspaceViewModel.Factory ->
            factory.create(id)
        }
    ),
) {
    ErrorEventHandler(vm)

    val clipboardInfoClip by vm.clipboardInfoClip.collectAsState(null)

    EditorWorkspaceOverlays(
        design = design,
        stateSource = vm.state,
        clipboardInfoClip = clipboardInfoClip,
        onPageAction = vm::onPageAction,
    )
}

@Composable
fun EditorWorkspaceOverlays(
    design: WorkspaceDesign = WorkspaceDesign(),
    stateSource: Flow<EditorWorkspaceViewModel.State>,
    clipboardInfoClip: ClipboardClip? = null,
    onPageAction: (EditorPageAction) -> Unit,
) {
    // StateFlow check: use current value as initial for single-frame renderers (screenshot tests, previews)
    val stateOrNull by stateSource.collectAsState(initial = (stateSource as? StateFlow)?.value)
    val state = stateOrNull ?: return
    val paneInsets = design.paneInsets()

    if (state.showGoToLineDialog) {
        GoToLineDialog(
            totalLines = state.totalLines,
            onGoToLine = { line ->
                onPageAction(EditorPageAction.Navigation.GoToLine(line))
                onPageAction(EditorPageAction.Dialog.DismissGoToLine)
            },
            onDismiss = { onPageAction(EditorPageAction.Dialog.DismissGoToLine) },
        )
    }

    if (state.showCloseConfirmDialog) {
        CloseConfirmDialog(
            onConfirm = { onPageAction(EditorPageAction.Dialog.ConfirmClose) },
            onDismiss = { onPageAction(EditorPageAction.Dialog.DismissCloseConfirm) },
        )
    }

    if (state.showReloadConfirmDialog) {
        ReloadConfirmDialog(
            onConfirm = { onPageAction(EditorPageAction.Dialog.ConfirmReload) },
            onDismiss = { onPageAction(EditorPageAction.Dialog.DismissReloadConfirm) },
        )
    }

    if (state.showLargeDeleteConfirmDialog) {
        LargeDeleteConfirmDialog(
            onConfirm = { onPageAction(EditorPageAction.Dialog.ConfirmLargeDelete) },
            onDismiss = { onPageAction(EditorPageAction.Dialog.DismissLargeDeleteConfirm) },
        )
    }

    if (state.showEncodingDialog) {
        EncodingDialog(
            currentEncoding = state.fileEncoding,
            onSelect = { charsetName -> onPageAction(EditorPageAction.File.ReopenWithEncoding(charsetName)) },
            onDismiss = { onPageAction(EditorPageAction.Dialog.DismissEncoding) },
        )
    }

    if (state.showLineEndingDialog) {
        state.lineEnding?.let { current ->
            LineEndingDialog(
                currentLineEnding = current,
                onSelect = { target -> onPageAction(EditorPageAction.File.ConvertLineEndings(target)) },
                onDismiss = { onPageAction(EditorPageAction.Dialog.DismissLineEnding) },
            )
        }
    }

    if (state.pendingEncoding != null) {
        // Reopening with a different encoding rescans from disk and discards unsaved changes
        CloseConfirmDialog(
            onConfirm = { onPageAction(EditorPageAction.Dialog.ConfirmEncodingDiscard) },
            onDismiss = { onPageAction(EditorPageAction.Dialog.DismissEncodingDiscard) },
        )
    }

    state.pendingSaveAsOverwrite?.let { destination ->
        SaveAsOverwriteDialog(
            fileName = destination.name,
            onConfirm = { onPageAction(EditorPageAction.Dialog.ConfirmSaveAsOverwrite) },
            onDismiss = { onPageAction(EditorPageAction.Dialog.DismissSaveAsOverwrite) },
        )
    }

    clipboardInfoClip?.let { clip ->
        ClipboardInfoBottomSheet(
            clip = clip,
            onDismiss = { onPageAction(EditorPageAction.Clipboard.DismissInfo) },
            onNavigateToSource = null,
            onPaste = { onPageAction(EditorPageAction.Clipboard.Paste(clip)) },
            onRemove = { onPageAction(EditorPageAction.Clipboard.Remove(clip)) },
            topInset = paneInsets.top,
            bottomInset = paneInsets.bottom,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorWorkspaceOverlaysCloseConfirmPreview() {
    EditorWorkspaceOverlays(
        stateSource = flowOf(
            EditorWorkspaceViewModel.State(
                id = Workspace.Id(),
                title = caString("test.txt"),
                subTitle = caString("/some/storage/test.txt"),
                showCloseConfirmDialog = true,
            )
        ),
        onPageAction = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun EditorWorkspaceOverlaysGoToLinePreview() {
    EditorWorkspaceOverlays(
        stateSource = flowOf(
            EditorWorkspaceViewModel.State(
                id = Workspace.Id(),
                title = caString("test.txt"),
                subTitle = caString("/some/storage/test.txt"),
                totalLines = 4200,
                showGoToLineDialog = true,
            )
        ),
        onPageAction = {},
    )
}
