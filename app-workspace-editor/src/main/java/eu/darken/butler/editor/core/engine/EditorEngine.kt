package eu.darken.butler.editor.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.buffer
import okio.use

class EditorEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val filePath: APath<*>?,
    private val gatewaySwitch: GatewaySwitch,
    private val editorSettings: EditorSettings,
    private val fileDataSourceFactory: FileDataSource.Factory,
    private val inMemoryDataSourceFactory: InMemoryDataSource.Factory,
    private val chunkRepositoryFactory: ChunkRepository.Factory,
    private val chunkManagerFactory: ChunkManager.Factory,
    private val chunkedTextBufferFactory: ChunkedTextBuffer.Factory,
) {
    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine")

    private val stateMutex = Mutex()
    private val _state = MutableStateFlow<EditorState>(EditorState.Empty)
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _currentContent = MutableStateFlow("")
    val currentContent: StateFlow<String> = _currentContent.asStateFlow()

    private val _cursorPosition = MutableStateFlow(TextPosition.Companion.ZERO)
    val cursorPosition: StateFlow<TextPosition> = _cursorPosition.asStateFlow()

    private val _selectionRange = MutableStateFlow<Pair<TextPosition, TextPosition>?>(null)
    val selectionRange: StateFlow<Pair<TextPosition, TextPosition>?> = _selectionRange.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _visibleRange = MutableStateFlow<IntRange>(0..50)
    val visibleRange: StateFlow<IntRange> = _visibleRange.asStateFlow()

    private val _totalLines = MutableStateFlow(1)
    val totalLines: StateFlow<Int> = _totalLines.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    private var isInitializing = true

    val fileInfo: Flow<FileInfo?> = state.map { s ->
        when (s) {
            is EditorState.Loaded -> s.fileInfo
            else -> null
        }
    }

    val isModified: Flow<Boolean> = state.map { s ->
        when (s) {
            is EditorState.Loaded -> s.isModified
            else -> false
        }
    }

    val textBuffer: ChunkedTextBuffer?
        get() = (state.value as? EditorState.Loaded)?.resources?.textBuffer

    private suspend fun createResourcesForFile(filePath: APath<*>?): EditorResources {
        log(tag) { "Creating resources for file: ${filePath?.name ?: "in-memory"}" }

        // Create data source
        val dataSource = if (filePath != null) {
            fileDataSourceFactory.create(workspaceId, filePath, gatewaySwitch)
        } else {
            inMemoryDataSourceFactory.create(
                workspaceId,
                if (BuildConfigWrap.BUILD_TYPE == BuildConfigWrap.BuildType.DEV) {
                    generateDebugContent()
                } else {
                    ""
                }
            )
        }

        // Create dependent resources
        val chunkRepository = chunkRepositoryFactory.create(workspaceId, dataSource)
        val chunkManager = chunkManagerFactory.create(workspaceId, chunkRepository)

        // Read undo settings
        val maxUndoStackSize = editorSettings.undoStackSize.value()
        val maxUndoMemoryBytes = editorSettings.undoMaxMemoryMB.value() * 1_048_576L  // Convert MB to bytes

        val textBuffer = chunkedTextBufferFactory.create(
            workspaceId,
            chunkManager,
            chunkRepository,
            maxUndoStackSize,
            maxUndoMemoryBytes
        )

        return EditorResources(
            dataSource = dataSource,
            chunkRepository = chunkRepository,
            chunkManager = chunkManager,
            textBuffer = textBuffer,
        )
    }

    private suspend fun disposeResources(resources: EditorResources) {
        log(tag) { "Disposing resources" }

        // Clean up in reverse order, don't abort on failures
        try {
            resources.textBuffer.release()
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to release text buffer - ${e.asLog()}" }
        }

        try {
            resources.dataSource.close()
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to close data source - ${e.asLog()}" }
        }
    }

    suspend fun initialize(): Result<Unit> = stateMutex.withLock {
        return try {
            log(tag) { "Initializing engine with: ${filePath?.name ?: "in-memory editor"}" }

            // Transition to Loading state
            _state.value = if (filePath != null) {
                EditorState.Loading(filePath)
            } else {
                EditorState.Empty
            }

            // Create new resources
            val resources = createResourcesForFile(filePath)

            // Open data source
            resources.dataSource.open()

            // Initialize text buffer
            val bufferInitResult = resources.textBuffer.initialize()
            if (bufferInitResult.isFailure) {
                val error = bufferInitResult.exceptionOrNull() ?: Exception("Unknown error")
                _state.value = EditorState.Error(error, _state.value)
                _error.value = error
                return bufferInitResult
            }

            // Update engine state from initialized buffer
            _totalLines.value = resources.textBuffer.totalLines.value

            // Load initial visible range content
            val endLine = minOf(50, resources.textBuffer.totalLines.value - 1)
            if (endLine >= 0) {
                _visibleRange.value = 0..endLine
                val contentResult = resources.textBuffer.getTextForRange(0, endLine)
                if (contentResult.isSuccess) {
                    _currentContent.value = contentResult.getOrNull() ?: ""
                }
            } else {
                _visibleRange.value = 0..0
                _currentContent.value = ""
            }

            // Transition to Loaded state
            val fileInfoValue = resources.textBuffer.fileInfo.value
            val isModifiedValue = resources.textBuffer.isModified.value
            _state.value = EditorState.Loaded(
                filePath = filePath,
                resources = resources,
                fileInfo = fileInfoValue,
                isModified = isModifiedValue,
            )

            log(tag) { "Successfully initialized engine with: ${filePath?.name ?: "in-memory editor"}" }
            isInitializing = false
            Result.success(Unit)

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to initialize engine: ${filePath?.name} - ${e.asLog()}" }
            _state.value = EditorState.Error(e, _state.value)
            _error.value = e
            Result.failure(e)
        }
    }

    suspend fun saveFile(): Result<Unit> {
        val currentState = _state.value

        return when (currentState) {
            is EditorState.Loaded -> {
                try {
                    log(tag) { "Saving file: ${currentState.filePath?.name ?: "in-memory"}" }
                    val result = currentState.resources.textBuffer.saveFile()
                    if (result.isFailure) {
                        _error.value = result.exceptionOrNull()
                    } else {
                        // Update state with new isModified value
                        _state.value = currentState.copy(isModified = false)
                    }
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to save file - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot save file - no file open")
                log(tag, Logging.Priority.WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun saveFileAs(newFilePath: APath<*>): Result<Unit> = stateMutex.withLock {
        val currentState = _state.value

        return when (currentState) {
            is EditorState.Loaded -> {
                try {
                    log(tag) { "Saving as: ${newFilePath.name}" }

                    // Open source from current data source for streaming
                    val currentDataSource = currentState.resources.dataSource
                    val source = currentDataSource.openSource()

                    // Stream to new file using gateway
                    try {
                        gatewaySwitch.file(newFilePath, readWrite = true).use { handle ->
                            handle.sink().buffer().use { sink ->
                                sink.writeAll(source)
                            }
                        }
                    } finally {
                        source.close()
                    }

                    log(tag) { "Content streamed to: ${newFilePath.name}" }

                    // Note: Engine remains with old source. Workspace should handle engine switch if needed.
                    Result.success(Unit)

                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to save as: ${newFilePath.name} - ${e.asLog()}" }
                    _state.value = EditorState.Error(e, currentState)
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot save - no content available")
                log(tag, Logging.Priority.WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun insertText(text: String) {
        val currentState = _state.value

        when (currentState) {
            is EditorState.Loaded -> {
                log(tag) { "Inserting text at position ${_cursorPosition.value}: ${text.take(50)}..." }

                val result = currentState.resources.textBuffer.insertText(_cursorPosition.value, text)

                result.fold(
                    onSuccess = { newPosition ->
                        log(tag) { "Text inserted successfully, new position: $newPosition" }

                        // Update cursor position from result
                        _cursorPosition.value = newPosition

                        // Mark as modified
                        _state.value = currentState.copy(isModified = true)

                        // Update total lines from text buffer
                        _totalLines.value = currentState.resources.textBuffer.totalLines.value

                        // Refresh visible content from updated chunks
                        refreshVisibleContent()
                    },
                    onFailure = { e ->
                        log(tag, Logging.Priority.ERROR) { "Failed to insert text - ${e.asLog()}" }
                        _error.value = e
                    }
                )
            }
            else -> {
                log(tag, Logging.Priority.WARN) { "Cannot insert text - no file open" }
            }
        }
    }

    suspend fun deleteSelection(): Result<String> {
        val currentState = _state.value

        return when (currentState) {
            is EditorState.Loaded -> {
                val selection = _selectionRange.value ?: return Result.failure(
                    IllegalStateException("No selection to delete")
                )

                try {
                    val result = currentState.resources.textBuffer.deleteText(selection.first, selection.second)
                    if (result.isSuccess) {
                        _selectionRange.value = null
                        _cursorPosition.value = selection.first
                        _state.value = currentState.copy(isModified = true)
                        _totalLines.value = currentState.resources.textBuffer.totalLines.value
                        refreshVisibleContent()
                    } else {
                        _error.value = result.exceptionOrNull()
                    }
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to delete selection - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot delete selection - no file open")
                log(tag, Logging.Priority.WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
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

    suspend fun search(query: String): Result<List<SearchResult>> {
        _searchQuery.value = query

        if (query.isEmpty()) {
            _searchResults.value = emptyList()
            return Result.success(emptyList())
        }

        val currentState = _state.value

        return when (currentState) {
            is EditorState.Loaded -> {
                try {
                    val results = currentState.resources.textBuffer.search(query, _cursorPosition.value, ignoreCase = true)
                    _searchResults.value = results
                    Result.success(results)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to search - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot search - no file open")
                log(tag, Logging.Priority.WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun goToLine(lineNumber: Int): Result<Unit> {
        return try {
            val totalLines = _totalLines.value
            if (lineNumber < 0 || lineNumber >= totalLines) {
                return Result.failure(IllegalArgumentException("Line number out of range"))
            }

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

            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to go to line: $lineNumber - ${e.asLog()}" }
            _error.value = e
            Result.failure(e)
        }
    }

    private suspend fun refreshVisibleContent() {
        val currentState = _state.value as? EditorState.Loaded ?: return
        val currentRange = _visibleRange.value

        try {
            val contentResult = currentState.resources.textBuffer.getTextForRange(
                currentRange.first,
                currentRange.last
            )
            if (contentResult.isSuccess) {
                _currentContent.value = contentResult.getOrNull() ?: ""
                log(tag) { "Refreshed visible content for range: ${currentRange.first}..${currentRange.last}" }
            } else {
                log(tag, Logging.Priority.WARN) { "Failed to refresh content: ${contentResult.exceptionOrNull()?.asLog()}" }
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Error refreshing visible content - ${e.asLog()}" }
        }
    }

    suspend fun updateVisibleRange(startLine: Int, endLine: Int) {
        if (isInitializing) {
            log(tag) { "Ignoring visible range update during initialization: $startLine..$endLine" }
            return
        }

        val currentState = _state.value
        if (currentState !is EditorState.Loaded) {
            log(tag) { "Ignoring visible range update - no file loaded" }
            return
        }

        val totalLines = _totalLines.value
        if (totalLines <= 0) return

        val constrainedStart = startLine.coerceIn(0, totalLines - 1)
        val constrainedEnd = endLine.coerceIn(constrainedStart, totalLines - 1)
        val newRange = constrainedStart..constrainedEnd

        if (_visibleRange.value != newRange) {
            _visibleRange.value = newRange

            // Load content for the new visible range
            try {
                val contentResult = currentState.resources.textBuffer.getTextForRange(constrainedStart, constrainedEnd)
                if (contentResult.isSuccess) {
                    _currentContent.value = contentResult.getOrNull() ?: ""
                    log(tag) { "Loaded content for range: $constrainedStart..$constrainedEnd" }
                } else {
                    log(tag, Logging.Priority.WARN) { "Failed to load content for range: ${contentResult.exceptionOrNull()?.asLog()}" }
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Error loading content for visible range - ${e.asLog()}" }
            }
        }
    }

    suspend fun undo(): Result<EditOperation?> {
        val currentState = _state.value

        return when (currentState) {
            is EditorState.Loaded -> {
                try {
                    val result = currentState.resources.textBuffer.undo()
                    if (result.isSuccess) {
                        _totalLines.value = currentState.resources.textBuffer.totalLines.value
                        refreshVisibleContent()
                    }
                    // Clear search results as they're now stale
                    _searchResults.value = emptyList()
                    _searchQuery.value = ""
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to undo - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot undo - no file open")
                log(tag, Logging.Priority.WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun redo(): Result<EditOperation?> {
        val currentState = _state.value

        return when (currentState) {
            is EditorState.Loaded -> {
                try {
                    val result = currentState.resources.textBuffer.redo()
                    if (result.isSuccess) {
                        _totalLines.value = currentState.resources.textBuffer.totalLines.value
                        refreshVisibleContent()
                    }
                    // Clear search results as they're now stale
                    _searchResults.value = emptyList()
                    _searchQuery.value = ""
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to redo - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot redo - no file open")
                log(tag, Logging.Priority.WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    fun canUndo(): Boolean {
        val currentState = _state.value
        return (currentState as? EditorState.Loaded)?.resources?.textBuffer?.canUndo() ?: false
    }

    fun canRedo(): Boolean {
        val currentState = _state.value
        return (currentState as? EditorState.Loaded)?.resources?.textBuffer?.canRedo() ?: false
    }

    fun clearError() {
        _error.value = null
    }

    suspend fun release() {
        log(tag, INFO) { "release()" }
        val currentState = _state.value
        if (currentState is EditorState.Loaded) {
            try {
                disposeResources(currentState.resources)
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to dispose resources: ${e.asLog()}" }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, filePath: APath<*>?): EditorEngine
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