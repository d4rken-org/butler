package eu.darken.butler.developer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerChip
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.OnValueChange
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.navigation.NavigationEventHandler
import androidx.compose.runtime.collectAsState
import eu.darken.butler.developer.R
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.DeveloperTab
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.Factory
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarContentPadding
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.operations.OperationsDisplayState
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.OptionsState
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.State
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.StorageVolumeInfo
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.SystemInfo
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.TargetPathInfo
import eu.darken.butler.developer.ui.DeveloperWorkspaceViewModel.TestDataState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.common.CutoutAwareFlowRow
import eu.darken.butler.workspace.ui.common.CutoutCard
import eu.darken.butler.workspace.ui.common.CutoutCardDefaults
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import eu.darken.butler.workspace.ui.operations.bar.OperationsBarAction
import eu.darken.butler.workspace.ui.operations.bar.WorkspaceOperationsFloatingBar

@Composable
fun DeveloperWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: DeveloperWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: Factory -> factory.create(id = id) }
    ),
) {
    NavigationEventHandler(vm)

    // Handle share intent events
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.shareIntentEvent.collect { intent ->
            context.startActivity(intent)
        }
    }

    val state by vm.state.collectAsState(initial = null)
    val operationsStateRaw by vm.operations.collectAsState(initial = null)
    val operationsState = operationsStateRaw ?: OperationsDisplayState()

    var operationDialogState by remember { mutableStateOf<OperationDialogState>(OperationDialogState.None) }

    // Calculate nav bar inset for bottom sheets
    val paneInsets = design.paneInsets()
    val navBarInset = paneInsets.bottom
    val statusBarInset = paneInsets.top

    state?.let { state ->
        DeveloperWorkspacePage(
            workspaceId = id,
            design = design,
            state = state,
            operationsState = operationsState,
            onTabSelected = { vm.selectTab(it) },
            onToggleLogPause = { vm.toggleLogPause() },
            onClearLogs = { vm.clearLogs() },
            onAddPath = { vm.openPathPicker() },
            onRemovePath = { vm.removePath(it) },
            onLargeFilesToggled = { vm.toggleLargeFiles(it) },
            onNestedStructureToggled = { vm.toggleNestedStructure(it) },
            onTextFilesToggled = { vm.toggleTextFiles(it) },
            onGenerateTestData = { vm.generateTestData() },
            onGenerateTestHistory = { vm.generateTestHistory() },
            onToggleDebugMode = { vm.toggleDebugMode(it) },
            onToggleTraceMode = { vm.toggleTraceMode(it) },
            onToggleFloatingLog = { vm.toggleFloatingLog(it) },
            onTestRoot = { vm.testRoot() },
            onTestShizuku = { vm.testShizuku() },
            onHideDeveloperMode = { vm.hideDeveloperMode() },
            onRequestCancelOperation = { vm.requestCancelOperation(it) },
            onDismissOperation = { vm.dismissOperation(it) },
            onClearCompletedOperations = { vm.clearCompletedOperations() },
            onShowOperationDetails = { operationId ->
                operationDialogState = OperationDialogState.OperationDetails(operationId)
            },
        )

        OperationDialogHost(
            dialogState = operationDialogState,
            operations = operationsState.operations,
            onDismissDialog = { operationDialogState = OperationDialogState.None },
            onCancelOperation = { operationId ->
                operationDialogState = OperationDialogState.None
                vm.cancelOperation(operationId)
            },
            onShareError = { vm.shareOperationError(it) },
            onHandleIssue = {},
            topInset = statusBarInset,
            bottomInset = navBarInset,
        )
    }
}

