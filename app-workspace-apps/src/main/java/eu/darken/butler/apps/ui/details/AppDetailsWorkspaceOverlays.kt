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
import eu.darken.butler.apps.core.details.components.ComponentToggleState
import eu.darken.butler.apps.ui.details.components.ComponentDetailsSheet
import eu.darken.butler.apps.ui.details.components.ComponentsConfirmDialog
import eu.darken.butler.apps.ui.details.components.ComponentsConfirmRequest
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Overlay slot of the app details page.
 *
 * Shares the ViewModel with [AppDetailsWorkspacePageHost]; the navigation handler stays there, or
 * every event would be handled twice. The error handler is the exception — it renders a dialog, so
 * it has to live in this slot to be pane-bound, and it is collected here and nowhere else.
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
        toggleStateSource = vm.componentToggleState,
        confirmSource = vm.componentConfirm,
        onDismiss = vm::onComponentSheetDismissed,
        onLaunch = { vm.onLaunchComponent(packageName = it.packageName, className = it.className) },
        onSetEnabled = { entry, enabled -> vm.onSetComponentEnabled(entry, enabled) },
        onSetupRequested = vm::openElevatedAccessSetup,
        onConfirm = vm::onComponentConfirm,
        onConfirmDismiss = vm::onComponentConfirmDismiss,
    )

    // Last on purpose: layers stack in composition order, so an error raised while one of this
    // page's own overlays is up lands on top of it instead of underneath.
    ErrorEventHandler(vm)
}

@Composable
fun AppDetailsWorkspaceOverlays(
    design: WorkspaceDesign = WorkspaceDesign(),
    selectedSource: Flow<ComponentEntry?>,
    toggleStateSource: Flow<ComponentToggleState> = flowOf(ComponentToggleState.UNSUPPORTED),
    confirmSource: Flow<ComponentsConfirmRequest?> = flowOf(null),
    onDismiss: () -> Unit = {},
    onLaunch: (ComponentEntry) -> Unit = {},
    onSetEnabled: (ComponentEntry, Boolean) -> Unit = { _, _ -> },
    onSetupRequested: () -> Unit = {},
    onConfirm: (ComponentsConfirmRequest) -> Unit = {},
    onConfirmDismiss: () -> Unit = {},
) {
    // The real sources are eagerly shared StateFlows, so a remount reads the current values with no
    // null first frame — which would briefly unmount the sheet's layer.
    val selected by selectedSource.collectAsState(
        initial = (selectedSource as? StateFlow<ComponentEntry?>)?.value
    )
    val toggleState by toggleStateSource.collectAsState(
        initial = (toggleStateSource as? StateFlow<ComponentToggleState>)?.value
            ?: ComponentToggleState.UNSUPPORTED
    )
    val confirmRequest by confirmSource.collectAsState(
        initial = (confirmSource as? StateFlow<ComponentsConfirmRequest?>)?.value
    )

    val paneInsets = design.paneInsets()
    val launchable = selected?.takeIf { it.kind == ComponentKind.ACTIVITY }
    val toggleTarget = selected

    // Passed straight through, including null: the sheet stays composed and drives its visibility
    // from the selection so it can run its exit transition.
    ComponentDetailsSheet(
        entry = selected,
        onDismiss = onDismiss,
        onLaunch = launchable?.let { entry -> { onLaunch(entry) } },
        toggleState = toggleState,
        onSetEnabled = { enabled -> toggleTarget?.let { onSetEnabled(it, enabled) } },
        onSetupRequested = onSetupRequested,
        topInset = paneInsets.top,
        bottomInset = paneInsets.bottom,
    )

    // Composed conditionally: PaneBoundAlertDialog has no exit animation.
    confirmRequest?.let { request ->
        ComponentsConfirmDialog(
            request = request,
            onConfirm = { onConfirm(request) },
            onDismiss = onConfirmDismiss,
        )
    }
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
        toggleStateSource = flowOf(ComponentToggleState.AVAILABLE),
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
        toggleStateSource = flowOf(ComponentToggleState.NEEDS_SETUP),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsWorkspaceOverlaysNoSelectionPreview() {
    AppDetailsWorkspaceOverlays(selectedSource = flowOf(null))
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppDetailsWorkspaceOverlaysConfirmPreview() {
    AppDetailsWorkspaceOverlays(
        selectedSource = flowOf(null),
        toggleStateSource = flowOf(ComponentToggleState.AVAILABLE),
        confirmSource = flowOf(
            ComponentsConfirmRequest(
                entries = listOf(
                    ComponentEntry(
                        kind = ComponentKind.RECEIVER,
                        packageName = "com.example.app",
                        className = "com.example.app.BootReceiver",
                        isExported = true,
                        enabledState = ComponentEnabledState.ENABLED,
                    ),
                    ComponentEntry(
                        kind = ComponentKind.SERVICE,
                        packageName = "com.example.app",
                        className = "com.example.app.sync.SyncService",
                        isExported = false,
                        enabledState = ComponentEnabledState.ENABLED,
                    ),
                ),
                enable = false,
            )
        ),
    )
}
