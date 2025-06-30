package eu.darken.butler.explorer.ui.explorer

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileType
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.ExplorerPathItem
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
                        target = ExplorerLocation.Directory(item.target),
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
            error = wsState.error,
            selectedItems = selectedItems,
            canGoBack = wsState.canGoBack,
            canGoForward = wsState.canGoForward,
        )
    }.asStateFlow()

    fun navigateToPath(path: APath) = launch {
        log(tag) { "navigateToPath($path)" }
        workspace.first()?.navigateTo(path)
        clearSelection()
    }

    fun navigateToItem(item: ExplorerPathItem) = launch {
        log(tag) { "navigateToItem($item)" }
        if (item.isDirectory) {
            workspace.first()?.navigateTo(item.lookup.lookedUp)
            clearSelection()
        }
    }

    fun navigateBack() = launch {
        log(tag) { "navigateBack()" }
        workspace.first()?.navigateBack()
        clearSelection()
    }

    fun navigateForward() = launch {
        log(tag) { "navigateForward()" }
        workspace.first()?.navigateForward()
        clearSelection()
    }

    fun refresh() = launch {
        log(tag) { "refresh()" }
        workspace.first()?.refresh()
    }

    fun navigateToBreadcrumb(target: ExplorerLocation.Breadcrumb.Target) = launch {
        log(tag) { "navigateToBreadcrumb($target)" }
        workspace.first()?.navigateToBreadcrumb(target)
        clearSelection()
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

    fun validatePath(path: String): Boolean {
        return path.isNotEmpty() && (path.startsWith("/") || path == "HOME")
    }

    fun navigateToShortcut(shortcut: ExplorerPathItem.Shortcut) = launch {
        log(tag) { "navigateToShortcut($shortcut)" }
        workspace.first()?.navigateToLocation(shortcut.target)
        clearSelection()
    }

    private fun createShortcutLookup(id: String): APathLookup<RawPath> {
        val path = RawPath.build("/virtual/$id")
        return object : APathLookup<RawPath> {
            override val lookedUp: RawPath = path
            override val fileType: FileType = FileType.DIRECTORY
            override val size: Long = 0
            override val modifiedAt: Instant = Instant.now()
            override val target: APath? = null
        }
    }

    data class State(
        val currentLocation: ExplorerLocation? = null,
        val breadcrumbs: List<ExplorerLocation.Breadcrumb> = emptyList(),
        val items: List<ExplorerPathItem>,
        val isLoading: Boolean,
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