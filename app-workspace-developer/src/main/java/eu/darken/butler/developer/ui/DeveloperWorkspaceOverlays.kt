package eu.darken.butler.developer.ui

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.Factory
import eu.darken.butler.workspace.core.Workspace

/**
 * Overlay slot of the developer page.
 *
 * Shares the ViewModel with [DeveloperWorkspacePageHost]; the navigation handler and the
 * share-intent collector stay there. The error handler lives here instead, because it renders a
 * dialog that has to be pane-bound.
 */
@Composable
fun DeveloperWorkspaceOverlaysHost(
    id: Workspace.Id,
    vm: DeveloperWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)
}
