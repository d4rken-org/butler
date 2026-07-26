package eu.darken.butler.apps.ui.details

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.workspace.core.Workspace

/**
 * Overlay slot of the app details page.
 *
 * Shares the ViewModel with [AppDetailsWorkspacePageHost]; the navigation handler stays there. The
 * error handler lives here instead, because it renders a dialog that has to be pane-bound.
 */
@Composable
fun AppDetailsWorkspaceOverlaysHost(
    id: Workspace.Id,
    vm: AppDetailsWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: AppDetailsWorkspaceViewModel.Factory ->
            factory.create(id = id)
        }
    ),
) {
    // Last on purpose: layers stack in composition order, so an error raised while one of
    // this page's own dialogs is up lands on top of it instead of underneath.
    ErrorEventHandler(vm)
}
