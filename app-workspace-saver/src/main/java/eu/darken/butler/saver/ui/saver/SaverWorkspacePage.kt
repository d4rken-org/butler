package eu.darken.butler.saver.ui.saver

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.saver.R
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.saver.core.SaverWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun SaverWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: SaverWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: SaverWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.shareIntentEvent.collect { intent ->
            context.startActivity(intent)
        }
    }

    SaverWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        vm = vm,
    )
}

@Composable
internal fun SaverWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    stateSource: Flow<SaverWorkspaceViewModel.State>,
    vm: SaverWorkspaceViewModel? = null,
) {
    val state by stateSource.collectAsState(
        initial = SaverWorkspaceViewModel.State()
    )

    // Operation dialog state
    var operationDialogState by remember { mutableStateOf<OperationDialogState>(OperationDialogState.None) }

    // Issue state observation. showIssueSheet is durable VM state so a notification-driven open
    // survives recomposition / late collector subscription.
    val issueState by (vm?.issueState?.collectAsState(initial = null) ?: remember { mutableStateOf(null) })
    val showIssueSheet by (vm?.showIssueSheet?.collectAsState() ?: remember { mutableStateOf(false) })

    // Navigation bar inset for bottom sheets
    val density = LocalDensity.current
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else 0.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            // As a full-screen modal the page renders into a transparent Dialog, so it must paint
            // its own opaque background; as a tab this simply matches the container surface.
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (state.isBatchMode) {
            // Batch mode: use non-scrolling Column with weight for file list
            BatchModeContent(
                design = design,
                state = state,
                workspaceId = workspaceId,
                vm = vm,
                onOperationClick = { operationId ->
                    val op = state.operationDisplay
                    if (op?.id == operationId && op.state is OperationDisplay.State.Waiting) {
                        vm?.showConflictSheet(operationId)
                    } else {
                        operationDialogState = OperationDialogState.OperationDetails(operationId)
                    }
                },
            )
        } else {
            // Single file mode: scrollable Column
            SingleFileModeContent(
                design = design,
                state = state,
                workspaceId = workspaceId,
                vm = vm,
                onOperationClick = { operationId ->
                    val op = state.operationDisplay
                    if (op?.id == operationId && op.state is OperationDisplay.State.Waiting) {
                        vm?.showConflictSheet(operationId)
                    } else {
                        operationDialogState = OperationDialogState.OperationDetails(operationId)
                    }
                },
            )
        }

        OperationDialogHost(
            dialogState = operationDialogState,
            operations = listOfNotNull(state.operationDisplay),
            onDismissDialog = { operationDialogState = OperationDialogState.None },
            onCancelOperation = { operationDialogState = OperationDialogState.None },
            onShareError = { vm?.shareError(it) },
            onHandleIssue = { operationId ->
                vm?.showConflictSheet(operationId)
            },
        )

        // Show issue bottom sheet when needed
        if (issueState != null && showIssueSheet) {
            IssuesBottomSheet(
                issue = issueState!!,
                onResolution = { resolution ->
                    vm?.resolveConflict(resolution)
                },
                onDismiss = { vm?.dismissConflictSheet() },
                bottomInset = navBarInset,
            )
        }
    }
}

