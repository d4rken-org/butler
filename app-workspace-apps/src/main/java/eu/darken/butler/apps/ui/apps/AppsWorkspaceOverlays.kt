package eu.darken.butler.apps.ui.apps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogHost
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogState
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Overlay slot of the apps page.
 *
 * Shares the ViewModel with [AppsWorkspacePageHost]; the navigation handler stays there. The error
 * handler lives here instead, because it renders a dialog that has to be pane-bound.
 */
@Composable
fun AppsWorkspaceOverlaysHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: AppsWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: AppsWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    AppsWorkspaceOverlays(
        design = design,
        stateSource = vm.state,
        onPageAction = vm::onPageAction,
    )

    // Last on purpose: layers stack in composition order, so an error raised while one of
    // this page's own dialogs is up lands on top of it instead of underneath.
    ErrorEventHandler(vm)
}

@Composable
fun AppsWorkspaceOverlays(
    design: WorkspaceDesign = WorkspaceDesign(),
    stateSource: Flow<AppsWorkspaceViewModel.State>,
    onPageAction: (AppsPageAction) -> Unit = {},
) {
    // StateFlow check: use current value as initial for single-frame renderers (screenshot tests, previews)
    val mainState by stateSource.collectAsState(
        initial = (stateSource as? StateFlow)?.value ?: AppsWorkspaceViewModel.State.Initializing
    )
    val state = mainState as? AppsWorkspaceViewModel.State.Ready ?: return

    val paneInsets = design.paneInsets()
    val navBarInset = paneInsets.bottom
    val statusBarInset = paneInsets.top

    AppsDialogHost(
        dialogState = state.dialogState,
        filterConfig = state.filterConfig,
        onDismiss = { onPageAction(AppsPageAction.Dialog.Dismiss) },
        onAction = { onPageAction(AppsPageAction.ActionBarClick(it)) },
        onFilterApply = { onPageAction(AppsPageAction.Dialog.ApplyFilter(it)) },
        onSortApply = { onPageAction(AppsPageAction.Dialog.ApplySort(it)) },
        onConfirmEnable = { onPageAction(AppsPageAction.Dialog.ConfirmEnable(it)) },
        onConfirmDisable = { onPageAction(AppsPageAction.Dialog.ConfirmDisable(it)) },
        onConfirmUninstall = { onPageAction(AppsPageAction.Dialog.ConfirmUninstall(it)) },
        onConfirmClearData = { onPageAction(AppsPageAction.Dialog.ConfirmClearData(it)) },
        onOpenSizeSetup = { onPageAction(AppsPageAction.Dialog.OpenSizeSetup) },
        topInset = statusBarInset,
        bottomInset = navBarInset,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsWorkspaceOverlaysSortOptionsPreview() {
    AppsWorkspaceOverlays(
        stateSource = flowOf(
            AppsWorkspaceViewModel.State.Ready(
                dialogState = AppsDialogState.SortOptions(
                    currentSortSettings = SortSettings(),
                    sizesAvailable = true,
                ),
            )
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsWorkspaceOverlaysSortOptionsNoUsageAccessPreview() {
    AppsWorkspaceOverlays(
        stateSource = flowOf(
            AppsWorkspaceViewModel.State.Ready(
                dialogState = AppsDialogState.SortOptions(
                    currentSortSettings = SortSettings(mode = SortSettings.Mode.SIZE),
                    sizesAvailable = false,
                ),
            )
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsWorkspaceOverlaysConfirmUninstallPreview() {
    AppsWorkspaceOverlays(
        stateSource = flowOf(
            AppsWorkspaceViewModel.State.Ready(
                dialogState = AppsDialogState.ConfirmUninstall(
                    apps = listOf(AppsMockDataProvider.createMockAppItem()),
                ),
            )
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsWorkspaceOverlaysFilterPreview() {
    AppsWorkspaceOverlays(
        stateSource = flowOf(
            AppsWorkspaceViewModel.State.Ready(
                filterConfig = TagFilterConfig(),
                dialogState = AppsDialogState.FilterOptions(availableTags = emptyList()),
            )
        ),
    )
}
