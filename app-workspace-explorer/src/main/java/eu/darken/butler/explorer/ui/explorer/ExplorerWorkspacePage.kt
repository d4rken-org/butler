package eu.darken.butler.explorer.ui.explorer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.rememberDelayedState
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
import eu.darken.butler.explorer.ui.explorer.dialogs.AddDeviceStorageSheet
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogHost
import eu.darken.butler.explorer.ui.explorer.elements.PermissionRequestCard
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider
import eu.darken.butler.explorer.ui.explorer.util.ExplorerSelectionState
import eu.darken.butler.explorer.ui.explorer.util.OpenDocumentTreeWithIntent
import eu.darken.butler.explorer.ui.explorer.util.explorerKeyboardShortcuts
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.issues.IssuesBottomSheet
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.operations.details.CancelOperationConfirmationDialog
import eu.darken.butler.workspace.ui.operations.details.OperationDialogHost
import eu.darken.butler.workspace.ui.operations.details.OperationDialogState
import eu.darken.butler.workspace.ui.states.WorkspaceErrorContent
import eu.darken.butler.workspace.ui.states.WorkspaceInitializingContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun ExplorerWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    mainStateSource: Flow<ExplorerWorkspaceViewModel.State>,
    operationsStateSource: Flow<ExplorerWorkspaceViewModel.OperationsState>,
    clipboardStateSource: Flow<ExplorerWorkspaceViewModel.ClipboardState>,
    workspaceStateSource: Flow<WorkspaceButtonViewModel.State?>,
    vm: ExplorerWorkspaceViewModel? = null,
    workspaceActionHandler: WorkspaceActionHandler? = null,
    initialOperationsExpanded: Boolean = false,
    initialClipboardExpanded: Boolean = false,
) {
    val mainState by mainStateSource.collectAsState(ExplorerWorkspaceViewModel.State.Initializing)
    val operationsState by operationsStateSource.collectAsState(ExplorerWorkspaceViewModel.OperationsState())
    val clipboardState by clipboardStateSource.collectAsState(ExplorerWorkspaceViewModel.ClipboardState())
    val workspaceButtonState by workspaceStateSource.collectAsState(null)
    val isWorkspaceFocused = LocalWorkspaceFocused.current

    val topBarStackState = rememberFloatingBarStackState(
        position = BarPosition.TOP,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        includeSystemBarInset = design.paneEdges.touchesTop,
    )
    val bottomBarStackState = rememberFloatingBarStackState(
        position = BarPosition.BOTTOM,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 16.dp,
        includeSystemBarInset = design.paneEdges.touchesBottom,
    )
    val density = LocalDensity.current
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else 0.dp

    // Observe conflict state
    val issueState by (vm?.issueState?.collectAsState() ?: remember { mutableStateOf(null) })
    var showIssueSheet by remember { mutableStateOf(false) }

    // Listen for requests to show conflict sheet (manual trigger only)
    LaunchedEffect(vm) {
        vm?.showIssueSheetEvent?.collect { showIssueSheet = true }
    }

    // Operation dialog state
    var operationDialogState by remember { mutableStateOf<OperationDialogState>(OperationDialogState.None) }
    var showCancelConfirmation by remember { mutableStateOf<Operation.Id?>(null) }

    // Progress indicator delay state - shows after 200ms to avoid flickering
    val readyState = mainState as? ExplorerWorkspaceViewModel.State.Ready
    val showProgress = rememberDelayedState(readyState?.progress, delayMs = 200)

    // Grid columns for keyboard navigation (approximate for adaptive grid)
    val gridColumns = 3

    // Keyboard shortcuts need Ready state properties - use defaults when not ready
    val keyboardState = readyState ?: ExplorerWorkspaceViewModel.State.Ready()
    val focusedItem = keyboardState.focusedItemIndex?.let { keyboardState.items?.getOrNull(it) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .explorerKeyboardShortcuts(
                availableActions = keyboardState.availableActions,
                clipboardEntries = clipboardState.entries,
                selectedItems = keyboardState.selectionState.selectedItems,
                focusedItem = focusedItem,
                viewStyle = keyboardState.viewStyle,
                gridColumns = gridColumns,
                trashEnabled = keyboardState.trashEnabled,
                enabled = isWorkspaceFocused && readyState != null,
                onExecuteAction = { vm?.executeAction(it) },
                onPaste = { vm?.pasteClipboard(it) },
                onSelectAll = { vm?.selectAll() },
                onClearSelection = { vm?.clearSelection() },
                onClearFocus = { vm?.clearFocus() },
                onNavigateToItem = { vm?.navigate(it) },
                onGoBack = { vm?.goBack() },
                onMoveFocusUp = { vm?.moveFocusUp() },
                onMoveFocusDown = { vm?.moveFocusDown() },
                onMoveFocusLeft = { vm?.moveFocusLeft(gridColumns) },
                onMoveFocusRight = { vm?.moveFocusRight(gridColumns) },
                onMoveFocusToFirst = { vm?.moveFocusToFirst() },
                onMoveFocusToLast = { vm?.moveFocusToLast() },
                onActivateFocusedItem = { focusedItem?.let { vm?.navigate(it) } },
                onRenameFocusedItem = { (focusedItem as? ExplorerItem.Lookup)?.let { vm?.renameFile(it) } },
                onDeleteFocusedItem = { vm?.deleteFocusedItem() },
                onPermanentDeleteFocusedItem = {
                    if (keyboardState.selectionState.selectedItems.isNotEmpty()) {
                        vm?.permanentDeleteSelectedItems()
                    } else {
                        vm?.deleteFocusedItem(forcePermDelete = true)
                    }
                },
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Content padding from floating bar stacks
            val topContentPadding = topBarStackState.contentPaddingDp()

            // Main content area - route based on state
            when (val state = mainState) {
                is ExplorerWorkspaceViewModel.State.Error -> {
                    WorkspaceErrorContent(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = topContentPadding),
                        error = state.error,
                        onShareError = { vm?.shareWorkspaceError() },
                        onCloseWorkspace = { vm?.closeWorkspace() },
                    )
                }
                is ExplorerWorkspaceViewModel.State.Initializing -> {
                    WorkspaceInitializingContent(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = topContentPadding),
                    )
                }
                is ExplorerWorkspaceViewModel.State.Ready -> {
                    if (state.setupRequirements.needsAction) {
                        PermissionRequestCard(
                            setupRequirements = state.setupRequirements,
                            onNavigateToSetup = {
                                vm?.navigateToSetup(state.setupRequirements)
                            },
                            onLaunchSAFPicker = { grant ->
                                vm?.launchAndroidDataSAFPicker(grant)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = topContentPadding),
                        )
                    } else {
                        ExplorerReadyContent(
                            state = state,
                            operationsState = operationsState,
                            clipboardState = clipboardState,
                            mainStateSource = mainStateSource,
                            workspaceId = workspaceId,
                            topBarStackState = topBarStackState,
                            bottomBarStackState = bottomBarStackState,
                            design = design,
                            navBarInset = navBarInset,
                            vm = vm,
                            showProgress = showProgress,
                            isWorkspaceFocused = isWorkspaceFocused,
                            onShowOperationDetails = { operationId ->
                                operationDialogState = OperationDialogState.OperationDetails(operationId)
                            },
                            workspaceButtonState = workspaceButtonState,
                            workspaceActionHandler = workspaceActionHandler,
                            safLocationManager = vm?.safLocationManager,
                            initialOperationsExpanded = initialOperationsExpanded,
                            initialClipboardExpanded = initialClipboardExpanded,
                        )
                    }
                }
            }

            // WorkspaceButton for non-ready states (Initializing/Error)
            // When Ready, the toolbar with WorkspaceButton is rendered in ExplorerReadyContent
            if (mainState !is ExplorerWorkspaceViewModel.State.Ready) {
                WorkspaceButton(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = topContentPadding + 8.dp, end = 16.dp),
                    state = workspaceButtonState,
                    buttonSize = 48.dp,
                    currentWorkspaceId = workspaceId,
                    workspaceActionHandler = workspaceActionHandler,
                )
            }

            // Dialogs - stay in parent
            if (readyState != null) {
                ExplorerDialogHost(
                    dialogState = readyState.dialogState,
                    trashEnabled = readyState.trashEnabled,
                    vm = vm,
                    bottomInset = navBarInset,
                )
            }

            OperationDialogHost(
                dialogState = operationDialogState,
                operations = operationsState.operations,
                onDismissDialog = { operationDialogState = OperationDialogState.None },
                onCancelOperation = { operationId ->
                    operationDialogState = OperationDialogState.None
                    showCancelConfirmation = operationId
                },
                onCopyError = { vm?.copyError(it) },
                onHandleIssue = { operationId ->
                    vm?.showConflictSheet(operationId)
                },
            )

            // Show conflict bottom sheet when needed
            if (issueState != null && showIssueSheet) {
                IssuesBottomSheet(
                    issue = issueState!!,
                    onResolution = { resolution -> vm?.resolveConflict(resolution) },
                    onDismiss = { showIssueSheet = false },
                    bottomInset = navBarInset,
                )
            }
        }
    }

    // Show add storage bottom sheet
    val showAddStorageSheet by (vm?.showAddStorageSheet?.collectAsState() ?: remember { mutableStateOf(false) })
    if (showAddStorageSheet) {
        AddDeviceStorageSheet(
            onDismiss = { vm?.dismissAddStorageSheet() },
            onContinue = { vm?.addSAFLocation() },
            bottomInset = navBarInset,
        )
    }

    // Show cancel confirmation dialog when needed
    showCancelConfirmation?.let { operationId ->
        CancelOperationConfirmationDialog(
            onDismiss = { showCancelConfirmation = null },
            onConfirm = {
                vm?.cancelOperation(operationId)
                showCancelConfirmation = null
            }
        )
    }
}

