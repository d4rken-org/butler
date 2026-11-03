package eu.darken.butler.apps.ui.apps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogHost
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogState
import eu.darken.butler.apps.ui.apps.preview.AppsMockDataProvider
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.collectAsStateCurrentOr
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.openPrivacyPolicy
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.error.ErrorShareConsentDialog
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.workspace.ui.operations.bar.OperationsBarAction
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import kotlinx.coroutines.flow.Flow
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
    val operationsState by vm.operationsUi.operations.collectAsState()
    val operationDialogState by vm.operationsUi.dialogState.collectAsState()
    val issue by vm.operationsUi.issue.collectAsState()
    val pendingErrorShare by vm.pendingErrorShare.collectAsState()

    AppsWorkspaceOverlays(
        design = design,
        stateSource = vm.state,
        operationsState = operationsState,
        operationDialogState = operationDialogState,
        issue = issue,
        showErrorShareConsent = pendingErrorShare != null,
        onPageAction = vm::onPageAction,
        onDismissOperationDialog = { vm.operationsUi.dismissDialog() },
        onShareOperationError = { vm.operationsUi.shareError(it) },
        onHandleIssue = { vm.operationsUi.onBarAction(OperationsBarAction.ShowConflict(it)) },
        onShowInHistory = { vm.operationsUi.showInHistory(it) },
        onDismissIssue = { vm.operationsUi.dismissIssue() },
        onConfirmErrorShare = { vm.confirmErrorShare() },
        onDismissErrorShare = { vm.dismissErrorShare() },
    )

    // Last on purpose: layers stack in composition order, so an error raised while one of
    // this page's own dialogs is up lands on top of it instead of underneath.
    ErrorEventHandler(vm)
}

@Composable
fun AppsWorkspaceOverlays(
    design: WorkspaceDesign = WorkspaceDesign(),
    stateSource: Flow<AppsWorkspaceViewModel.State>,
    operationsState: OperationsDisplayState = OperationsDisplayState(),
    operationDialogState: OperationDialogState = OperationDialogState.None,
    issue: Issue? = null,
    showErrorShareConsent: Boolean = false,
    onPageAction: (AppsPageAction) -> Unit = {},
    onDismissOperationDialog: () -> Unit = {},
    onShareOperationError: (Operation.Id) -> Unit = {},
    onHandleIssue: (Operation.Id) -> Unit = {},
    onShowInHistory: (Operation.Id) -> Unit = {},
    onDismissIssue: () -> Unit = {},
    onConfirmErrorShare: () -> Unit = {},
    onDismissErrorShare: () -> Unit = {},
) {
    val paneInsets = design.paneInsets()
    val navBarInset = paneInsets.bottom
    val statusBarInset = paneInsets.top

    OperationDialogHost(
        dialogState = operationDialogState,
        operations = operationsState.operations,
        onDismissDialog = onDismissOperationDialog,
        // Nothing a package action does can be interrupted, so the sheet offers no cancel.
        onCancelOperation = null,
        onShareError = onShareOperationError,
        onHandleIssue = onHandleIssue,
        onShowInHistory = onShowInHistory,
        historyEnabled = operationsState.historyEnabled,
        topInset = statusBarInset,
        bottomInset = navBarInset,
    )

    issue?.let {
        IssuesBottomSheet(
            issue = it,
            // The answer belongs to Android's own dialog; the operation leaves Waiting once that
            // dialog reports.
            onResolution = {},
            onDismiss = onDismissIssue,
            topInset = statusBarInset,
            bottomInset = navBarInset,
        )
    }

    if (showErrorShareConsent) {
        val context = LocalContext.current
        ErrorShareConsentDialog(
            onConfirm = onConfirmErrorShare,
            onDismiss = onDismissErrorShare,
            onPrivacyPolicy = { openPrivacyPolicy(context) },
        )
    }

    // Below the operation overlays: those belong to the whole tab, while everything from here on
    // needs a Ready state to describe.
    val mainState by stateSource.collectAsStateCurrentOr(AppsWorkspaceViewModel.State.Initializing)
    val state = mainState as? AppsWorkspaceViewModel.State.Ready ?: return

    AppsDialogHost(
        dialogState = state.dialogState,
        filterConfig = state.filterConfig,
        viewStyle = state.viewStyle,
        onDismiss = { onPageAction(AppsPageAction.Dialog.Dismiss) },
        onAction = { onPageAction(AppsPageAction.ActionBarClick(it)) },
        onFilterApply = { onPageAction(AppsPageAction.Dialog.ApplyFilter(it)) },
        onSortApply = { onPageAction(AppsPageAction.Dialog.ApplySort(it)) },
        onViewStyleApplyToTab = { onPageAction(AppsPageAction.ViewStyle.ApplyToTab(it)) },
        onViewStyleSetAsDefault = { onPageAction(AppsPageAction.ViewStyle.SetAsDefault(it)) },
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
