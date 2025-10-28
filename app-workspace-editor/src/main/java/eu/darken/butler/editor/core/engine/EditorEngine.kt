package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class EditorEngine @Inject constructor(
    private val fileDataSourceFactory: FileDataSource.Factory,
    private val inMemoryDataSourceFactory: InMemoryDataSource.Factory,
    private val chunkRepositoryFactory: ChunkRepository.Factory,
    private val chunkManagerFactory: ChunkManager.Factory,
    private val chunkedTextBufferFactory: ChunkedTextBuffer.Factory,
    private val memoryManager: MemoryManager,
    private val gatewaySwitch: GatewaySwitch,
) {
    private val tag = logTag("Editor", "Engine")

    private data class EditorResources(
        val dataSource: EditorDataSource,
        val chunkRepository: ChunkRepository,
        val chunkManager: ChunkManager,
        val textBuffer: VirtualTextBuffer
    )

    private val _resources = MutableStateFlow<EditorResources?>(null)
    private val resources: StateFlow<EditorResources?> = _resources.asStateFlow()

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

    val fileInfo: Flow<FileInfo?> = resources.flatMapLatest { res ->
        res?.textBuffer?.fileInfo ?: flowOf(null)
    }

    val isModified: Flow<Boolean> = resources.flatMapLatest { res ->
        res?.textBuffer?.isModified ?: flowOf(false)
    }

    val memoryStats: Flow<MemoryStats> = flow {
        emit(memoryManager.getMemoryStats())
    }.catch { emit(MemoryStats(0, 0, 0, 0, 0)) }

    val textBuffer: VirtualTextBuffer?
        get() = _resources.value?.textBuffer

    suspend fun initialize(filePath: APath<*>?, isReadOnly: Boolean = false) {
        try {
            log(tag) { "Initializing editor engine with file: ${filePath?.name ?: "No file"}" }

            // Create data source
            val dataSource = filePath?.let { path ->
                fileDataSourceFactory.create(path, gatewaySwitch)
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
                dataSource = dataSource,
                chunkRepository = chunkRepository,
                chunkManager = chunkManager,
                textBuffer = textBuffer
            )
            _resources.value = resources

            // Initialize based on data source type
            when (dataSource) {
                is FileDataSource -> {
                    log(tag) { "Initializing file data source: $filePath" }

                    val initResult = dataSource.initialize()
                    if (initResult.isFailure) {
                        _error.value = initResult.exceptionOrNull()
                        return
                    }

                    val openResult = textBuffer.openFile(filePath!!)
                    if (openResult.isFailure) {
                        _error.value = openResult.exceptionOrNull()
                        return
                    }

                    log(tag) { "Successfully initialized with file: $filePath" }
                }
                is InMemoryDataSource -> {
                    log(tag) { "Initializing in-memory data source" }

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
            log(tag, Logging.Priority.ERROR) { "Failed to initialize editor engine - ${e.asLog()}" }
            _error.value = e
        }
    }

    suspend fun cleanup() {
        log(tag) { "Cleaning up editor engine" }

        val resources = _resources.value
        resources?.let {
            try {
                it.textBuffer.closeFile()
                it.dataSource.close()
            } catch (e: Exception) {
                log(tag, Logging.Priority.ERROR) { "Error during cleanup - ${e.asLog()}" }
            }
        }

        _resources.value = null
        clearState()
    }

    suspend fun openFile(filePath: APath<*>): Result<Unit> {
        val resources = _resources.value ?: return Result.failure(
            IllegalStateException("Editor engine not initialized")
        )

        return try {
            log(tag) { "Opening file: $filePath" }

            val result = resources.textBuffer.openFile(filePath)
            if (result.isFailure) {
                _error.value = result.exceptionOrNull()
                result
            } else {
                log(tag) { "Successfully opened file: $filePath" }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            log(tag, Logging.Priority.ERROR) { "Failed to open file: $filePath - ${e.asLog()}" }
            _error.value = e
            Result.failure(e)
        }
    }

    suspend fun closeFile(): Result<Unit> {
        val resources = _resources.value ?: return Result.failure(
            IllegalStateException("Editor engine not initialized")
        )

        return try {
            resources.textBuffer.closeFile()
            clearState()
            log(tag) { "File closed" }
            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, Logging.Priority.ERROR) { "Failed to close file - ${e.asLog()}" }
            _error.value = e
            Result.failure(e)
        }
    }

    suspend fun saveFile(): Result<Unit> {
        val resources = _resources.value ?: return Result.failure(
            IllegalStateException("Editor engine not initialized")
        )

        return try {
            val result = resources.textBuffer.saveFile()
            if (result.isFailure) {
                _error.value = result.exceptionOrNull()
            }
            result
        } catch (e: Exception) {
            log(tag, Logging.Priority.ERROR) { "Failed to save file - ${e.asLog()}" }
            _error.value = e
            Result.failure(e)
        }
    }

    fun insertText(text: String) {
        val resources = _resources.value
        if (resources == null) {
            log(tag, Logging.Priority.WARN) { "Cannot insert text - no resources available" }
            return
        }

        // TEMPORARY FIX: Bypass complex text buffer and directly update content
        val currentContent = _currentContent.value
        val currentPos = _cursorPosition.value

        val beforeCursor = currentContent.substring(0, currentPos.offset.toInt().coerceIn(0, currentContent.length))
        val afterCursor = currentContent.substring(currentPos.offset.toInt().coerceIn(0, currentContent.length))
        val newContent = beforeCursor + text + afterCursor

        _currentContent.value = newContent

        val lines = if (newContent.isEmpty()) 1 else newContent.split('\n').size
        _totalLines.value = lines

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

    suspend fun deleteSelection(): Result<String> {
        val resources = _resources.value ?: return Result.failure(
            IllegalStateException("Editor engine not initialized")
        )

        val selection = _selectionRange.value ?: return Result.failure(
            IllegalStateException("No selection to delete")
        )

        return try {
            val result = resources.textBuffer.deleteText(selection.first, selection.second)
            if (result.isSuccess) {
                _selectionRange.value = null
                _cursorPosition.value = selection.first
            } else {
                _error.value = result.exceptionOrNull()
            }
            result
        } catch (e: Exception) {
            log(tag, Logging.Priority.ERROR) { "Failed to delete selection - ${e.asLog()}" }
            _error.value = e
            Result.failure(e)
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

        val resources = _resources.value ?: return Result.failure(
            IllegalStateException("Editor engine not initialized")
        )

        return try {
            val results = resources.textBuffer.search(query, _cursorPosition.value, ignoreCase = true)
            _searchResults.value = results
            Result.success(results)
        } catch (e: Exception) {
            log(tag, Logging.Priority.ERROR) { "Failed to search - ${e.asLog()}" }
            _error.value = e
            Result.failure(e)
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
            log(tag, Logging.Priority.ERROR) { "Failed to go to line: $lineNumber - ${e.asLog()}" }
            _error.value = e
            Result.failure(e)
        }
    }

    fun updateVisibleRange(startLine: Int, endLine: Int) {
        val totalLines = _totalLines.value
        if (totalLines <= 0) return

        val constrainedStart = startLine.coerceIn(0, totalLines - 1)
        val constrainedEnd = endLine.coerceIn(constrainedStart, totalLines - 1)
        val newRange = constrainedStart..constrainedEnd

        if (_visibleRange.value != newRange) {
            _visibleRange.value = newRange
        }
    }

    suspend fun undo(): Result<EditOperation?> {
        val resources = _resources.value ?: return Result.failure(
            IllegalStateException("Editor engine not initialized")
        )

        return try {
            resources.textBuffer.undo()
        } catch (e: Exception) {
            log(tag, Logging.Priority.ERROR) { "Failed to undo - ${e.asLog()}" }
            _error.value = e
            Result.failure(e)
        }
    }

    suspend fun redo(): Result<EditOperation?> {
        val resources = _resources.value ?: return Result.failure(
            IllegalStateException("Editor engine not initialized")
        )

        return try {
            resources.textBuffer.redo()
        } catch (e: Exception) {
            log(tag, Logging.Priority.ERROR) { "Failed to redo - ${e.asLog()}" }
            _error.value = e
            Result.failure(e)
        }
    }

    fun canUndo(): Boolean = _resources.value?.textBuffer?.canUndo() ?: false

    fun canRedo(): Boolean = _resources.value?.textBuffer?.canRedo() ?: false

    fun clearError() {
        _error.value = null
    }

    private fun clearState() {
        _currentContent.value = ""
        _cursorPosition.value = TextPosition.Companion.ZERO
        _selectionRange.value = null
        _error.value = null
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _visibleRange.value = 0..50
        _totalLines.value = 1
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