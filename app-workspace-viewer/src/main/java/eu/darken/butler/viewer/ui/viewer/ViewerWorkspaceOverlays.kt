package eu.darken.butler.viewer.ui.viewer

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.workspace.core.Workspace

/**
 * Overlay slot of the viewer page.
 *
 * Shares the ViewModel with [ViewerWorkspacePageHost]; the share-intent collector stays there. The
 * error handler lives here instead, because it renders a dialog that has to be pane-bound.
 */
@Composable
fun ViewerWorkspaceOverlaysHost(
    id: Workspace.Id,
    vm: ViewerWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: ViewerWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)
}
