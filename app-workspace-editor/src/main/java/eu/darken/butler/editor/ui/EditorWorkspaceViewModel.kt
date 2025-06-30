package eu.darken.butler.editor.ui

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
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.EditorDataSource
import eu.darken.butler.editor.core.EditorModule
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.FileDataSource
import eu.darken.butler.editor.core.FileInfo
import eu.darken.butler.editor.core.InMemoryDataSource
import eu.darken.butler.editor.core.MemoryManager
import eu.darken.butler.editor.core.MemoryStats
import eu.darken.butler.editor.core.SearchResult
import eu.darken.butler.editor.core.TextPosition
import eu.darken.butler.editor.core.VirtualTextBuffer
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.editor.core.ChunkManager
import eu.darken.butler.editor.core.ChunkRepository

@HiltViewModel(assistedFactory = EditorWorkspaceViewModel.Factory::class)
class EditorWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    private val fileDataSourceFactory: EditorModule.FileDataSourceFactory,
    private val inMemoryDataSourceFactory: EditorModule.InMemoryDataSourceFactory,
    private val chunkRepositoryFactory: EditorModule.ChunkRepositoryFactory,
    private val chunkManagerFactory: EditorModule.ChunkManagerFactory,
    private val chunkedTextBufferFactory: EditorModule.ChunkedTextBufferFactory,
    private val memoryManager: MemoryManager,
    private val gatewaySwitch: eu.darken.butler.common.files.GatewaySwitch,
    private val editorSettings: EditorSettings,
    dispatchers: DispatcherProvider,
    navCtrl: NavigationController,
    private val workspaceProvider: WorkspaceProvider,
) : ViewModel4(dispatchers, logTag("Workspace", "Editor", id.shortTag, "Page"), navCtrl) {

    private data class EditorResources(
        val workspace: EditorWorkspace,
        val dataSource: EditorDataSource,
        val chunkRepository: ChunkRepository,
        val chunkManager: ChunkManager,
        val textBuffer: VirtualTextBuffer
    )

    private val editorResources = MutableStateFlow<EditorResources?>(null)
    
    private val workspaceFlow = flow {
        emit(workspaceProvider.get(id))
    }.flatMapLatest { it }

    private val _currentContent = MutableStateFlow("")
    private val _cursorPosition = MutableStateFlow(TextPosition.ZERO)
    private val _selectionRange = MutableStateFlow<Pair<TextPosition, TextPosition>?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<Throwable?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    private val _visibleRange = MutableStateFlow<IntRange>(0..50)
    private val _totalLines = MutableStateFlow(1)

    val state = combine(
        flowOf(id),
        editorResources.flatMapLatest { resources ->
            resources?.textBuffer?.fileInfo ?: flowOf(null)
        },
        _totalLines,
        editorResources.flatMapLatest { resources ->
            resources?.textBuffer?.isModified ?: flowOf(false)
        },
        _currentContent,
        _cursorPosition,
        _selectionRange,
        _isLoading,
        _error,
        _searchQuery,
        _searchResults,
        _visibleRange,
        flow { emit(memoryManager.getMemoryStats()) }.catch { emit(MemoryStats(0, 0, 0, 0, 0)) },
        editorSettings.showLineNumbers.flow,
        editorSettings.wordWrap.flow,
        editorResources
    ) { values ->
        State(
            id = values[0] as Workspace.Id,
            fileInfo = values[1] as FileInfo?,
            totalLines = values[2] as Int,
            isModified = values[3] as Boolean,
            currentContent = values[4] as String,
            cursorPosition = values[5] as TextPosition,
            selectionRange = values[6] as Pair<TextPosition, TextPosition>?,
            isLoading = values[7] as Boolean,
            error = values[8] as Throwable?,
            searchQuery = values[9] as String,
            searchResults = values[10] as List<SearchResult>,
            visibleRange = values[11] as IntRange,
            memoryStats = values[12] as MemoryStats,
            showLineNumbers = values[13] as Boolean,
            wordWrap = values[14] as Boolean,
            hasWorkspace = values[15] != null
        )
    }.asStateFlow()

    init {
        // Collect workspace flow and manage resources lifecycle
        workspaceFlow
            .onEach { workspace ->
                if (workspace != null) {
                    createResources(workspace as EditorWorkspace)
                } else {
                    cleanupResources()
                }
            }
            .catch { e ->
                log(tag, ERROR) { "Failed to monitor workspace - ${e.asLog()}" }
                _error.value = e
                cleanupResources()
            }
            .launchInViewModel()
    }
    
    private suspend fun createResources(workspace: EditorWorkspace) {
        try {
            _isLoading.value = true
            _error.value = null
            
            log(tag) { "Creating editor resources for workspace: ${workspace.id}" }
            
            // Create data source
            val dataSource = workspace.filePath?.let { filePath ->
                fileDataSourceFactory.create(filePath, gatewaySwitch)
            } ?: inMemoryDataSourceFactory.create(
                if (BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.DEV) {
                    generateDebugContent()
                } else {
                    ""
                }
            )
            
            // Create dependent resources
            val chunkRepository = chunkRepositoryFactory.create(dataSource)
            val chunkManager = chunkManagerFactory.create(chunkRepository)
            val textBuffer = chunkedTextBufferFactory.create(chunkManager, chunkRepository)
            
            // Store resources
            val resources = EditorResources(
                workspace = workspace,
                dataSource = dataSource,
                chunkRepository = chunkRepository,
                chunkManager = chunkManager,
                textBuffer = textBuffer
            )
            editorResources.value = resources
            
            // Initialize based on data source type
            when (dataSource) {
                is FileDataSource -> {
                    val filePath = workspace.filePath!!
                    log(tag) { "Initializing file data source: $filePath" }

                    // Initialize the file data source
                    val initResult = dataSource.initialize()
                    if (initResult.isFailure) {
                        _error.value = initResult.exceptionOrNull()
                        return
                    }

                    // Open file in text buffer
                    val openResult = textBuffer.openFile(filePath)
                    if (openResult.isFailure) {
                        _error.value = openResult.exceptionOrNull()
                        return
                    }

                    log(tag) { "Successfully initialized with file: $filePath" }
                }
                is InMemoryDataSource -> {
                    log(tag) { "Initializing in-memory data source" }

                    // Initialize the text buffer for in-memory content
                    val initResult = textBuffer.initialize()
                    if (initResult.isFailure) {
                        _error.value = initResult.exceptionOrNull()
                        return
                    }

                    // Load initial content for DEV mode
                    if (BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.DEV) {
                        val content = dataSource.getContent()
                        _currentContent.value = content
                        val lines = content.split('\n')
                        _totalLines.value = lines.size
                        _visibleRange.value = 0..minOf(50, lines.size - 1)
                        log(tag) { "Initialized with debug content: ${lines.size} lines" }
                    } else {
                        _currentContent.value = ""
                        _totalLines.value = 1
                        _visibleRange.value = 0..0
                    }

                    log(tag) { "Successfully initialized in-memory editor" }
                }
            }
            
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to create editor resources - ${e.asLog()}" }
            _error.value = e
        } finally {
            _isLoading.value = false
        }
    }
    
    private suspend fun cleanupResources() {
        log(tag) { "Cleaning up editor resources" }
        
        val resources = editorResources.value
        resources?.let {
            try {
                // Close text buffer
                it.textBuffer.closeFile()
                
                // Close data source
                it.dataSource.close()
            } catch (e: Exception) {
                log(tag, ERROR) { "Error during cleanup - ${e.asLog()}" }
            }
        }
        
        // Clear resources
        editorResources.value = null
        
        // Clear state
        clearState()
    }

    fun openFile(filePath: APath) {
        launch {
            try {
                val resources = editorResources.value
                if (resources == null) {
                    log(tag, WARN) { "Cannot open file - no workspace resources available" }
                    _error.value = IllegalStateException("Editor not initialized")
                    return@launch
                }
                
                _isLoading.value = true
                _error.value = null

                log(tag) { "Opening file: $filePath" }

                val result = resources.textBuffer.openFile(filePath)
                if (result.isFailure) {
                    _error.value = result.exceptionOrNull()
                    return@launch
                }

                // Load initial content for visible range
                loadVisibleContent()

                log(tag) { "Successfully opened file: $filePath" }

            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to open file: $filePath - ${e.asLog()}" }
                _error.value = e
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun closeFile() {
        launch {
            try {
                val resources = editorResources.value
                if (resources == null) {
                    log(tag, WARN) { "Cannot close file - no workspace resources available" }
                    return@launch
                }
                
                _isLoading.value = true
                resources.textBuffer.closeFile()
                clearState()
                log(tag) { "File closed" }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to close file - ${e.asLog()}" }
                _error.value = e
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun saveFile() {
        launch {
            try {
                val resources = editorResources.value
                if (resources == null) {
                    log(tag, WARN) { "Cannot save file - no workspace resources available" }
                    _error.value = IllegalStateException("Editor not initialized")
                    return@launch
                }
                
                _isLoading.value = true
                val result = resources.textBuffer.saveFile()
                if (result.isFailure) {
                    _error.value = result.exceptionOrNull()
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to save file - ${e.asLog()}" }
                _error.value = e
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateVisibleRange(startLine: Int, endLine: Int) {
        val totalLines = _totalLines.value
        if (totalLines <= 0) {
            return // No content to display
        }

        // Constrain the range to available lines
        val constrainedStart = startLine.coerceIn(0, totalLines - 1)
        val constrainedEnd = endLine.coerceIn(constrainedStart, totalLines - 1)
        val newRange = constrainedStart..constrainedEnd

        if (_visibleRange.value != newRange) {
            _visibleRange.value = newRange
            // No need to load content since we're managing it directly now
        }
    }

    fun insertText(text: String) {
        val resources = editorResources.value
        if (resources == null) {
            log(tag, WARN) { "Cannot insert text - no workspace resources available" }
            return
        }
        
        // TEMPORARY FIX: Bypass complex text buffer and directly update content
        val currentContent = _currentContent.value
        val currentPos = _cursorPosition.value

        // Insert text at cursor position
        val beforeCursor = currentContent.substring(0, currentPos.offset.toInt().coerceIn(0, currentContent.length))
        val afterCursor = currentContent.substring(currentPos.offset.toInt().coerceIn(0, currentContent.length))
        val newContent = beforeCursor + text + afterCursor

        // Update content and cursor position
        _currentContent.value = newContent

        // Update total lines
        val lines = if (newContent.isEmpty()) 1 else newContent.split('\n').size
        _totalLines.value = lines

        // Update visible range if needed
        val currentRange = _visibleRange.value
        if (currentRange.last < lines - 1) {
            _visibleRange.value = currentRange.first..minOf(currentRange.first + 50, lines - 1)
        }

        val newOffset = currentPos.offset + text.length
        val newPosition = TextPosition(
            offset = newOffset,
            line = currentPos.line + text.count { it == '\n' },
            column = if (text.contains('\n')) {
                text.length - text.lastIndexOf('\n') - 1
            } else {
                currentPos.column + text.length
            }
        )
        _cursorPosition.value = newPosition
    }

    fun deleteSelection() {
        val selection = _selectionRange.value
        if (selection != null) {
            launch {
                try {
                    val resources = editorResources.value
                    if (resources == null) {
                        log(tag, WARN) { "Cannot delete selection - no workspace resources available" }
                        return@launch
                    }
                    
                    val result = resources.textBuffer.deleteText(selection.first, selection.second)
                    if (result.isSuccess) {
                        _selectionRange.value = null
                        _cursorPosition.value = selection.first
                        loadVisibleContent()
                    } else {
                        _error.value = result.exceptionOrNull()
                    }
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to delete selection - ${e.asLog()}" }
                    _error.value = e
                }
            }
        }
    }

    fun setCursorPosition(position: TextPosition) {
        _cursorPosition.value = position
        _selectionRange.value = null
    }

    fun setSelection(start: TextPosition, end: TextPosition) {
        _selectionRange.value = start to end
    }

    fun search(query: String) {
        _searchQuery.value = query
        if (query.isNotEmpty()) {
            launch {
                try {
                    val resources = editorResources.value
                    if (resources == null) {
                        log(tag, WARN) { "Cannot search - no workspace resources available" }
                        return@launch
                    }
                    
                    val results = resources.textBuffer.search(query, _cursorPosition.value, ignoreCase = true)
                    _searchResults.value = results
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to search - ${e.asLog()}" }
                    _error.value = e
                }
            }
        } else {
            _searchResults.value = emptyList()
        }
    }

    fun goToLine(lineNumber: Int) {
        launch {
            try {
                val totalLines = _totalLines.value
                if (lineNumber < 0 || lineNumber >= totalLines) {
                    return@launch
                }

                // Calculate offset for the line
                val lines = _currentContent.value.split('\n')
                var offset = 0
                for (i in 0 until lineNumber) {
                    offset += lines[i].length + 1 // +1 for newline
                }

                val position = TextPosition(
                    offset = offset.toLong(),
                    line = lineNumber,
                    column = 0
                )
                _cursorPosition.value = position

                // Update visible range to include this line
                val visibleStart = (lineNumber - 25).coerceAtLeast(0)
                val visibleEnd = (lineNumber + 25).coerceAtMost(totalLines - 1)
                updateVisibleRange(visibleStart, visibleEnd)

            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to go to line: $lineNumber - ${e.asLog()}" }
                _error.value = e
            }
        }
    }

    fun undo() {
        launch {
            try {
                val resources = editorResources.value
                if (resources == null) {
                    log(tag, WARN) { "Cannot undo - no workspace resources available" }
                    return@launch
                }
                
                val result = resources.textBuffer.undo()
                if (result.isSuccess) {
                    loadVisibleContent()
                } else {
                    _error.value = result.exceptionOrNull()
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to undo - ${e.asLog()}" }
                _error.value = e
            }
        }
    }

    fun redo() {
        launch {
            try {
                val resources = editorResources.value
                if (resources == null) {
                    log(tag, WARN) { "Cannot redo - no workspace resources available" }
                    return@launch
                }
                
                val result = resources.textBuffer.redo()
                if (result.isSuccess) {
                    loadVisibleContent()
                } else {
                    _error.value = result.exceptionOrNull()
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to redo - ${e.asLog()}" }
                _error.value = e
            }
        }
    }

    fun canUndo(): Boolean = editorResources.value?.textBuffer?.canUndo() ?: false

    fun canRedo(): Boolean = editorResources.value?.textBuffer?.canRedo() ?: false

    fun clearError() {
        _error.value = null
    }

    private suspend fun loadVisibleContent() {
        // No longer needed - we're managing content directly in memory
        // This function is kept for compatibility but does nothing
    }

    private fun clearState() {
        _currentContent.value = ""
        _cursorPosition.value = TextPosition.ZERO
        _selectionRange.value = null
        _error.value = null
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _visibleRange.value = 0..50
        _totalLines.value = 1
    }


    data class State(
        val id: Workspace.Id,
        val fileInfo: FileInfo? = null,
        val totalLines: Int = 0,
        val isModified: Boolean = false,
        val currentContent: String = "",
        val cursorPosition: TextPosition = TextPosition.ZERO,
        val selectionRange: Pair<TextPosition, TextPosition>? = null,
        val isLoading: Boolean = false,
        val error: Throwable? = null,
        val searchQuery: String = "",
        val searchResults: List<SearchResult> = emptyList(),
        val visibleRange: IntRange = 0..50,
        val memoryStats: MemoryStats = MemoryStats(0, 0, 0, 0, 0),
        val showLineNumbers: Boolean = true,
        val wordWrap: Boolean = false,
        val hasWorkspace: Boolean = true
    ) {
        val hasFile: Boolean get() = fileInfo != null
        val fileName: String get() = fileInfo?.path?.name ?: "Untitled"
        val hasSelection: Boolean get() = selectionRange != null
        val hasSearchResults: Boolean get() = searchResults.isNotEmpty()
        val isSearchActive: Boolean get() = searchQuery.isNotEmpty()
        val hasError: Boolean get() = error != null
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): EditorWorkspaceViewModel
    }
    
    companion object {
        private fun generateDebugContent(): String {
            return """
                |Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.
                |Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.
                |Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur.
                |Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.
                |
                |The quick brown fox jumps over the lazy dog. This is a test line with very long content that should demonstrate horizontal scrolling when line wrap is disabled in the editor settings.
                |Short line.
                |Another medium length line with some content.
                |
                |    Indented line with 4 spaces
                |        Double indented line with 8 spaces
                |            Triple indented line with 12 spaces
                |
                |Special characters: !@#$%^&*()_+-={}[]|\:";'<>?,./
                |Numbers: 0123456789
                |Mixed case: AbCdEfGhIjKlMnOpQrStUvWxYz
                |
                |This is line 17 of the debug content.
                |Line 18 - Testing scrolling behavior
                |Line 19 - More test content
                |Line 20 - Final line of debug text
            """.trimMargin()
        }
    }
}