@Composable
fun DeveloperWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    state: State,
    operationsState: OperationsDisplayState = OperationsDisplayState(),
    onTabSelected: (DeveloperTab) -> Unit = {},
    onToggleLogPause: () -> Unit = {},
    onClearLogs: () -> Unit = {},
    onAddPath: () -> Unit = {},
    onRemovePath: (APath<*>) -> Unit = {},
    onLargeFilesToggled: (Boolean) -> Unit = {},
    onNestedStructureToggled: (Boolean) -> Unit = {},
    onTextFilesToggled: (Boolean) -> Unit = {},
    onGenerateTestData: () -> Unit = {},
    onGenerateTestHistory: () -> Unit = {},
    onToggleDebugMode: (Boolean) -> Unit = {},
    onToggleTraceMode: (Boolean) -> Unit = {},
    onToggleFloatingLog: (Boolean) -> Unit = {},
    onTestRoot: () -> Unit = {},
    onTestShizuku: () -> Unit = {},
    onHideDeveloperMode: () -> Unit = {},
    onRequestCancelOperation: (Operation.Id) -> Unit = {},
    onDismissOperation: (Operation.Id) -> Unit = {},
    onClearCompletedOperations: () -> Unit = {},
    onShowOperationDetails: (Operation.Id) -> Unit = {},
) {
    val paneInsets = design.paneInsets()
    val statusBarInset = paneInsets.top

    val bottomBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.BOTTOM,
        workspaceId = workspaceId,
        design = design,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        estimatedContentPadding = 80.dp,
    )

    val contentPadding = rememberFloatingBarContentPadding(bottomStackState = bottomBarStackState)

    // A different tab is fresh content; reset scroll-collapse so the bar doesn't stay hidden over it.
    // Guarded on the transition: firing on initial composition would undo the restored collapse state.
    OnValueChange(state.selectedTab) { _, _ -> bottomBarStackState.resetScrollCollapse() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // Floating header card with tabs and workspace button
        CutoutCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBarInset + 12.dp, bottom = 12.dp)
                .padding(horizontal = 16.dp),
            cutoutContent = if (design.isSingle) {
                {
                    WorkspaceButton(
                        buttonSize = WorkspaceButtonDefaults.sizeCompact,
                        currentWorkspaceId = workspaceId,
                    )
                }
            } else null,
            contentPadding = CutoutCardDefaults.contentPadding(8.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            CutoutAwareFlowRow(
                modifier = Modifier.fillMaxWidth(),
                cutoutWidth = cutoutWidth,
                cutoutHeight = cutoutHeight,
                horizontalSpacing = 8.dp,
                verticalSpacing = 4.dp,
            ) {
                DeveloperTab.entries.forEach { tab ->
                    ButlerChip(
                        label = when (tab) {
                            DeveloperTab.SYSTEM -> stringResource(R.string.developer_tab_system)
                            DeveloperTab.OPTIONS -> stringResource(R.string.developer_tab_options)
                            DeveloperTab.LOGS -> stringResource(R.string.developer_tab_logs)
                            DeveloperTab.TEST_DATA -> stringResource(R.string.developer_tab_testdata)
                        },
                        selected = state.selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                    )
                }
            }
        }

        // Content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(bottomBarStackState.nestedScrollConnection)
                .padding(horizontal = WorkspacePaddings.ContentHorizontal)
        ) {
            when (state.selectedTab) {
                DeveloperTab.SYSTEM -> SystemInfoSection(
                    systemInfo = state.systemInfo,
                    contentPadding = contentPadding,
                )
                DeveloperTab.OPTIONS -> OptionsSection(
                    optionsState = state.optionsState,
                    contentPadding = contentPadding,
                    onToggleDebugMode = onToggleDebugMode,
                    onToggleTraceMode = onToggleTraceMode,
                    onToggleFloatingLog = onToggleFloatingLog,
                    onTestRoot = onTestRoot,
                    onTestShizuku = onTestShizuku,
                    onHideDeveloperMode = onHideDeveloperMode,
                )
                DeveloperTab.LOGS -> LogsSection(
                    logs = state.logLines,
                    isPaused = state.isLogPaused,
                    contentPadding = contentPadding,
                    onTogglePause = onToggleLogPause,
                    onClear = onClearLogs,
                )
                DeveloperTab.TEST_DATA -> TestDataSection(
                    testDataState = state.testDataState,
                    contentPadding = contentPadding,
                    onAddPath = onAddPath,
                    onRemovePath = onRemovePath,
                    onLargeFilesToggled = onLargeFilesToggled,
                    onNestedStructureToggled = onNestedStructureToggled,
                    onTextFilesToggled = onTextFilesToggled,
                    onGenerateTestData = onGenerateTestData,
                    onGenerateTestHistory = onGenerateTestHistory,
                )
            }
        }
        }

        // Operations bar at bottom
        FloatingBarStack(
            state = bottomBarStackState,
            position = BarPosition.BOTTOM,
            modifier = Modifier.align(Alignment.BottomCenter),
            bars = {
                WorkspaceOperationsFloatingBar(
                    key = DeveloperBarKeys.OPERATIONS,
                    operations = operationsState.operations,
                    onAction = { action ->
                        when (action) {
                            is OperationsBarAction.RequestCancel -> onRequestCancelOperation(action.id)
                            is OperationsBarAction.Dismiss -> onDismissOperation(action.id)
                            // No conflict sheet in this workspace, so a waiting operation opens details,
                            // which is what the bar did before it moved onto the shared stack.
                            is OperationsBarAction.ShowConflict -> onShowOperationDetails(action.id)
                            is OperationsBarAction.ShowDetails -> onShowOperationDetails(action.id)
                            OperationsBarAction.ClearCompleted -> onClearCompletedOperations()
                        }
                    },
                )
            },
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun DeveloperWorkspacePagePreview() {
    val workspaceId = Workspace.Id()
    DeveloperWorkspacePage(
        workspaceId = workspaceId,
        state = State(
            id = workspaceId,
            selectedTab = DeveloperTab.SYSTEM,
            systemInfo = SystemInfo(
                deviceModel = "Pixel 8 Pro",
                deviceManufacturer = "Google",
                apiLevel = 34,
                versionName = "1.0.0-dev",
                versionCode = 100,
                flavor = "FOSS",
                buildType = "DEV",
                gitSha = "abc123",
                memoryAvailable = "4.2 GB",
                memoryTotal = "8.0 GB",
                storageVolumes = listOf(
                    StorageVolumeInfo(
                        name = "Internal Storage",
                        path = "/storage/emulated/0",
                        freeSpace = "64 GB",
                        totalSpace = "128 GB",
                    )
                ),
            ),
            logLines = emptyList(),
            isLogPaused = false,
            testDataState = TestDataState(
                targetPaths = listOf(
                    TargetPathInfo(
                        path = LocalPath.build("/storage/emulated/0"),
                        displayPath = "/storage/emulated/0",
                    ),
                ),
                largeFilesEnabled = false,
                nestedStructureEnabled = false,
                textFilesEnabled = true,
                canGenerate = true,
            ),
            optionsState = OptionsState(
                isDebugMode = true,
                isTraceMode = false,
                isFloatingLogEnabled = false,
                rootTestResult = null,
                isRootTesting = false,
                shizukuTestResult = null,
                isShizukuTesting = false,
                canHideDeveloperMode = false,
            ),
        ),
    )
}
