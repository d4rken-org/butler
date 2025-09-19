package eu.darken.butler.common.picker.core

import androidx.lifecycle.SavedStateHandle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel(assistedFactory = FilePickerViewModel.Factory::class)
class FilePickerViewModel @AssistedInject constructor(
    @Assisted private val config: FilePickerConfig,
    @Assisted private val resultKey: String,
    private val dispatchers: DispatcherProvider,
    private val savedStateHandle: SavedStateHandle,
    private val gatewaySwitch: GatewaySwitch,
    private val navController: NavigationController,
) : ViewModel3(dispatchers, logTag("FilePicker", "ViewModel")) {

    private val _state = MutableStateFlow(
        FilePickerState(
            currentPath = config.initialPath ?: getDefaultPath(),
            showHiddenFiles = config.showHiddenFiles,
        )
    )
    val state = _state.asStateFlow()

    init {
        log(tag, INFO) { "Initializing with config: $config, resultKey: $resultKey" }
        savedStateHandle[SAVED_CONFIG_KEY] = config
        savedStateHandle[SAVED_RESULT_KEY] = resultKey
        loadCurrentPath()
    }

    private fun getDefaultPath(): APath {
        return LocalPath.build("/storage/emulated/0")
    }

    private fun loadCurrentPath() = launch {
        val currentPath = _state.value.currentPath ?: return@launch
        
        _state.update { it.copy(isLoading = true, error = null) }
        
        try {
            val lookup = gatewaySwitch.lookup(currentPath)
            
            if (lookup.fileType != FileType.DIRECTORY) {
                log(tag, WARN) { "Path is not a directory: $currentPath" }
                _state.update { 
                    it.copy(
                        isLoading = false, 
                        error = "Not a directory"
                    ) 
                }
                return@launch
            }
            
            val children = gatewaySwitch.listFiles(currentPath)
            val items = children
                .map { child ->
                    try {
                        val childLookup = gatewaySwitch.lookup(child)
                        FilePickerState.FileItem(
                            path = child,
                            name = childLookup.name,
                            isDirectory = childLookup.fileType == FileType.DIRECTORY,
                            size = childLookup.size,
                            lastModified = childLookup.modifiedAt?.toEpochMilliseconds(),
                            isHidden = childLookup.name.startsWith(".")
                        )
                    } catch (e: Exception) {
                        log(tag, WARN) { "Failed to lookup $child: ${e.asLog()}" }
                        null
                    }
                }
                .filterNotNull()
                .filter { item ->
                    _state.value.showHiddenFiles || !item.isHidden
                }
                .sortedWith(
                    compareBy(
                        { !it.isDirectory },
                        { it.name.lowercase() }
                    )
                )
                .let { items ->
                    // Apply filters if in file mode
                    if (config.mode in listOf(SelectionMode.SingleFile, SelectionMode.MultipleFiles) && config.filters.isNotEmpty()) {
                        items.filter { item ->
                            item.isDirectory || config.filters.any { filter ->
                                val regex = filter.replace("*", ".*").toRegex(RegexOption.IGNORE_CASE)
                                item.name.matches(regex)
                            }
                        }
                    } else {
                        items
                    }
                }
            
            _state.update { 
                it.copy(
                    items = items,
                    isLoading = false,
                    error = null
                ) 
            }
            
            log(tag, INFO) { "Loaded ${items.size} items for path: $currentPath" }
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
        _state.update { it.copy(currentPath = path, selectedItems = emptySet()) }
        loadCurrentPath()
    }

    fun navigateUp() {
        val currentPath = _state.value.currentPath ?: return
        
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
            is RawPath -> {
                // RawPath doesn't have a direct parent method, use path string
                val pathStr = currentPath.path
                val parentStr = pathStr.substringBeforeLast('/', "")
                if (parentStr.isNotEmpty()) {
                    RawPath(parentStr)
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
        
        log(tag, INFO) { "Confirming selection: $selected" }
        savedStateHandle[resultKey] = FilePickerResult.Selected(selected)
        navController.up()
    }

    fun cancel() {
        log(tag, INFO) { "Cancelling picker" }
        savedStateHandle[resultKey] = FilePickerResult.Cancelled
        navController.up()
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        // Apply search filter
        if (query.isNotEmpty()) {
            launch {
                // Debounce search
                kotlinx.coroutines.delay(300.milliseconds)
                if (_state.value.searchQuery == query) {
                    loadCurrentPath() // This will apply the search filter
                }
            }
        } else {
            loadCurrentPath()
        }
    }

    fun toggleHiddenFiles() {
        _state.update { it.copy(showHiddenFiles = !it.showHiddenFiles) }
        loadCurrentPath()
    }

    fun createFolder(name: String) = launch {
        val currentPath = _state.value.currentPath ?: return@launch
        
        try {
            val newPath = when (currentPath) {
                is LocalPath -> LocalPath.build(currentPath.path, name)
                is RawPath -> RawPath(currentPath.path + "/" + name)
                else -> {
                    log(tag, WARN) { "Cannot create folder for path type: ${currentPath::class}" }
                    return@launch
                }
            }
            
            gatewaySwitch.createDir(newPath)
            log(tag, INFO) { "Created folder: $newPath" }
            loadCurrentPath()
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create folder: ${e.asLog()}" }
            _state.update { 
                it.copy(error = "Failed to create folder: ${e.message}")
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            config: FilePickerConfig,
            resultKey: String = "file_picker_result"
        ): FilePickerViewModel
    }

    companion object {
        private const val SAVED_CONFIG_KEY = "file_picker_config"
        private const val SAVED_RESULT_KEY = "file_picker_result_key"
    }
}