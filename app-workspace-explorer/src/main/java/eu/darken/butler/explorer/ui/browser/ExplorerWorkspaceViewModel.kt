package eu.darken.butler.explorer.ui.browser

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.catch

@HiltViewModel(assistedFactory = ExplorerWorkspaceViewModel.Factory::class)
class ExplorerWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
) : ViewModel3(dispatchers, logTag("Workspace", "Explorer", id.shortTag, "Page")) {

    private val fileSystemRepository = FileSystemRepository()
    private val currentPathFlow = MutableStateFlow<APath>(RawPath.build("/"))
    private val isLoadingFlow = MutableStateFlow(false)
    private val selectedItemsFlow = MutableStateFlow<Set<String>>(emptySet())

    val state = combine(
        currentPathFlow,
        isLoadingFlow,
        selectedItemsFlow,
    ) { currentPath, isLoading, selectedItems ->
        val fileItems = try {
            if (!isLoading) {
                fileSystemRepository.loadDirectory(currentPath)
            } else {
                emptyFlow()
            }
        } catch (e: Exception) {
            log(tag) { "Error loading directory: $e" }
            emptyFlow()
        }

        State(
            id = id,
            currentPath = currentPath,
            fileItemsFlow = fileItems,
            isLoading = isLoading,
            selectedItems = selectedItems,
        )
    }.asStateFlow()

    fun navigateToPath(path: APath) {
        log(tag) { "Navigating to path: $path" }
        isLoadingFlow.value = true
        currentPathFlow.value = path
        selectedItemsFlow.value = emptySet()
        isLoadingFlow.value = false
    }

    fun toggleItemSelection(itemPath: String) {
        val currentSelection = selectedItemsFlow.value
        selectedItemsFlow.value = if (currentSelection.contains(itemPath)) {
            currentSelection - itemPath
        } else {
            currentSelection + itemPath
        }
    }

    fun clearSelection() {
        selectedItemsFlow.value = emptySet()
    }

    fun validatePath(path: String): Boolean {
        return path.isNotEmpty() && path.startsWith("/")
    }

    data class State(
        val id: Workspace.Id,
        val currentPath: APath,
        val fileItemsFlow: kotlinx.coroutines.flow.Flow<List<FileItem>>,
        val isLoading: Boolean,
        val selectedItems: Set<String>,
    )

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ExplorerWorkspaceViewModel
    }
}