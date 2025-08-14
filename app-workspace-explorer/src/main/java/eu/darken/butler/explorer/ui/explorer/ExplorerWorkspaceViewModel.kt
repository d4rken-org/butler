package eu.darken.butler.explorer.ui.explorer

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.ui.explorer.actions.DefaultActionProvider
import eu.darken.butler.explorer.ui.explorer.actions.ExplorerAction
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemResult
import eu.darken.butler.explorer.ui.explorer.dialogs.CreateItemType
import eu.darken.butler.explorer.ui.explorer.dialogs.DeleteConfirmationResult
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogEvent
import eu.darken.butler.explorer.ui.explorer.dialogs.ExplorerDialogState
import eu.darken.butler.explorer.ui.explorer.dialogs.RenameResult
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@HiltViewModel(assistedFactory = ExplorerWorkspaceViewModel.Factory::class)
class ExplorerWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    private val workspaceProvider: WorkspaceProvider,
    private val actionProvider: DefaultActionProvider,
    private val clipboardRepo: ClipboardRepo,
) : ViewModel3(dispatchers, logTag("Explorer", "Workspace", id.shortTag, "Page")) {

    enum class ViewMode {
        LIST,
        GRID
    }

    private val selectedItemsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val viewModeFlow = MutableStateFlow(ViewMode.LIST)
    private val dialogStateFlow = MutableStateFlow<ExplorerDialogState>(ExplorerDialogState.None)

    val dialogEvents = SingleEventFlow<ExplorerDialogEvent>()

    private val workspace: Flow<ExplorerWorkspace?> = workspaceProvider.retrieve(id).map { it as ExplorerWorkspace? }

    private suspend fun getWorkspace() = workspace.filterNotNull().first()

    private val workspaceState: Flow<ExplorerWorkspace.State> = workspace.flatMapLatest { ws ->
        ws?.current ?: MutableStateFlow(ExplorerWorkspace.State())
    }

    val state = combine(
        workspaceState,
        selectedItemsFlow,
        viewModeFlow,
        clipboardRepo.state,
        dialogStateFlow,
    ) { wsState, selectedItems, viewMode, clipboard, dialogState ->
        val items = wsState.currentLocation?.items ?: emptyList()

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
        )
    }.asStateFlow()

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
                // TODO: Show sort options dialog/menu
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

    init {
        // Handle dialog events
        launch {
            dialogEvents.collect { event ->
                handleDialogEvent(event)
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
                CreateItemType.FOLDER -> ExplorerOperation.FileOp.CreateFolder(
                    parentPath = currentLocation.path,
                    name = result.name
                )
                CreateItemType.FILE -> ExplorerOperation.FileOp.CreateFile(
                    parentPath = currentLocation.path,
                    name = result.name
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
                ExplorerOperation.FileOp.Delete(
                    paths = result.items,
                    recursive = true
                )
            )
            clearSelection()
        }
    }

    fun onRename(result: RenameResult) = launch {
        log(tag) { "onRename($result)" }
        dialogStateFlow.value = ExplorerDialogState.None

        getWorkspace().execute(
            ExplorerOperation.FileOp.Rename(
                path = result.item,
                newName = result.newName
            )
        )
    }

    fun pasteClipboard(clip: ClipboardClip) = launch {
        log(tag) { "pasteClipboard($clip)" }
        when (clip) {
            is ClipboardClip.Paths -> {
                val currentLocation = state.first().currentLocation
                if (currentLocation is ExplorerLocation.Directory) {
                    val operation = when (clip.mode) {
                        ClipboardClip.Paths.Mode.COPY -> ExplorerOperation.FileOp.Copy(
                            sources = clip.paths.toSet(),
                            destination = currentLocation.path,
                        )
                        ClipboardClip.Paths.Mode.CUT -> ExplorerOperation.FileOp.Move(
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

    data class State(
        val currentLocation: ExplorerLocation? = null,
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
    )

    data class ClipboardState(
        val items: Set<String>,
        val isCut: Boolean,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ExplorerWorkspaceViewModel
    }
}