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
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.actions.DefaultActionProvider
import eu.darken.butler.explorer.core.actions.ExplorerAction
import eu.darken.butler.explorer.core.actions.ExplorerActionProvider
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
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
) : ViewModel3(dispatchers, logTag("Explorer", "Workspace", id.shortTag, "Page")) {

    enum class ViewMode {
        LIST,
        GRID
    }

    private val selectedItemsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val viewModeFlow = MutableStateFlow(ViewMode.LIST)
    private val clipboardFlow = MutableStateFlow<ClipboardState?>(null)

    private val workspace: Flow<ExplorerWorkspace?> = workspaceProvider.retrieve(id).map { it as ExplorerWorkspace? }

    private suspend fun getWorkspace() = workspace.filterNotNull().first()

    private val workspaceState: Flow<ExplorerWorkspace.State> = workspace.flatMapLatest { ws ->
        ws?.current ?: MutableStateFlow(ExplorerWorkspace.State())
    }

    val state = combine(
        workspaceState,
        selectedItemsFlow,
        viewModeFlow,
        clipboardFlow,
    ) { wsState, selectedItems, viewMode, clipboard ->
        val items = wsState.currentLocation?.items ?: emptyList()

        val selectionState = ExplorerActionProvider.SelectionState(
            selectedItems = selectedItems,
            hasClipboard = clipboard != null,
        )

        // TODO: Determine actual capabilities based on gateway type and permissions
        val capabilities = ExplorerActionProvider.LocationCapabilities(
            canWrite = wsState.currentLocation?.let {
                it is ExplorerLocation.Directory && it.info?.isWritable == true
            } ?: false,
            hasRootAccess = false, // TODO: Check actual root status
            hasAdbAccess = false, // TODO: Check actual ADB status
        )

        val availableActions = wsState.currentLocation?.let {
            actionProvider.getActions(
                location = it,
                selectionState = selectionState,
                capabilities = capabilities,
            )
        } ?: emptyList()

        State(
            currentLocation = wsState.currentLocation,
            breadcrumbs = wsState.currentBreadcrumbs ?: emptyList(),
            items = items,
            isLoading = wsState.isLoading,
            isLoadingExtended = wsState.isLoadingExtended,
            error = wsState.error,
            selectedItems = selectedItems,
            viewMode = viewMode,
            canGoBack = wsState.canGoBack,
            canGoForward = wsState.canGoForward,
            availableActions = availableActions,
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

    fun refresh() = launch {
        log(tag) { "refresh()" }
        getWorkspace().navigate(ExplorerNavigation.Refresh)
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

    fun createNewFolder() = launch {
        log(tag) { "createNewFolder()" }
        // TODO: Show dialog to get folder name and create it
        // For now, just log the action
    }

    fun copySelectedItems() = launch {
        log(tag) { "copySelectedItems(): ${selectedItemsFlow.value.size} items" }
        val selected = selectedItemsFlow.value
        if (selected.isNotEmpty()) {
            clipboardFlow.value = ClipboardState(
                items = selected,
                isCut = false,
            )
            clearSelection()
        }
    }

    fun cutSelectedItems() = launch {
        log(tag) { "cutSelectedItems(): ${selectedItemsFlow.value.size} items" }
        val selected = selectedItemsFlow.value
        if (selected.isNotEmpty()) {
            clipboardFlow.value = ClipboardState(
                items = selected,
                isCut = true,
            )
            clearSelection()
        }
    }

    fun deleteSelectedItems() = launch {
        log(tag) { "deleteSelectedItems(): ${selectedItemsFlow.value.size} items" }
        // TODO: Show confirmation dialog
        // Delete selected files/folders
    }

    fun shareSelectedItems() = launch {
        log(tag) { "shareSelectedItems(): ${selectedItemsFlow.value.size} items" }
        // TODO: Implement share via Android share sheet
    }

    fun showSortOptions() = launch {
        log(tag) { "showSortOptions()" }
        // TODO: Show sort options dialog/menu
    }

    fun showFilterOptions() = launch {
        log(tag) { "showFilterOptions()" }
        // TODO: Show filter options dialog/menu
    }

    fun showMoreOptions() = launch {
        log(tag) { "showMoreOptions()" }
        // TODO: Show more options menu
        // Could include: Select All, Properties, etc.
    }

    fun toggleViewMode() {
        log(tag) { "toggleViewMode()" }
        viewModeFlow.value = when (viewModeFlow.value) {
            ViewMode.LIST -> ViewMode.GRID
            ViewMode.GRID -> ViewMode.LIST
        }
    }

    fun pasteItems() = launch {
        log(tag) { "pasteItems()" }
        val clipboard = clipboardFlow.value
        if (clipboard != null) {
            // TODO: Implement actual paste operation
            log(tag) { "Pasting ${clipboard.items.size} items, isCut=${clipboard.isCut}" }
            if (clipboard.isCut) {
                clipboardFlow.value = null
            }
        }
    }

    fun selectAll() = launch {
        log(tag) { "selectAll()" }
        val currentState = state.first()
        val allPaths = currentState.items
            .filterIsInstance<ExplorerItem.PathItem>()
            .map { it.lookup.path }
            .toSet()
        selectedItemsFlow.value = allPaths
    }

    fun executeAction(action: ExplorerAction) = launch {
        log(tag) { "executeAction(${action.id})" }
        when (action.id) {
            "create_folder" -> createNewFolder()
            "copy" -> copySelectedItems()
            "cut" -> cutSelectedItems()
            "delete" -> deleteSelectedItems()
            "share" -> shareSelectedItems()
            "paste" -> pasteItems()
            "select_all" -> selectAll()
            "sort" -> showSortOptions()
            "filter" -> showFilterOptions()
            "toggle_view" -> toggleViewMode()
            "refresh" -> refresh()
            "more" -> showMoreOptions()
            else -> log(tag, WARN) { "Unknown action: ${action.id}" }
        }
    }

    data class State(
        val currentLocation: ExplorerLocation? = null,
        val breadcrumbs: List<ExplorerBreadcrumb> = emptyList(),
        val items: List<ExplorerItem>,
        val isLoading: Boolean,
        val isLoadingExtended: Boolean = false,
        val error: Throwable? = null,
        val selectedItems: Set<String>,
        val viewMode: ViewMode = ViewMode.LIST,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val availableActions: List<ExplorerAction> = emptyList(),
    ) {
        val hasSelection: Boolean get() = selectedItems.isNotEmpty()
        val selectionCount: Int get() = selectedItems.size
    }

    data class ClipboardState(
        val items: Set<String>,
        val isCut: Boolean,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ExplorerWorkspaceViewModel
    }
}