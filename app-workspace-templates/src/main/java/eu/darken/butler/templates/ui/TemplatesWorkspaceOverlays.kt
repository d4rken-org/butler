package eu.darken.butler.templates.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.templates.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dialogs.PaneBoundWorkspaceRenameDialog

/**
 * Overlay slot of the templates page.
 *
 * Shares the ViewModel with [TemplatesWorkspacePageHost]; the navigation handler stays there. The
 * error handler lives here instead, because it renders a dialog that has to be pane-bound.
 */
@Composable
fun TemplatesWorkspaceOverlaysHost(
    id: Workspace.Id,
    vm: TemplatesWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: TemplatesWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    val state by vm.state.collectAsState(initial = null)
    val renameVisible by vm.renameDialogVisible.collectAsState()

    TemplatesWorkspaceOverlays(
        renameVisible = renameVisible,
        customTitle = state?.customTitle,
        onRename = { vm.renameWorkspace(it) },
        onDismissRename = { vm.dismissRenameDialog() },
    )

    // Last on purpose: layers stack in composition order, so an error raised while one of
    // this page's own dialogs is up lands on top of it instead of underneath.
    ErrorEventHandler(vm)
}

@Composable
fun TemplatesWorkspaceOverlays(
    renameVisible: Boolean,
    customTitle: String? = null,
    onRename: (String?) -> Unit = {},
    onDismissRename: () -> Unit = {},
) {
    if (renameVisible) {
        // Pane-bound: naming this tab is an action on this pane, so it must not dim the whole
        // window, and back / focus / screen readers stay contained to the pane.
        PaneBoundWorkspaceRenameDialog(
            currentCustomTitle = customTitle,
            autoTitle = stringResource(R.string.workspace_templates_tab_title),
            onConfirm = onRename,
            onDismiss = onDismissRename,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TemplatesWorkspaceOverlaysRenamePreview() {
    TemplatesWorkspaceOverlays(
        renameVisible = true,
        customTitle = "Holiday photos",
    )
}