@Composable
fun ExplorerWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: ExplorerWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: ExplorerWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
    workspaceButtonVm: WorkspaceButtonViewModel = hiltViewModel(),
) {
    ErrorEventHandler(vm)
    NavigationEventHandler(vm, workspaceButtonVm)

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // SAF directory picker launcher
    val safPickerLauncher = rememberLauncherForActivityResult(
        OpenDocumentTreeWithIntent()
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                val grant = vm.pendingSAFPickerGrant.first()
                if (grant != null) {
                    // Android/data workaround flow - auto-label
                    vm.handleAndroidDataSAFPickerResult(it, grant)
                } else {
                    // Manual addition flow - prompt for label
                    vm.handleSAFPickerResult(it)
                }
            }
        }
    }

    // Handle SAF picker launch events
    LaunchedEffect(vm) {
        vm.safPickerEvents.collect { intent ->
            safPickerLauncher.launch(intent)
        }
    }

    ExplorerWorkspacePage(
        workspaceId = id,
        design = design,
        mainStateSource = vm.state,
        clipboardStateSource = vm.clipboard,
        operationsStateSource = vm.operations,
        workspaceStateSource = workspaceButtonVm.state,
        vm = vm,
        workspaceActionHandler = workspaceButtonVm,
    )
}

@Preview2
@Composable
private fun ExplorerWorkspacePagePreview() {
    val mockState = ExplorerWorkspaceViewModel.State.Ready(
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/storage/emulated/0"),
            items = MockDataProvider.createAllFileTypes(),
            info = ExplorerLocation.Directory.Info(
                fileCount = 15,
                directoryCount = 5,
                totalSize = 1024L * 1024L * 250L,
                volumeFreeSpace = 1024L * 1024L * 1024L * 50L,
                volumeTotalSpace = 1024L * 1024L * 1024L * 128L,
                isWritable = true,
            ),
            progress = null,
        ),
        breadcrumbs = listOf(
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_home.toCaString(),
                target = ExplorerNavigation.Target.Home
            ),
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_device.toCaString(),
                target = ExplorerNavigation.Target.Device
            ),
            ExplorerBreadcrumb(
                label = "storage".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage"))
            ),
            ExplorerBreadcrumb(
                label = "emulated".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated"))
            ),
            ExplorerBreadcrumb(
                label = "0".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0"))
            )
        ),
        items = MockDataProvider.createAllFileTypes(),
        availableActions = listOf(
            ExplorerAction.Directory.Create(isEnabled = false),
            ExplorerAction.Common.Sort(),
            ExplorerAction.Common.Filter(isEnabled = false),
        ),
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
        )
    }
}