@Composable
private fun SingleFileModeContent(
    design: WorkspaceDesign,
    state: SaverWorkspaceViewModel.State,
    workspaceId: Workspace.Id,
    vm: SaverWorkspaceViewModel?,
    onOperationClick: (eu.darken.butler.workspace.core.operations.Operation.Id) -> Unit,
) {
    // System bar insets for edge-to-edge (based on pane edges)
    val density = LocalDensity.current
    val statusBarInset = if (design.paneEdges.touchesTop) {
        with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    } else 0.dp
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else 0.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = statusBarInset + 16.dp, bottom = navBarInset + 16.dp),
    ) {
        SaverHeader(
            callerLabel = state.callerLabel,
            callerPackage = state.callerPackage,
            createdAt = state.createdAt,
            workspaceId = workspaceId,
            isModal = state.isModal,
            onBack = { vm?.onClose() },
        )

        Spacer(modifier = Modifier.height(16.dp))

        val sourceInfo = state.sourceInfos.firstOrNull()
        FilePreviewCard(
            modifier = Modifier.weight(1f),
            sourceInfo = sourceInfo,
        )

        Spacer(modifier = Modifier.height(16.dp))

        SourceFileCard(sourceInfo = sourceInfo)

        Spacer(modifier = Modifier.height(16.dp))

        // Show destination picker in Idle or Error state (for retry)
        val showDestinationPicker = state.saveState is SaverWorkspace.SaveState.Idle ||
            state.saveState is SaverWorkspace.SaveState.Error
        if (showDestinationPicker) {
            if (state.hasInaccessibleFiles) {
                WarningCard(
                    message = pluralStringResource(
                        R.plurals.saver_source_expired_warning,
                        state.inaccessibleFileCount,
                        state.inaccessibleFileCount,
                    ),
                    onRetry = { vm?.onRefreshAccessibility() },
                    onClose = { vm?.onClose() },
                )
            } else {
                DestinationCard(
                    destination = state.destination,
                    filename = state.filename,
                    isBatchMode = false,
                    onClick = { vm?.onPickDestination() },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        SaverActionArea(
            state = state,
            operationDisplay = state.operationDisplay,
            onSave = { vm?.onSave() },
            onOpenSaved = { vm?.onOpenSavedFile() },
            onSaveAgain = { vm?.onSaveAgain() },
            onFinishApp = { vm?.onFinishApp() },
            onRetry = { vm?.onRetry() },
            onOperationClick = onOperationClick,
            isModal = state.isModal,
            onDone = { vm?.onClose() },
        )
    }
}

@Composable
private fun BatchModeContent(
    design: WorkspaceDesign,
    state: SaverWorkspaceViewModel.State,
    workspaceId: Workspace.Id,
    vm: SaverWorkspaceViewModel?,
    onOperationClick: (eu.darken.butler.workspace.core.operations.Operation.Id) -> Unit,
) {
    // System bar insets for edge-to-edge (based on pane edges)
    val density = LocalDensity.current
    val statusBarInset = if (design.paneEdges.touchesTop) {
        with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    } else 0.dp
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else 0.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = statusBarInset + 16.dp, bottom = navBarInset + 16.dp),
    ) {
        SaverHeader(
            callerLabel = state.callerLabel,
            callerPackage = state.callerPackage,
            createdAt = state.createdAt,
            workspaceId = workspaceId,
            isModal = state.isModal,
            onBack = { vm?.onClose() },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // File list expands to fill available space
        SourceFilesList(
            modifier = Modifier.weight(1f),
            sourceInfos = state.sourceInfos,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Show destination picker in Idle or Error state (for retry)
        val showDestinationPicker = state.saveState is SaverWorkspace.SaveState.Idle ||
            state.saveState is SaverWorkspace.SaveState.Error
        if (showDestinationPicker) {
            if (state.hasInaccessibleFiles) {
                WarningCard(
                    message = pluralStringResource(
                        R.plurals.saver_source_expired_warning,
                        state.inaccessibleFileCount,
                        state.inaccessibleFileCount,
                    ),
                    onRetry = { vm?.onRefreshAccessibility() },
                    onClose = { vm?.onClose() },
                )
            } else {
                DestinationCard(
                    destination = state.destination,
                    filename = null,
                    isBatchMode = true,
                    onClick = { vm?.onPickDestination() },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        SaverActionArea(
            state = state,
            operationDisplay = state.operationDisplay,
            onSave = { vm?.onSave() },
            onOpenSaved = { vm?.onOpenSavedFile() },
            onSaveAgain = { vm?.onSaveAgain() },
            onFinishApp = { vm?.onFinishApp() },
            onRetry = { vm?.onRetry() },
            onOperationClick = onOperationClick,
            isModal = state.isModal,
            onDone = { vm?.onClose() },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SaverWorkspacePageSingleFilePreview() {
    SaverWorkspacePage(
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        stateSource = flowOf(
            SaverWorkspaceViewModel.State(
                sourceInfos = listOf(
                    ContentUriHelper.SourceInfo(
                        uri = "content://example/image.jpg".toUri(),
                        displayName = "vacation_photo.jpg",
                        mimeType = "image/jpeg",
                        size = 3_500_000,
                        isAccessible = true,
                    )
                ),
                filename = "vacation_photo.jpg",
                callerLabel = "Telegram",
            )
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SaverWorkspacePageBatchModePreview() {
    SaverWorkspacePage(
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        stateSource = flowOf(
            SaverWorkspaceViewModel.State(
                sourceInfos = listOf(
                    ContentUriHelper.SourceInfo(
                        uri = "content://example/image1.jpg".toUri(),
                        displayName = "photo_001.jpg",
                        mimeType = "image/jpeg",
                        size = 3_500_000,
                        isAccessible = true,
                    ),
                    ContentUriHelper.SourceInfo(
                        uri = "content://example/image2.jpg".toUri(),
                        displayName = "photo_002.jpg",
                        mimeType = "image/jpeg",
                        size = 2_800_000,
                        isAccessible = true,
                    ),
                    ContentUriHelper.SourceInfo(
                        uri = "content://example/image3.jpg".toUri(),
                        displayName = "photo_003.jpg",
                        mimeType = "image/jpeg",
                        size = 4_200_000,
                        isAccessible = true,
                    ),
                ),
                destination = LocalPath.build("/sdcard/Download"),
                callerLabel = "Gallery",
            )
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SaverWorkspacePageWithDestinationPreview() {
    SaverWorkspacePage(
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        stateSource = flowOf(
            SaverWorkspaceViewModel.State(
                sourceInfos = listOf(
                    ContentUriHelper.SourceInfo(
                        uri = "content://example/document.pdf".toUri(),
                        displayName = "report.pdf",
                        mimeType = "application/pdf",
                        size = 1_200_000,
                        isAccessible = true,
                    )
                ),
                filename = "report.pdf",
                destination = LocalPath.build("/sdcard/Download"),
                callerLabel = "Email",
            )
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SaverWorkspacePageInaccessiblePreview() {
    SaverWorkspacePage(
        workspaceId = Workspace.Id(),
        design = WorkspaceDesign(),
        stateSource = flowOf(
            SaverWorkspaceViewModel.State(
                sourceInfos = listOf(
                    ContentUriHelper.SourceInfo(
                        uri = "content://example/expired.jpg".toUri(),
                        displayName = "expired_file.jpg",
                        mimeType = "image/jpeg",
                        size = 1_000_000,
                        isAccessible = false,
                    )
                ),
                filename = "expired_file.jpg",
                callerLabel = "Telegram",
            )
        ),
    )
}