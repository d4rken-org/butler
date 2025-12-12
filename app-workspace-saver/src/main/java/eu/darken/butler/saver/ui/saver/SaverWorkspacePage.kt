package eu.darken.butler.saver.ui.saver

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.saver.R
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.saver.core.SaverWorkspace
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Clock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Save
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.saver.core.operations.SaveFilesReport
import eu.darken.butler.workspace.core.operations.Operation

@Composable
fun SaverWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: SaverWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: SaverWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm, workspaceButtonVm)

    SaverWorkspacePage(
        workspaceId = id,
        design = design,
        stateSource = vm.state,
        workspaceButtonStateSource = workspaceButtonVm.state,
        vm = vm,
        workspaceActionHandler = workspaceButtonVm,
    )
}

@Composable
private fun SaverWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign,
    stateSource: Flow<SaverWorkspaceViewModel.State>,
    workspaceButtonStateSource: Flow<WorkspaceButtonViewModel.State?>,
    vm: SaverWorkspaceViewModel? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
) {
    val state by stateSource.collectAsState(
        initial = SaverWorkspaceViewModel.State()
    )
    val workspaceButtonState by workspaceButtonStateSource.collectAsState(null)

    // Operation dialog state
    var operationDialogState by remember { mutableStateOf<OperationDialogState>(OperationDialogState.None) }

    // Issue state observation
    val issueState by (vm?.issueState?.collectAsState(initial = null) ?: remember { mutableStateOf(null) })
    var showIssueSheet by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm?.showIssueSheetEvent?.collect { showIssueSheet = true }
    }

    // Auto-dismiss issue sheet when issue is resolved/cancelled
    LaunchedEffect(issueState) {
        if (issueState == null) {
            showIssueSheet = false
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        if (state.isBatchMode) {
            // Batch mode: use non-scrolling Column with weight for file list
            BatchModeContent(
                state = state,
                workspaceButtonState = workspaceButtonState,
                workspaceId = workspaceId,
                workspaceActionHandler = workspaceActionHandler,
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
                state = state,
                workspaceButtonState = workspaceButtonState,
                workspaceId = workspaceId,
                workspaceActionHandler = workspaceActionHandler,
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
            onCopyError = { /* TODO: implement if needed */ },
        )

        // Show issue bottom sheet when needed
        if (issueState != null && showIssueSheet) {
            IssuesBottomSheet(
                issue = issueState!!,
                onResolution = { resolution ->
                    vm?.resolveConflict(resolution)
                    showIssueSheet = false  // Dismiss immediately after resolution
                },
                onDismiss = { showIssueSheet = false },
            )
        }
    }
}

@Composable
private fun SingleFileModeContent(
    state: SaverWorkspaceViewModel.State,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceId: Workspace.Id,
    workspaceActionHandler: WorkspaceActionHandler?,
    vm: SaverWorkspaceViewModel?,
    onOperationClick: (eu.darken.butler.workspace.core.operations.Operation.Id) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        SaverHeader(
            callerLabel = state.callerLabel,
            createdAt = state.createdAt,
            workspaceButtonState = workspaceButtonState,
            workspaceId = workspaceId,
            workspaceActionHandler = workspaceActionHandler,
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
            onClose = { vm?.onClose() },
            onRetry = { vm?.onRetry() },
            onOperationClick = onOperationClick,
        )
    }
}

@Composable
private fun BatchModeContent(
    state: SaverWorkspaceViewModel.State,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceId: Workspace.Id,
    workspaceActionHandler: WorkspaceActionHandler?,
    vm: SaverWorkspaceViewModel?,
    onOperationClick: (eu.darken.butler.workspace.core.operations.Operation.Id) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        SaverHeader(
            callerLabel = state.callerLabel,
            createdAt = state.createdAt,
            workspaceButtonState = workspaceButtonState,
            workspaceId = workspaceId,
            workspaceActionHandler = workspaceActionHandler,
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
            onClose = { vm?.onClose() },
            onRetry = { vm?.onRetry() },
            onOperationClick = onOperationClick,
        )
    }
}

@Preview2
@Composable
private fun SaverWorkspacePageSingleFilePreview() {
    PreviewWrapper {
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
            workspaceButtonStateSource = flowOf(null),
        )
    }
}

@Preview2
@Composable
private fun SaverWorkspacePageBatchModePreview() {
    PreviewWrapper {
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
            workspaceButtonStateSource = flowOf(null),
        )
    }
}

@Preview2
@Composable
private fun SaverWorkspacePageWithDestinationPreview() {
    PreviewWrapper {
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
            workspaceButtonStateSource = flowOf(null),
        )
    }
}

@Preview2
@Composable
private fun SaverWorkspacePageInaccessiblePreview() {
    PreviewWrapper {
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
            workspaceButtonStateSource = flowOf(null),
        )
    }
}