@Preview2
@Composable
private fun ExplorerWorkspacePageEmptyPreview() {
    val mockState = ExplorerWorkspaceViewModel.State.Ready(
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/sdcard/EmptyFolder"),
            items = emptyList(),
            progress = null,
        ),
        breadcrumbs = listOf(
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_home.toCaString(),
                target = ExplorerNavigation.Target.Home
            ),
            ExplorerBreadcrumb(
                label = "sdcard".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard"))
            ),
            ExplorerBreadcrumb(
                label = "EmptyFolder".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/EmptyFolder"))
            ),
        ),
        items = emptyList(),
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
        )
    }
}


@Preview2
@Composable
private fun ExplorerWorkspacePageErrorPreview() {
    val mockState = ExplorerWorkspaceViewModel.State.Ready(
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/permission/denied"),
            items = emptyList(),
            progress = null,
        ),
        breadcrumbs = listOf(
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_home.toCaString(),
                target = ExplorerNavigation.Target.Home
            ),
            ExplorerBreadcrumb(
                label = "permission".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/permission"))
            ),
            ExplorerBreadcrumb(
                label = "denied".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/permission/denied"))
            ),
        ),
        items = emptyList(),
        error = ReadException(
            path = LocalPath.build("/permission/denied")
        )
    )
    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
        )
    }
}

