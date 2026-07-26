package eu.darken.butler.history.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

/**
 * Overlay slot of the history page.
 *
 * Shares the ViewModel with [HistoryWorkspacePageHost]; the error handler stays there.
 */
@Composable
fun HistoryWorkspaceOverlaysHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: HistoryWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: HistoryWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    val state by vm.state.collectAsState(initial = null)
    val overlayState by vm.overlayState.collectAsState()

    HistoryWorkspaceOverlays(
        design = design,
        filter = state?.filter,
        overlayState = overlayState,
        onDismissAddFilter = { vm.setAddFilterOpen(false) },
        onToggleOutcome = { vm.toggleOutcome(it) },
        onToggleKind = { vm.toggleKind(it) },
        onRemovePathScope = { vm.removePathScope(it) },
        onAddPathScopeRequested = { vm.openPathScopePicker() },
        onDismissEntryDetails = { vm.showEntryDetails(null) },
        onDismissPathScope = { vm.closePathScopePicker() },
        onApplyPathScope = { newScope ->
            if (newScope != null) vm.addPathScope(newScope)
            vm.closePathScopePicker()
        },
    )
}

@Composable
fun HistoryWorkspaceOverlays(
    design: WorkspaceDesign = WorkspaceDesign(),
    filter: HistoryFilter?,
    overlayState: HistoryWorkspaceViewModel.OverlayState,
    onDismissAddFilter: () -> Unit = {},
    onToggleOutcome: (HistoryOutcome) -> Unit = {},
    onToggleKind: (Operation.Metadata.Kind) -> Unit = {},
    onRemovePathScope: (String) -> Unit = {},
    onAddPathScopeRequested: () -> Unit = {},
    onDismissEntryDetails: () -> Unit = {},
    onDismissPathScope: () -> Unit = {},
    onApplyPathScope: (String?) -> Unit = {},
) {
    val paneInsets = design.paneInsets()
    val navBarInset = paneInsets.bottom
    val statusBarInset = paneInsets.top

    if (filter != null) {
        HistoryAddFilterSheet(
            visible = overlayState.addFilterOpen,
            filter = filter,
            topInset = statusBarInset,
            bottomInset = navBarInset,
            onDismiss = onDismissAddFilter,
            onToggleOutcome = onToggleOutcome,
            onToggleKind = onToggleKind,
            onRemovePathScope = onRemovePathScope,
            onAddPathScopeRequested = onAddPathScopeRequested,
        )
    }

    HistoryEntryDetailsBottomSheet(
        entry = overlayState.detailEntry,
        topInset = statusBarInset,
        bottomInset = navBarInset,
        onDismiss = onDismissEntryDetails,
    )

    if (overlayState.pathScopeOpen) {
        PathScopeDialog(
            initialPath = null,
            onDismiss = onDismissPathScope,
            onApply = onApplyPathScope,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HistoryWorkspaceOverlaysAddFilterPreview() {
    HistoryWorkspaceOverlays(
        filter = HistoryFilter(),
        overlayState = HistoryWorkspaceViewModel.OverlayState(addFilterOpen = true),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HistoryWorkspaceOverlaysPathScopePreview() {
    HistoryWorkspaceOverlays(
        filter = HistoryFilter(),
        overlayState = HistoryWorkspaceViewModel.OverlayState(pathScopeOpen = true),
    )
}
