package eu.darken.butler.explorer.ui.explorer

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.NavigationRequest
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.ExplorerPathItem
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.Instant

@HiltViewModel(assistedFactory = ExplorerWorkspaceViewModel.Factory::class)
class ExplorerWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    private val workspaceProvider: WorkspaceProvider,
) : ViewModel3(dispatchers, logTag("Workspace", "Explorer", id.shortTag, "Page")) {

    private val selectedItemsFlow = MutableStateFlow<Set<APath>>(emptySet())

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
        val items = when (val location = wsState.currentLocation) {
            is ExplorerLocation.Home -> {
                location.items.mapIndexed { index, item ->
                    ExplorerPathItem.Shortcut(
                        lookup = createShortcutLookup("home_item_$index"),
                        icon = item.icon,
                        label = item.label,
                        target = item.target,
                    )
                }
            }
            is ExplorerLocation.Device -> {
                location.items.mapIndexed { index, item ->
                    ExplorerPathItem.Shortcut(
                        lookup = createShortcutLookup("device_item_$index"),
                        icon = item.icon,
                        label = item.label,
                        target = ExplorerLocation.Directory(item.target, parent = location),
                    )
                }
            }
            is ExplorerLocation.Directory -> {
                location.items ?: emptyList()
            }
            null -> emptyList()
        }

        State(
            currentLocation = wsState.currentLocation,
            breadcrumbs = wsState.currentLocation?.breadcrumbs ?: emptyList(),
            items = items.map { item ->
                when (item) {
                    is ExplorerPathItem.Directory -> item.copy(isSelected = selectedItems.contains(item.lookup.lookedUp))
                    is ExplorerPathItem.RegularFile -> item.copy(isSelected = selectedItems.contains(item.lookup.lookedUp))
                    is ExplorerPathItem.SymbolicLink -> item.copy(isSelected = selectedItems.contains(item.lookup.lookedUp))
                    is ExplorerPathItem.MediaFile -> item.copy(isSelected = selectedItems.contains(item.lookup.lookedUp))
                    is ExplorerPathItem.ApkFile -> item.copy(isSelected = selectedItems.contains(item.lookup.lookedUp))
                    is ExplorerPathItem.ArchiveFile -> item.copy(isSelected = selectedItems.contains(item.lookup.lookedUp))
                    is ExplorerPathItem.ImageFile -> item.copy(isSelected = selectedItems.contains(item.lookup.lookedUp))
                    is ExplorerPathItem.DocumentFile -> item.copy(isSelected = selectedItems.contains(item.lookup.lookedUp))
                    is ExplorerPathItem.Shortcut -> item.copy(isSelected = selectedItems.contains(item.lookup.lookedUp))
                }
            },
            isLoading = wsState.isLoading,
            isLoadingExtended = wsState.isLoadingExtended,
            error = wsState.error,
            selectedItems = selectedItems,
            canGoBack = wsState.canGoBack,
            canGoForward = wsState.canGoForward,
        )
    }.asStateFlow()

    fun navigate(item: ExplorerPathItem) = launch {
        log(tag) { "navigate($item)" }
        when (item) {
            is ExplorerPathItem.Shortcut -> {
                getWorkspace().navigate(NavigationRequest.ToLocation(item.target))
                clearSelection()
            }
            else -> {
                if (item.isDirectory) {
                    getWorkspace().navigate(NavigationRequest.ToPath(item.lookup.lookedUp))
                    clearSelection()
                }
                // Non-directory items are not navigable, do nothing
            }
        }
    }

    fun navigateToPathString(pathString: String) = launch {
        log(tag) { "navigateToPathString($pathString)" }
        val normalizedPath = pathString.trim()

        when {
            normalizedPath.isEmpty() -> {
                getWorkspace().navigate(NavigationRequest.ToBreadcrumb(ExplorerLocation.Breadcrumb.Target.Home))
            }
            normalizedPath.startsWith("/") -> {
                val path = LocalPath.build(normalizedPath)
                getWorkspace().navigate(NavigationRequest.ToPath(path))
                clearSelection()
            }
            else -> {
                // Invalid path - could show error
                log(tag, WARN) { "Invalid path: $pathString" }
            }
        }
    }

    fun navigateToBreadcrumb(target: ExplorerLocation.Breadcrumb.Target) = launch {
        log(tag) { "navigateToBreadcrumb($target)" }
        getWorkspace().navigate(NavigationRequest.ToBreadcrumb(target))
        clearSelection()
    }

    fun refresh() = launch {
        log(tag) { "refresh()" }
        getWorkspace().navigate(NavigationRequest.Refresh)
    }

    fun toggleItemSelection(item: ExplorerPathItem) {
        val path = item.lookup.lookedUp
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
        val breadcrumbs: List<ExplorerLocation.Breadcrumb> = emptyList(),
        val items: List<ExplorerPathItem>,
        val isLoading: Boolean,
        val isLoadingExtended: Boolean = false,
        val error: Throwable? = null,
        val selectedItems: Set<APath>,
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