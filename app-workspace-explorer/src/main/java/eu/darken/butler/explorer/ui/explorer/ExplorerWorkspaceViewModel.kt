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
) : ViewModel3(dispatchers, logTag("Explorer", "Workspace", id.shortTag, "Page")) {

    private val selectedItemsFlow = MutableStateFlow<Set<String>>(emptySet())

    private val workspace: Flow<ExplorerWorkspace?> = workspaceProvider.retrieve(id).map {
        it as? ExplorerWorkspace
    }

    private suspend fun getWorkspace() = workspace.filterNotNull().first()

    private val workspaceState: Flow<ExplorerWorkspace.State> = workspace.flatMapLatest { ws ->
        ws?.current ?: MutableStateFlow(ExplorerWorkspace.State())
    }

    val state = combine(
        workspaceState,
        selectedItemsFlow,
    ) { wsState, selectedItems ->
        val items = wsState.currentLocation?.items ?: emptyList()

        State(
            currentLocation = wsState.currentLocation,
            breadcrumbs = wsState.currentBreadcrumbs ?: emptyList(),
            items = items,
            isLoading = wsState.isLoading,
            isLoadingExtended = wsState.isLoadingExtended,
            error = wsState.error,
            selectedItems = selectedItems,
            canGoBack = wsState.canGoBack,
            canGoForward = wsState.canGoForward,
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

    data class State(
        val currentLocation: ExplorerLocation? = null,
        val breadcrumbs: List<ExplorerBreadcrumb> = emptyList(),
        val items: List<ExplorerItem>,
        val isLoading: Boolean,
        val isLoadingExtended: Boolean = false,
        val error: Throwable? = null,
        val selectedItems: Set<String>,
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
    ) {
        val hasSelection: Boolean get() = selectedItems.isNotEmpty()
        val selectionCount: Int get() = selectedItems.size
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ExplorerWorkspaceViewModel
    }
}