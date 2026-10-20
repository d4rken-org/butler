package eu.darken.butler.workspace.ui.hosted

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.LocalWorkspacePageHosts
import eu.darken.butler.workspace.ui.floatingbar.LocalWorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.modal.WorkspaceBackHandler
import eu.darken.butler.workspace.ui.scroll.LocalWorkspaceScrollPositions
import eu.darken.butler.workspace.ui.workspaces.WorkspaceModalContent

@Composable
fun HostedPickerScreenHost(
    token: String,
    vm: HostedPickerViewModel = hiltViewModel(
        key = token,
        creationCallback = { factory: HostedPickerViewModel.Factory -> factory.create(token) },
    ),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val state by vm.state.collectAsState(initial = HostedPickerViewModel.State.Loading)

    // Composed before the page, so the page's own handlers (clear selection, go up, cancel) win
    WorkspaceBackHandler { vm.close() }

    CompositionLocalProvider(
        LocalWorkspacePageHosts provides vm.pageHosts,
        LocalWorkspaceScrollPositions provides vm.scrollPositions,
        LocalWorkspaceBarCollapseStates provides vm.barCollapseStates,
        LocalWorkspaceFocused provides true,
    ) {
        HostedPickerScreen(
            state = state,
            onShareError = { vm.showError(it) },
            onCloseWorkspace = { vm.closeWorkspace(it) },
        )
    }
}

@Composable
fun HostedPickerScreen(
    modifier: Modifier = Modifier,
    state: HostedPickerViewModel.State,
    onShareError: (Throwable) -> Unit,
    onCloseWorkspace: (Workspace.Id) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (state) {
            is HostedPickerViewModel.State.Showing -> WorkspaceModalContent(
                workspace = state.leaf,
                design = WorkspaceDesign(),
                onShareError = onShareError,
                onCloseWorkspace = { onCloseWorkspace(state.leaf.id) },
            )

            HostedPickerViewModel.State.Loading,
            HostedPickerViewModel.State.Gone -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HostedPickerScreenLoadingPreview() {
    HostedPickerScreen(
        state = HostedPickerViewModel.State.Loading,
        onShareError = {},
        onCloseWorkspace = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HostedPickerScreenShowingPreview() {
    // No page host map in previews: the content renders its own fallback instead
    CompositionLocalProvider(LocalWorkspacePageHosts provides emptyMap()) {
        HostedPickerScreen(
            state = HostedPickerViewModel.State.Showing(
                leaf = Workspace.Info(
                    id = Workspace.Id(),
                    type = Workspace.Type.EXPLORER,
                    title = "Select Folder".toCaString(),
                    lifecycleState = Workspace.LifecycleState.Ready,
                    callerWorkspaceId = Workspace.Id(),
                    isHosted = true,
                ),
            ),
            onShareError = {},
            onCloseWorkspace = {},
        )
    }
}
