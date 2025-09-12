package eu.darken.butler.common.picker.core

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class FilePickerViewModel @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val savedStateHandle: SavedStateHandle,
    private val gatewaySwitch: GatewaySwitch,
    private val navController: NavigationController,
) : ViewModel3(dispatchers, logTag("FilePicker", "ViewModel")) {

    private val config: FilePickerConfig = savedStateHandle["config"] 
        ?: FilePickerConfig()
    
    private val resultKey: String = savedStateHandle["resultKey"] 
        ?: "file_picker_result"

    private val _state = MutableStateFlow(
        FilePickerState(
            currentPath = config.initialPath ?: getDefaultPath(),
            showHiddenFiles = config.showHiddenFiles,
        )
    )
    val state = _state.asStateFlow()

    init {
        log(tag) { "Initializing with config: $config" }
        loadCurrentPath()
    }

    private fun getDefaultPath(): APath {
        return LocalPath.build("/storage/emulated/0")
    }

    private fun loadCurrentPath() = launch {
        val currentPath = _state.value.currentPath ?: return@launch
        
        _state.update { it.copy(isLoading = true, error = null) }
        
        try {
            // For now, use a simplified approach
            // In a real implementation, we'd properly use the gateway
            val items = listOf(
                FilePickerState.FileItem(
                    path = LocalPath.build("/storage/emulated/0/Download"),
                    name = "Download",
                    isDirectory = true
                ),
                FilePickerState.FileItem(
                    path = LocalPath.build("/storage/emulated/0/Documents"),
                    name = "Documents", 
                    isDirectory = true
                ),
                FilePickerState.FileItem(
                    path = LocalPath.build("/storage/emulated/0/test.txt"),
                    name = "test.txt",
                    isDirectory = false,
                    size = 1024
                )
            )
            
            _state.update { 
                it.copy(
                    items = items,
                    isLoading = false,
                    error = null
                ) 
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to load path: ${e.asLog()}" }
            _state.update { 
                it.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load directory"
                ) 
            }
        }
    }

    fun navigateTo(path: APath) {
        log(tag) { "Navigating to: $path" }
        _state.update { it.copy(currentPath = path) }
        loadCurrentPath()
    }

    fun navigateUp() {
        val currentPath = _state.value.currentPath ?: return
        
        // Simplified parent navigation
        val parentPath = when (currentPath) {
            is LocalPath -> {
                val pathStr = currentPath.path
                val parentStr = pathStr.substringBeforeLast('/', "")
                if (parentStr.isNotEmpty()) {
                    LocalPath.build(parentStr)
                } else {
                    null
                }
            }
            else -> null
        }
        
        parentPath?.let { navigateTo(it) }
    }

    fun toggleSelection(path: APath) {
        when (config.mode) {
            SelectionMode.SingleFile, SelectionMode.SingleFolder -> {
                _state.update { 
                    it.copy(selectedItems = setOf(path))
                }
            }
            SelectionMode.MultipleFiles, SelectionMode.MultipleFolders, SelectionMode.Mixed -> {
                _state.update { state ->
                    state.copy(
                        selectedItems = if (path in state.selectedItems) {
                            state.selectedItems - path
                        } else {
                            state.selectedItems + path
                        }
                    )
                }
            }
        }
    }

    fun onItemClick(item: FilePickerState.FileItem) {
        when {
            item.isDirectory -> navigateTo(item.path)
            config.mode == SelectionMode.SingleFolder || config.mode == SelectionMode.MultipleFolders -> {
                // In folder mode, clicking a file does nothing
            }
            else -> toggleSelection(item.path)
        }
    }

    fun onItemLongClick(item: FilePickerState.FileItem) {
        when (config.mode) {
            SelectionMode.SingleFolder, SelectionMode.MultipleFolders -> {
                if (item.isDirectory) {
                    toggleSelection(item.path)
                }
            }
            else -> toggleSelection(item.path)
        }
    }

    fun confirmSelection() {
        val selected = _state.value.selectedItems.toList()
        if (selected.isEmpty()) {
            log(tag, WARN) { "No items selected" }
            return
        }
        
        log(tag) { "Confirming selection: $selected" }
        savedStateHandle[resultKey] = FilePickerResult.Selected(selected)
        navController.up()
    }

    fun cancel() {
        log(tag) { "Cancelling picker" }
        savedStateHandle[resultKey] = FilePickerResult.Cancelled
        navController.up()
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        // TODO: Implement search filtering
    }

    fun toggleHiddenFiles() {
        _state.update { it.copy(showHiddenFiles = !it.showHiddenFiles) }
        loadCurrentPath()
    }

    fun createFolder(name: String) = launch {
        val currentPath = _state.value.currentPath ?: return@launch
        
        try {
            // Simplified folder creation
            log(tag) { "Would create folder: $name in $currentPath" }
            loadCurrentPath()
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create folder: ${e.asLog()}" }
            _state.update { 
                it.copy(error = "Failed to create folder: ${e.message}")
            }
        }
    }
}