@Preview2
@Composable
private fun ExplorerWorkspacePageWithAllBarsPreview() {
    val mockFileItems = MockDataProvider.createAllFileTypes()
    val mockOperations = MockDataProvider.createMockOperationsState(runningCount = 2, completedCount = 1)
    val mockClipboardEntries = MockDataProvider.createMockClipboardState(copyCount = 2, cutCount = 1)

    val mockState = ExplorerWorkspaceViewModel.State.Ready(
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/storage/emulated/0"),
            items = MockDataProvider.createAllFileTypes(),
            info = ExplorerLocation.Directory.Info(
                fileCount = 25,
                directoryCount = 8,
                totalSize = 1024L * 1024L * 512L,
                volumeFreeSpace = 1024L * 1024L * 1024L * 32L,
                volumeTotalSpace = 1024L * 1024L * 1024L * 128L,
                isWritable = true,
            ),
            progress = null,
        ),
        breadcrumbs = listOf(
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_home.toCaString(),
                target = ExplorerNavigation.Target.Home
            ),
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_device.toCaString(),
                target = ExplorerNavigation.Target.Device
            ),
            ExplorerBreadcrumb(
                label = "0".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/storage/emulated/0"))
            )
        ),
        items = mockFileItems,
        availableActions = listOf(
            ExplorerAction.Directory.Create(isEnabled = true),
            ExplorerAction.Common.Sort(),
            ExplorerAction.Common.Filter(isEnabled = true),
        ),
        selectionState = ExplorerSelectionState(
            selectedItems = setOf(mockFileItems[0], mockFileItems[2]),
            selectableItems = setOf(mockFileItems[0], mockFileItems[2]),
        ),
    )

    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            mainStateSource = flowOf(mockState),
            clipboardStateSource = flowOf(mockClipboardEntries),
            operationsStateSource = flowOf(mockOperations),
            workspaceStateSource = flowOf(null),
            vm = null,
        )
    }
}

@Preview2
@Composable
private fun ExplorerPickerMode_MixedMultiPreview() {
    val mockPickerConfig = PickerConfig(
        selection = PickerConfig.Selection.MixedMulti,
        callerWorkspaceId = Workspace.Id(),
    )

    val mockItems = listOf(
        MockDataProvider.createMockDirectory("Photos", childCount = 234),
        MockDataProvider.createMockDirectory("Videos", childCount = 56),
        MockDataProvider.createMockDirectory("Music", childCount = 189),
        MockDataProvider.createMockRegularFile("vacation.jpg"),
        MockDataProvider.createMockRegularFile("recipe.pdf"),
        MockDataProvider.createMockRegularFile("notes.txt"),
        MockDataProvider.createMockRegularFile("budget.xlsx"),
    )

    val mockState = ExplorerWorkspaceViewModel.State.Ready(
        pickerConfig = mockPickerConfig,
        currentLocation = ExplorerLocation.Directory(
            path = LocalPath.build("/sdcard/Documents"),
            items = mockItems,
            info = ExplorerLocation.Directory.Info(
                fileCount = 4,
                directoryCount = 3,
                totalSize = 1024L * 1024L * 512L,
                volumeFreeSpace = 1024L * 1024L * 1024L * 28L,
                volumeTotalSpace = 1024L * 1024L * 1024L * 128L,
                isWritable = true,
            ),
            progress = null,
        ),
        breadcrumbs = listOf(
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_home.toCaString(),
                target = ExplorerNavigation.Target.Home
            ),
            ExplorerBreadcrumb(
                label = R.string.explorer_navigation_device.toCaString(),
                target = ExplorerNavigation.Target.Device
            ),
            ExplorerBreadcrumb(
                label = "sdcard".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard"))
            ),
            ExplorerBreadcrumb(
                label = "Documents".toCaString(),
                target = ExplorerNavigation.Target.Directory(LocalPath.build("/sdcard/Documents"))
            )
        ),
        items = mockItems,
        selectionState = ExplorerSelectionState(
            selectedItems = setOf(mockItems[0], mockItems[2], mockItems[3], mockItems[5], mockItems[6]),
            selectableItems = mockItems.toSet(),
        ),
    )

    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            design = WorkspaceDesign(),
            mainStateSource = flowOf(mockState),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
            workspaceActionHandler = null,
        )
    }
}

@Preview2
@Composable
private fun ExplorerWorkspacePageInitializingPreview() {
    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            mainStateSource = flowOf(ExplorerWorkspaceViewModel.State.Initializing),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
        )
    }
}

@Preview2
@Composable
private fun ExplorerWorkspacePageFatalErrorPreview() {
    PreviewWrapper {
        ExplorerWorkspacePage(
            workspaceId = Workspace.Id(),
            mainStateSource = flowOf(
                ExplorerWorkspaceViewModel.State.Error(
                    error = RuntimeException("Failed to initialize workspace")
                )
            ),
            workspaceStateSource = flowOf(null),
            clipboardStateSource = flowOf(ExplorerWorkspaceViewModel.ClipboardState()),
            operationsStateSource = flowOf(ExplorerWorkspaceViewModel.OperationsState()),
            vm = null,
        )
    }
}
