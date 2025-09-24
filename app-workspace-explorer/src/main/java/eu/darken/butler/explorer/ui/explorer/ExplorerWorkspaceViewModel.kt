package eu.darken.butler.explorer.ui.explorer

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.datastore.valueBlocking
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerSettings
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.locationId
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.sorting.ExplorerItemSorter
import eu.darken.butler.explorer.ui.explorer.actions.DefaultActionProvider
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemResult
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemType
import eu.darken.butler.explorer.ui.explorer.dialogs.DeleteConfirmationResult
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogEvent
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.RenameResult
import eu.darken.butler.explorer.ui.explorer.dialogs.SortOptionsResult
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withStateUpdates
import eu.darken.butler.workspace.core.permissions.PermissionState
import eu.darken.butler.workspace.ui.operations.OperationDisplay
import eu.darken.butler.workspace.ui.operations.toDisplayModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel(assistedFactory = ExplorerWorkspaceViewModel.Factory::class)
class ExplorerWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    @ApplicationContext private val context: Context,
    dispatchers: DispatcherProvider,
    navController: NavigationController,
    workspaceProvider: WorkspaceProvider,
    private val actionProvider: DefaultActionProvider,
    private val clipboardRepo: ClipboardRepo,
    private val explorerSettings: ExplorerSettings,
    itemSorterFactory: ExplorerItemSorter.Factory,
    private val operationsManager: OperationsManager,
) : ViewModel4(dispatchers, logTag("Explorer", "Workspace", id.shortTag, "Page"), navController) {

    private val selectedItemsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val viewModeFlow = MutableStateFlow(ViewMode.LIST)
    private val dialogStateFlow = MutableStateFlow<ExplorerDialogState>(ExplorerDialogState.None)
    private val conflictStateFlow = MutableStateFlow<PathActionIssue?>(null)
    val conflictState = conflictStateFlow
    private var currentConflictOperationId: Operation.Id? = null

    val dialogEvents = SingleEventFlow<ExplorerDialogEvent>()

    private val workspace: Flow<ExplorerWorkspace?> = workspaceProvider.retrieve(id).map { it as ExplorerWorkspace? }
    private val itemSorter = itemSorterFactory.create(id)
    private val currentSortSettings = MutableStateFlow(explorerSettings.sortSettings.valueBlocking)
    private suspend fun getWorkspace() = workspace.filterNotNull().first()

    private val workspaceState: Flow<ExplorerWorkspace.State> = workspace.flatMapLatest { ws ->
        ws?.current ?: MutableStateFlow(ExplorerWorkspace.State())
    }

    init {
        // Handle dialog events
        launch {
            dialogEvents.collect { event ->
                handleDialogEvent(event)
            }
        }

        // Observe pending conflicts from workspace and update UI state
        launch {
            workspaceState.collect { wsState ->
                val firstConflictEntry = wsState.pendingConflicts.entries.firstOrNull()
                if (firstConflictEntry != null) {
                    val (operationId, awaitingInputState) = firstConflictEntry
                    currentConflictOperationId = operationId
                    conflictStateFlow.value = awaitingInputState.issue
                } else {
                    currentConflictOperationId = null
                    conflictStateFlow.value = null
                }
            }
        }
    }

    enum class ViewMode {
        LIST,
        GRID
    }


    val state = combine(
        workspaceState,
        selectedItemsFlow,
        viewModeFlow,
        clipboardRepo.state,
        dialogStateFlow,
        currentSortSettings,
    ) { wsState, selectedItems, viewMode, clipboard, dialogState, sortSetting ->
        val rawItems = wsState.currentLocation?.items ?: emptyList()
        val items = itemSorter.sortItems(rawItems, sortSetting)

        val selectionState = ExplorerSelectionState(
            selectedItems = selectedItems,
            selectableItems = items.filter { it is ExplorerItem.PathItem }.map { it.id }.toSet(),
        )

        val availableActions = wsState.currentLocation?.let {
            actionProvider.getActions(
                location = it,
                selectionState = selectionState,
            )
        } ?: emptyList()

        State(
            currentLocation = wsState.currentLocation,
            locationId = wsState.currentLocation?.locationId,
            breadcrumbs = wsState.currentBreadcrumbs ?: emptyList(),
            items = items,
            isLoading = wsState.isLoading,
            isLoadingExtended = wsState.isLoadingExtended,
            error = wsState.error,
            selectionState = selectionState,
            viewMode = viewMode,
            canGoBack = wsState.canGoBack,
            canGoForward = wsState.canGoForward,
            availableActions = availableActions,
            dialogState = dialogState,
            clipboardEntries = clipboard.entries,
            permissionState = wsState.currentLocation?.permissionState ?: PermissionState(),
        )
    }.asStateFlow()

    val operations = operationsManager
        .operationsForWorkspace(id)
        .withStateUpdates()
        .map { managedOps ->
            managedOps.map { it.toDisplayModel() }
        }
        .map { displayOps ->
            displayOps.sortedWith(
                compareBy<OperationDisplay> { op ->
                    // Priority: Running > Waiting (was running, needs input) > Queued > Others
                    when (op.state) {
                        is OperationDisplay.State.Running -> 0
                        is OperationDisplay.State.Waiting -> 1  // Higher priority than queued
                        is OperationDisplay.State.Queued -> 2
                        is OperationDisplay.State.Failed -> 3
                        is OperationDisplay.State.Completed -> 4
                        is OperationDisplay.State.Cancelled -> 5
                    }
                }.thenBy { it.startedAt } // Oldest first within each group
            )
        }
        .stateIn(
            scope = vmScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun navigate(item: ExplorerItem) = launch {
        log(tag) { "navigate($item)" }
        when (item) {
            is ExplorerItem.PathItem -> when (item) {
                is ExplorerItem.DirectoryItem -> {
                    getWorkspace().navigate(ExplorerNavigation.Target.Directory(item.lookup.lookedUp))
                    clearSelection()
                }
                is ExplorerItem.FileItem -> {
                    // TODO Open file?
                }
            }
            is ExplorerItem.Shortcut -> {
                getWorkspace().navigate(item.target)
                clearSelection()
            }

        }
    }

    fun navigateToPathString(pathString: String) = launch {
        log(tag) { "navigateToPathString($pathString)" }
        val normalizedPath = pathString.trim()

        when {
            normalizedPath.isEmpty() -> {
                getWorkspace().navigate(ExplorerNavigation.Target.Home)
            }
            normalizedPath.startsWith("/") -> {
                getWorkspace().navigate(ExplorerNavigation.Target.Directory(LocalPath.build(normalizedPath)))
                clearSelection()
            }
            else -> {
                // Invalid path - could show error
                log(tag, WARN) { "Invalid path: $pathString" }
            }
        }
    }

    fun navigate(target: ExplorerNavigation) = launch {
        log(tag) { "navigate($target)" }
        getWorkspace().navigate(target)
        clearSelection()
    }

    fun toggleItemSelection(item: ExplorerItem) {
        log(tag) { "toggleItemSelection($item)" }
        if (item !is ExplorerItem.PathItem) {
            log(tag, WARN) { "toggleItemSelection($item) is not a path" }
            return
        }
        val path = item.lookup.path
        val currentSelection = selectedItemsFlow.value
        selectedItemsFlow.value = if (currentSelection.contains(path)) {
            currentSelection - path
        } else {
            currentSelection + path
        }
    }

    fun clearSelection() {
        selectedItemsFlow.value = emptySet()
    }

    fun executeAction(action: ExplorerAction) = launch {
        log(tag) { "executeAction(${action::class.simpleName})" }
        val stateSnap = state.first()
        when (action) {
            is ExplorerAction.Directory.Create -> {
                dialogEvents.emit(ExplorerDialogEvent.ShowCreateItem)
            }
            is ExplorerAction.Directory.Rename -> {
                val item = stateSnap.items.find { it.id == stateSnap.selectionState.selectedItems.single() }
                item as ExplorerItem.PathItem
                val event = ExplorerDialogEvent.ShowRename(
                    item = item.lookup.lookedUp,
                )
                dialogEvents.emit(event)
            }
            is ExplorerAction.Directory.Copy -> {
                log(tag) { "copySelectedItems(): ${selectedItemsFlow.value.size} items" }
                val selected = selectedItemsFlow.value
                if (selected.isEmpty()) return@launch
                val clip = ClipboardClip.Paths(
                    mode = ClipboardClip.Paths.Mode.COPY,
                    origin = getWorkspace().id,
                    paths = stateSnap.items
                        .filter { it.id in selected }
                        .filterIsInstance<ExplorerItem.PathItem>()
                        .map { it.lookup.lookedUp },
                )
                clipboardRepo.add(clip)
                clearSelection()
            }
            is ExplorerAction.Directory.Cut -> {
                log(tag) { "cutSelectedItems(): ${selectedItemsFlow.value.size} items" }
                val selected = selectedItemsFlow.value
                if (selected.isEmpty()) return@launch
                val clip = ClipboardClip.Paths(
                    mode = ClipboardClip.Paths.Mode.CUT,
                    origin = getWorkspace().id,
                    paths = stateSnap.items
                        .filter { it.id in selected }
                        .filterIsInstance<ExplorerItem.PathItem>()
                        .map { it.lookup.lookedUp },
                )
                clipboardRepo.add(clip)
                clearSelection()
            }
            is ExplorerAction.Directory.Delete -> {
                log(tag) { "deleteSelectedItems(): ${selectedItemsFlow.value.size} items" }
                val selectedPaths = selectedItemsFlow.value
                if (selectedPaths.isNotEmpty()) {
                    val currentLocation = stateSnap.currentLocation
                    if (currentLocation is ExplorerLocation.Directory) {
                        // Convert string paths to APath objects
                        val pathsToDelete = selectedPaths.mapNotNull { pathString ->
                            try {
                                LocalPath.build(pathString)
                            } catch (e: Exception) {
                                log(tag, WARN) { "Failed to parse path: $pathString" }
                                null
                            }
                        }.toSet()

                        if (pathsToDelete.isNotEmpty()) {
                            dialogEvents.emit(ExplorerDialogEvent.ShowDeleteConfirmation(pathsToDelete))
                        }
                    }
                }
            }
            is ExplorerAction.Directory.Share -> {
                log(tag) { "shareSelectedItems(): ${selectedItemsFlow.value.size} items" }
                // TODO: Implement share via Android share sheet
            }
            is ExplorerAction.Directory.SelectAll -> {
                selectedItemsFlow.value = stateSnap.selectionState.selectableItems
            }
            is ExplorerAction.Directory.DeselectAll -> {
                selectedItemsFlow.value = emptySet()
            }
            is ExplorerAction.Common.Sort -> {
                dialogStateFlow.value = ExplorerDialogState.EditSortOptions(
                    currentSortSettings = currentSortSettings.value
                )
            }
            is ExplorerAction.Common.Filter -> {
                // TODO: Show filter options dialog/menu
            }
            is ExplorerAction.Common.ToggleView -> {
                viewModeFlow.value = when (viewModeFlow.value) {
                    ViewMode.LIST -> ViewMode.GRID
                    ViewMode.GRID -> ViewMode.LIST
                }
            }
            is ExplorerAction.Common.Refresh -> {
                getWorkspace().navigate(ExplorerNavigation.Refresh)
            }
        }
    }

    private suspend fun handleDialogEvent(event: ExplorerDialogEvent) {
        log(tag) { "handleDialogEvent($event)" }
        when (event) {
            is ExplorerDialogEvent.ShowCreateItem -> {
                dialogStateFlow.value = ExplorerDialogState.CreateItem
            }
            is ExplorerDialogEvent.ShowDeleteConfirmation -> {
                dialogStateFlow.value = ExplorerDialogState.DeleteConfirmation(event.items)
            }
            is ExplorerDialogEvent.ShowRename -> {
                dialogStateFlow.value = ExplorerDialogState.Rename(event.item)
            }
            is ExplorerDialogEvent.Dismiss -> {
                dialogStateFlow.value = ExplorerDialogState.None
            }
        }
    }

    fun dismissDialog() {
        dialogStateFlow.value = ExplorerDialogState.None
    }

    fun onCreateItem(result: CreateItemResult) = launch {
        log(tag) { "onCreateItem($result)" }
        dialogStateFlow.value = ExplorerDialogState.None

        val currentLocation = state.first().currentLocation
        if (currentLocation is ExplorerLocation.Directory) {
            val operation = when (result.type) {
                CreateItemType.FOLDER -> ExplorerCommand.Create(
                    parentPath = currentLocation.path,
                    name = result.name,
                    type = ExplorerCommand.Create.Type.FOLDER,
                )
                CreateItemType.FILE -> ExplorerCommand.Create(
                    parentPath = currentLocation.path,
                    name = result.name,
                    type = ExplorerCommand.Create.Type.FILE,
                )
            }
            getWorkspace().execute(operation)
        }
    }

    fun onDeleteConfirmed(result: DeleteConfirmationResult) = launch {
        log(tag) { "onDeleteConfirmed($result)" }
        dialogStateFlow.value = ExplorerDialogState.None

        if (result.items.isNotEmpty()) {
            getWorkspace().execute(
                ExplorerCommand.Delete(
                    targets = result.items,
                )
            )
            clearSelection()
        }
    }

    fun onRename(result: RenameResult) = launch {
        log(tag) { "onRename($result)" }
        dialogStateFlow.value = ExplorerDialogState.None

        val currentLocation = state.first().currentLocation as ExplorerLocation.Directory
        getWorkspace().execute(
            ExplorerCommand.Move(
                sources = setOf(result.item),
                destination = currentLocation.path.child(result.newName),
            )
        )
    }

    fun onSortOptions(result: SortOptionsResult) = launch {
        log(tag) { "onSortOptions($result)" }
        dialogStateFlow.value = ExplorerDialogState.None
        explorerSettings.sortSettings.value(result.sortSettings)
        currentSortSettings.value = result.sortSettings
    }

    fun pasteClipboard(clip: ClipboardClip) = launch {
        log(tag) { "pasteClipboard($clip)" }
        when (clip) {
            is ClipboardClip.Paths -> {
                val currentLocation = state.first().currentLocation
                if (currentLocation is ExplorerLocation.Directory) {
                    val operation = when (clip.mode) {
                        ClipboardClip.Paths.Mode.COPY -> ExplorerCommand.Copy(
                            sources = clip.paths.toSet(),
                            destination = currentLocation.path,
                        )
                        ClipboardClip.Paths.Mode.CUT -> ExplorerCommand.Move(
                            sources = clip.paths.toSet(),
                            destination = currentLocation.path,
                        )
                    }
                    getWorkspace().execute(operation)

                    if (clip.mode == ClipboardClip.Paths.Mode.CUT) {
                        clipboardRepo.remove(clip.id)
                    }
                }
            }
        }
    }

    fun removeClipboardEntry(clip: ClipboardClip) = launch {
        log(tag) { "removeClipboardEntry($clip)" }
        clipboardRepo.remove(clip.id)
    }

    fun clearAllClipboard() = launch {
        log(tag) { "clearAllClipboard()" }
        clipboardRepo.clear()
    }

    fun resolveConflict(resolution: PathActionIssue.Resolution) = launch {
        log(tag) { "resolveConflict(): $resolution" }

        val operationId = currentConflictOperationId
        if (operationId != null) {
            // Forward resolution to workspace
            getWorkspace().resolveConflict(operationId, resolution)
        } else {
            log(tag, WARN) { "Cannot resolve conflict: no current operation ID" }
        }

        // Clear conflict UI state (it will be updated by workspace state observer if needed)
        conflictStateFlow.value = null
        currentConflictOperationId = null
    }

    data class State(
        val currentLocation: ExplorerLocation? = null,
        val locationId: String? = null,
        val breadcrumbs: List<ExplorerBreadcrumb> = emptyList(),
        val items: List<ExplorerItem>,
        val isLoading: Boolean,
        val isLoadingExtended: Boolean = false,
        val error: Throwable? = null,
        val selectionState: ExplorerSelectionState = ExplorerSelectionState(),
        val viewMode: ViewMode = ViewMode.LIST,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val availableActions: List<ExplorerAction> = emptyList(),
        val dialogState: ExplorerDialogState = ExplorerDialogState.None,
        val clipboardEntries: List<ClipboardClip> = emptyList(),
        val permissionState: PermissionState = PermissionState(),
    )

    data class ClipboardState(
        val items: Set<String>,
        val isCut: Boolean,
    )

    fun navigateToSetup() = launch {
        log(tag) { "navigateToSetup(): Opening setup for storage permissions" }
        navTo(
            Nav.Main.destSetup(
                typeFilter = setOf(SetupModule.Type.STORAGE),
                requiredTypes = setOf(SetupModule.Type.STORAGE),
                autoCloseWhenComplete = true,
            )
        )
    }

    fun cancelOperation(id: Operation.Id) = launch {
        log(tag) { "cancelOperation($id)" }
        operationsManager.cancel(id)
    }

    fun dismissOperation(id: Operation.Id) = launch {
        log(tag) { "dismissOperation($id)" }
        operationsManager.remove(id)
    }

    fun clearCompletedOperations() = launch {
        log(tag) { "clearCompletedOperations()" }
        operationsManager.clearCompleted()
    }

    fun onOperationClick(operation: OperationDisplay) = launch {
        log(tag) { "onOperationClick($operation)" }
        when (operation.state) {
            is OperationDisplay.State.Waiting -> {
                // Navigate or handle waiting operations
                log(tag) { "Operation is waiting for user input" }
            }
            else -> {
                // Could show operation details or do nothing
                log(tag) { "Operation clicked: ${operation.title}" }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ExplorerWorkspaceViewModel
    }
}