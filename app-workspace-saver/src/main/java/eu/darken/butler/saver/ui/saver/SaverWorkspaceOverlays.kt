package eu.darken.butler.saver.ui.saver

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

/**
 * Overlay slot of the saver page.
 *
 * Shares the ViewModel with [SaverWorkspacePageHost]; the navigation handler, the share-intent
 * collector and the auto-surface effect stay there. The error handler lives here instead, because
 * it renders a dialog that has to be pane-bound.
 */
@Composable
fun SaverWorkspaceOverlaysHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: SaverWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: SaverWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)
}
