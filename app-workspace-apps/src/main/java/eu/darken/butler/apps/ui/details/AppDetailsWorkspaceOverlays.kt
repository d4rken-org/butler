package eu.darken.butler.apps.ui.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.apps.core.details.AppDetailsWorkspaceViewModel
import eu.darken.butler.apps.core.details.components.ComponentEnabledState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentKind
import eu.darken.butler.apps.ui.details.components.ComponentDetailsSheet
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Overlay slot of the app details page.
 *
 * Shares the ViewModel with [AppDetailsWorkspacePageHost]; the error and navigation handlers stay
 * there, or every event would be handled twice.
 */
@Composable
fun AppDetailsWorkspaceOverlaysHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: AppDetailsWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: AppDetailsWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    AppDetailsWorkspaceOverlays(
        design = design,
        selectedSource = vm.selectedComponent,
        onDismiss = vm::onComponentSheetDismissed,
        onLaunch = { vm.onLaunchComponent(packageName = it.packageName, className = it.className) },
    )
}

@Composable
fun AppDetailsWorkspaceOverlays(
    design: WorkspaceDesign = WorkspaceDesign(),
    selectedSource: Flow<ComponentEntry?>,
    onDismiss: () -> Unit = {},
    onLaunch: (ComponentEntry) -> Unit = {},
) {
    // The real source is an eagerly shared StateFlow, so a remount reads the current selection with
    // no null first frame — which would briefly unmount the sheet's layer.
    val selected by selectedSource.collectAsState(
        initial = (selectedSource as? StateFlow<ComponentEntry?>)?.value
    )

    val paneInsets = design.paneInsets()
    val launchable = selected?.takeIf { it.kind == ComponentKind.ACTIVITY }

    // Passed straight through, including null: the sheet stays composed and drives its visibility
    // from the selection so it can run its exit transition.
    ComponentDetailsSheet(
        entry = selected,
        onDismiss = onDismiss,
        onLaunch = launchable?.let { entry -> { onLaunch(entry) } },
        topInset = paneInsets.top,
        bottomInset = paneInsets.bottom,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsWorkspaceOverlaysActivityPreview() {
    AppDetailsWorkspaceOverlays(
        selectedSource = flowOf(
            ComponentEntry(
                kind = ComponentKind.ACTIVITY,
                packageName = "com.example.app",
                className = "com.example.app.MainActivity",
                isExported = true,
                enabledState = ComponentEnabledState.ENABLED,
            )
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsWorkspaceOverlaysProviderPreview() {
    AppDetailsWorkspaceOverlays(
        selectedSource = flowOf(
            ComponentEntry(
                kind = ComponentKind.PROVIDER,
                packageName = "com.example.app",
                className = "com.example.app.data.FileProvider",
                isExported = false,
                enabledState = ComponentEnabledState.DISABLED,
                authority = "com.example.app.files",
            )
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsWorkspaceOverlaysNoSelectionPreview() {
    AppDetailsWorkspaceOverlays(selectedSource = flowOf(null))
}
