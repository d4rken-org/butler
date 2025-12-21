package eu.darken.butler.editor.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.editor.core.EditorSettings
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.editor.ui.editor.text.CursorDirection
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Source
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class EditorEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val filePath: APath<*>?,
    @Assisted private val initialContent: String?,
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

    private val _cursorPosition = MutableStateFlow(TextPosition.ZERO)
    val cursorPosition: StateFlow<TextPosition> = _cursorPosition.asStateFlow()

    private val _selectionRange = MutableStateFlow<Pair<TextPosition, TextPosition>?>(null)
    val selectionRange: StateFlow<Pair<TextPosition, TextPosition>?> = _selectionRange.asStateFlow()

    // Selection anchor for shift+arrow key selection
    private var selectionAnchor: TextPosition? = null

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

    private val isInitializing = AtomicBoolean(true)
    private var initializationJob: Job? = null

    val contentSource: Flow<ContentSource> = state.map { s ->
        when (s) {
            is EditorState.Loaded -> s.contentSource
            else -> ContentSource.Memory(size = 0L)
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
            fileDataSourceFactory.create(
                workspaceId = workspaceId,
                filePath = filePath,
                gatewaySwitch = gatewaySwitch
            )
        } else {
            inMemoryDataSourceFactory.create(
                workspaceId = workspaceId,
                initialContent = initialContent ?: ""
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
        // Capture the job for cancellation support
        initializationJob = currentCoroutineContext()[Job]

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
            currentCoroutineContext().ensureActive()

            // Initialize text buffer
            val bufferInitResult = resources.textBuffer.initialize()
            currentCoroutineContext().ensureActive()
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
                currentCoroutineContext().ensureActive()
                val contentResult = resources.textBuffer.getTextForRange(0, endLine)
                if (contentResult.isSuccess) {
                    _currentContent.value = contentResult.getOrNull() ?: ""
                }
            } else {
                _visibleRange.value = 0..0
                _currentContent.value = ""
            }

            // Transition to Loaded state
            val contentSourceValue = resources.textBuffer.contentSource.value
            val isModifiedValue = resources.textBuffer.isModified.value
            _state.value = EditorState.Loaded(
                filePath = filePath,
                resources = resources,
                contentSource = contentSourceValue,
                isModified = isModifiedValue,
            )

            log(tag) { "Successfully initialized engine with: ${filePath?.name ?: "in-memory editor"}" }
            isInitializing.set(false)
            initializationJob = null
            Result.success(Unit)

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to initialize engine: ${filePath?.name} - ${e.asLog()}" }
            _state.value = EditorState.Error(e, _state.value)
            _error.value = e
            initializationJob = null
            Result.failure(e)
        }
    }

    /**
     * Cancels an in-progress file initialization.
     * Safe to call even if no initialization is running.
     */
    fun cancelInitialization() {
        initializationJob?.let { job ->
            log(tag, INFO) { "Cancelling initialization" }
            job.cancel()
            initializationJob = null
            _state.value = EditorState.Empty
        }
    }

    suspend fun saveFile(): Result<Unit> = stateMutex.withLock {
        return when (val currentState = _state.value) {
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
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    /**
     * Gets a content stream for reading the current document content.
     * Engine exposes content, Workspace handles file I/O operations.
     *
     * @return Source for streaming content
     * @throws IllegalStateException if no content is loaded
     */
    suspend fun getContentStream(): Source = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                log(tag) { "Opening content stream for reading" }
                currentState.resources.dataSource.openSource()
            }
            else -> {
                throw IllegalStateException("Cannot get content stream - no content available")
            }
        }
    }

    suspend fun insertText(text: String) = stateMutex.withLock {
        when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                val cursorPos = _cursorPosition.value

                // Recalculate correct offset from line/column using chunk metadata
                // UI may send placeholder offset=0 with virtual scrolling
                val correctedOffset = currentState.resources.textBuffer.findOffset(
                    cursorPos.line,
                    cursorPos.column
                )

                val correctedPosition = TextPosition(
                    offset = correctedOffset,
                    line = cursorPos.line,
                    column = cursorPos.column
                )

                log(tag, VERBOSE) { "Inserting text at position $correctedPosition: ${text.take(50)}..." }

                val result = currentState.resources.textBuffer.insertText(correctedPosition, text)

                result.fold(
                    onSuccess = { newPosition ->
                        log(tag, VERBOSE) { "Text inserted successfully, new position: $newPosition" }

                        // Update cursor position from result
                        _cursorPosition.value = newPosition

                        // Mark as modified
                        _state.value = currentState.copy(isModified = true)

                        // Update total lines from text buffer
                        _totalLines.value = currentState.resources.textBuffer.totalLines.value

                        // Invalidate search results (positions are now stale)
                        invalidateSearchResults()

                        // Update visible content - use in-place update for small edits
                        if (text.length <= 10 && !text.contains('\n')) {
                            val cursorLine = correctedPosition.line
                            val visibleStart = _visibleRange.value.first
                            if (cursorLine in _visibleRange.value) {
                                val lines = _currentContent.value.split('\n').toMutableList()
                                val lineIndex = cursorLine - visibleStart
                                if (lineIndex in lines.indices) {
                                    val line = lines[lineIndex]
                                    val col = correctedPosition.column.coerceAtMost(line.length)
                                    lines[lineIndex] = line.substring(0, col) + text + line.substring(col)
                                    _currentContent.value = lines.joinToString("\n")
                                } else {
                                    refreshVisibleContent()
                                }
                            } else {
                                refreshVisibleContent()
                            }
                        } else {
                            refreshVisibleContent()
                        }
                    },
                    onFailure = { e ->
                        log(tag, ERROR) { "Failed to insert text - ${e.asLog()}" }
                        _error.value = e
                    }
                )
            }
            else -> {
                log(tag, WARN) { "Cannot insert text - no file open" }
            }
        }
    }

    suspend fun deleteSelection(): Result<String> = stateMutex.withLock {
        return when (val currentState = _state.value) {
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
                        // Invalidate search results (positions are now stale)
                        invalidateSearchResults()
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
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun deleteAtCursor(count: Int): Result<String> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                if (count <= 0) {
                    return Result.success("")
                }

                val cursorPos = _cursorPosition.value

                // Calculate start position, clamped to 0
                val startOffset = (cursorPos.offset - count).coerceAtLeast(0L)
                val actualCount = (cursorPos.offset - startOffset).toInt()

                if (actualCount <= 0) {
                    // Nothing to delete (cursor at start of document)
                    return Result.success("")
                }

                try {
                    // Find the line/column for start position
                    val startPosition = currentState.resources.textBuffer.findPosition(startOffset)
                    val endPosition = cursorPos

                    log(tag, VERBOSE) { "Deleting $actualCount characters at cursor: $startPosition to $endPosition" }

                    val result = currentState.resources.textBuffer.deleteText(startPosition, endPosition)
                    if (result.isSuccess) {
                        val deletedText = result.getOrNull() ?: ""
                        _cursorPosition.value = startPosition
                        _state.value = currentState.copy(isModified = true)
                        _totalLines.value = currentState.resources.textBuffer.totalLines.value
                        invalidateSearchResults()

                        // Update visible content - use in-place update for small single-line deletes
                        if (actualCount <= 10 && !deletedText.contains('\n') && startPosition.line == endPosition.line) {
                            val cursorLine = startPosition.line
                            val visibleStart = _visibleRange.value.first
                            if (cursorLine in _visibleRange.value) {
                                val lines = _currentContent.value.split('\n').toMutableList()
                                val lineIndex = cursorLine - visibleStart
                                if (lineIndex in lines.indices) {
                                    val line = lines[lineIndex]
                                    val startCol = startPosition.column.coerceAtMost(line.length)
                                    val endCol = endPosition.column.coerceAtMost(line.length)
                                    lines[lineIndex] = line.substring(0, startCol) + line.substring(endCol)
                                    _currentContent.value = lines.joinToString("\n")
                                } else {
                                    refreshVisibleContent()
                                }
                            } else {
                                refreshVisibleContent()
                            }
                        } else {
                            refreshVisibleContent()
                        }
                    } else {
                        _error.value = result.exceptionOrNull()
                    }
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to delete at cursor - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot delete at cursor - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun copySelection(): Result<String> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                val selection = _selectionRange.value ?: return Result.failure(
                    IllegalStateException("No selection to copy")
                )

                try {
                    log(tag) { "Copying selection: ${selection.first} to ${selection.second}" }
                    currentState.resources.textBuffer.getText(selection.first.offset, selection.second.offset)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to copy selection - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot copy selection - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun selectAll(): Result<Pair<TextPosition, TextPosition>> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                try {
                    val startPosition = TextPosition(offset = 0, line = 0, column = 0)

                    val totalLength = currentState.resources.textBuffer.totalLength.value
                    val totalLines = _totalLines.value

                    // Get the last line to calculate its length for the column
                    val lastLineNumber = (totalLines - 1).coerceAtLeast(0)
                    val lastLineResult = currentState.resources.textBuffer.getTextForLine(lastLineNumber)
                    val lastLineLength = lastLineResult.getOrNull()?.length ?: 0

                    val endPosition = TextPosition(
                        offset = totalLength,
                        line = lastLineNumber,
                        column = lastLineLength
                    )

                    log(tag) { "Selecting all text: $startPosition to $endPosition" }

                    val selection = startPosition to endPosition
                    _selectionRange.value = selection

                    Result.success(selection)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to select all - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot select all - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun setCursorPosition(position: TextPosition) = stateMutex.withLock {
        val correctedPosition = when (val currentState = _state.value) {
            is EditorState.Loaded -> TextPosition(
                offset = currentState.resources.textBuffer.findOffset(position.line, position.column),
                line = position.line,
                column = position.column
            )
            else -> position
        }
        _cursorPosition.value = correctedPosition
        _selectionRange.value = null
    }

    suspend fun setSelection(start: TextPosition, end: TextPosition) = stateMutex.withLock {
        when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                // Recalculate actual offsets from line/column positions
                // UI may send placeholder offset=0 with virtual scrolling
                val correctedStart = TextPosition(
                    offset = currentState.resources.textBuffer.findOffset(start.line, start.column),
                    line = start.line,
                    column = start.column
                )
                val correctedEnd = TextPosition(
                    offset = currentState.resources.textBuffer.findOffset(end.line, end.column),
                    line = end.line,
                    column = end.column
                )
                _selectionRange.value = correctedStart to correctedEnd
            }
            else -> {
                // No file loaded, store as-is
                _selectionRange.value = start to end
            }
        }
    }

    suspend fun moveCursor(direction: CursorDirection, extendSelection: Boolean) = stateMutex.withLock {
        log(tag) { "moveCursor(direction=$direction, extendSelection=$extendSelection)" }
        val currentState = _state.value as? EditorState.Loaded
        if (currentState == null) {
            log(tag, WARN) { "moveCursor: No file loaded, ignoring" }
            return
        }
        val currentPos = _cursorPosition.value
        log(tag) { "moveCursor: currentPos=$currentPos" }

        // Set anchor if starting selection
        if (extendSelection && selectionAnchor == null) {
            selectionAnchor = currentPos
            log(tag) { "moveCursor: Set selection anchor to $currentPos" }
        }

        // Calculate new position based on direction
        val newPos = when (direction) {
            CursorDirection.LEFT -> moveCursorLeft(currentPos, currentState)
            CursorDirection.RIGHT -> moveCursorRight(currentPos, currentState)
            CursorDirection.UP -> moveCursorUp(currentPos, currentState)
            CursorDirection.DOWN -> moveCursorDown(currentPos, currentState)
            CursorDirection.WORD_LEFT -> moveCursorWordLeft(currentPos, currentState)
            CursorDirection.WORD_RIGHT -> moveCursorWordRight(currentPos, currentState)
            CursorDirection.LINE_START -> moveCursorToLineStart(currentPos, currentState)
            CursorDirection.LINE_END -> moveCursorToLineEnd(currentPos, currentState)
        }

        log(tag) { "moveCursor: newPos=$newPos (was $currentPos)" }
        _cursorPosition.value = newPos

        if (extendSelection) {
            // Update selection from anchor to cursor
            val anchor = selectionAnchor!!
            _selectionRange.value = if (anchor.offset <= newPos.offset) {
                anchor to newPos
            } else {
                newPos to anchor
            }
            log(tag) { "moveCursor: Selection updated to ${_selectionRange.value}" }
        } else {
            // Clear selection and anchor
            selectionAnchor = null
            _selectionRange.value = null
        }
    }

    private suspend fun moveCursorLeft(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        return if (pos.column > 0) {
            // Move left within line
            val newOffset = state.resources.textBuffer.findOffset(pos.line, pos.column - 1)
            TextPosition(offset = newOffset, line = pos.line, column = pos.column - 1)
        } else if (pos.line > 0) {
            // Move to end of previous line
            val prevLineLength = getLineLength(pos.line - 1, state)
            val newOffset = state.resources.textBuffer.findOffset(pos.line - 1, prevLineLength)
            TextPosition(offset = newOffset, line = pos.line - 1, column = prevLineLength)
        } else {
            // Already at start of document
            pos
        }
    }

    private suspend fun moveCursorRight(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val lineLength = getLineLength(pos.line, state)
        val totalLines = _totalLines.value

        return if (pos.column < lineLength) {
            // Move right within line
            val newOffset = state.resources.textBuffer.findOffset(pos.line, pos.column + 1)
            TextPosition(offset = newOffset, line = pos.line, column = pos.column + 1)
        } else if (pos.line < totalLines - 1) {
            // Move to start of next line
            val newOffset = state.resources.textBuffer.findOffset(pos.line + 1, 0)
            TextPosition(offset = newOffset, line = pos.line + 1, column = 0)
        } else {
            // Already at end of document
            pos
        }
    }

    private suspend fun moveCursorUp(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        return if (pos.line > 0) {
            val newLine = pos.line - 1
            val prevLineLength = getLineLength(newLine, state)
            val newColumn = minOf(pos.column, prevLineLength)
            val newOffset = state.resources.textBuffer.findOffset(newLine, newColumn)
            TextPosition(offset = newOffset, line = newLine, column = newColumn)
        } else {
            // Already on first line
            pos
        }
    }

    private suspend fun moveCursorDown(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val totalLines = _totalLines.value
        return if (pos.line < totalLines - 1) {
            val newLine = pos.line + 1
            val nextLineLength = getLineLength(newLine, state)
            val newColumn = minOf(pos.column, nextLineLength)
            val newOffset = state.resources.textBuffer.findOffset(newLine, newColumn)
            TextPosition(offset = newOffset, line = newLine, column = newColumn)
        } else {
            // Already on last line
            pos
        }
    }

    private suspend fun moveCursorWordLeft(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val lineContent = getLineContent(pos.line, state)
        var column = pos.column
        var line = pos.line

        // Skip whitespace backwards
        while (column > 0 && lineContent.getOrNull(column - 1)?.isWhitespace() == true) {
            column--
        }

        // If at start of line, move to end of previous line
        if (column == 0 && line > 0) {
            line--
            val prevLineContent = getLineContent(line, state)
            column = prevLineContent.length
            // Skip whitespace at end of previous line
            while (column > 0 && prevLineContent.getOrNull(column - 1)?.isWhitespace() == true) {
                column--
            }
            // Skip word chars
            while (column > 0 && prevLineContent.getOrNull(column - 1)?.isWordChar() == true) {
                column--
            }
        } else {
            // Skip word characters backwards
            while (column > 0 && lineContent.getOrNull(column - 1)?.isWordChar() == true) {
                column--
            }
        }

        val newOffset = state.resources.textBuffer.findOffset(line, column)
        return TextPosition(offset = newOffset, line = line, column = column)
    }

    private suspend fun moveCursorWordRight(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val lineContent = getLineContent(pos.line, state)
        var column = pos.column
        var line = pos.line
        val totalLines = _totalLines.value

        // Skip word chars forwards
        while (column < lineContent.length && lineContent.getOrNull(column)?.isWordChar() == true) {
            column++
        }

        // Skip whitespace forwards
        while (column < lineContent.length && lineContent.getOrNull(column)?.isWhitespace() == true) {
            column++
        }

        // If at end of line, move to start of next line
        if (column >= lineContent.length && line < totalLines - 1) {
            line++
            column = 0
            val nextLineContent = getLineContent(line, state)
            // Skip leading whitespace
            while (column < nextLineContent.length && nextLineContent.getOrNull(column)?.isWhitespace() == true) {
                column++
            }
        }

        val newOffset = state.resources.textBuffer.findOffset(line, column)
        return TextPosition(offset = newOffset, line = line, column = column)
    }

    private suspend fun moveCursorToLineStart(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val newOffset = state.resources.textBuffer.findOffset(pos.line, 0)
        return TextPosition(offset = newOffset, line = pos.line, column = 0)
    }

    private suspend fun moveCursorToLineEnd(pos: TextPosition, state: EditorState.Loaded): TextPosition {
        val lineLength = getLineLength(pos.line, state)
        val newOffset = state.resources.textBuffer.findOffset(pos.line, lineLength)
        return TextPosition(offset = newOffset, line = pos.line, column = lineLength)
    }

    private suspend fun getLineLength(lineNumber: Int, state: EditorState.Loaded): Int {
        val result = state.resources.textBuffer.getTextForLine(lineNumber)
        return result.getOrNull()?.length ?: 0
    }

    private suspend fun getLineContent(lineNumber: Int, state: EditorState.Loaded): String {
        val result = state.resources.textBuffer.getTextForLine(lineNumber)
        return result.getOrNull() ?: ""
    }

    private fun Char.isWordChar(): Boolean {
        return this.isLetterOrDigit() || this == '_'
    }

    suspend fun deleteForward(): Result<String> = stateMutex.withLock {
        val currentState = _state.value as? EditorState.Loaded
            ?: return Result.failure(IllegalStateException("Cannot delete forward - no file open"))

        val cursorPos = _cursorPosition.value
        val totalLength = currentState.resources.textBuffer.totalLength.value

        if (cursorPos.offset >= totalLength) {
            return Result.success("") // Nothing to delete at end
        }

        // Delete 1 character forward (from cursor to cursor+1)
        val endPosition = currentState.resources.textBuffer.findPosition(cursorPos.offset + 1)

        log(tag, VERBOSE) { "Forward delete at $cursorPos to $endPosition" }

        val result = currentState.resources.textBuffer.deleteText(cursorPos, endPosition)
        if (result.isSuccess) {
            _state.value = currentState.copy(isModified = true)
            _totalLines.value = currentState.resources.textBuffer.totalLines.value
            invalidateSearchResults()
            refreshVisibleContent()
        } else {
            _error.value = result.exceptionOrNull()
        }
        return result
    }

    suspend fun search(query: String, options: SearchOptions = SearchOptions()): Result<List<SearchResult>> = stateMutex.withLock {
            _searchQuery.value = query

            if (query.isEmpty()) {
                _searchResults.value = emptyList()
                return Result.success(emptyList())
            }

            return when (val currentState = _state.value) {
                is EditorState.Loaded -> {
                    try {
                        coroutineContext.ensureActive()
                        val results =
                            currentState.resources.textBuffer.search(
                                query,
                                _cursorPosition.value,
                                options
                            )
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
                    log(tag, WARN) { error.message ?: "Unknown error" }
                    Result.failure(error)
                }
            }
        }

    suspend fun goToLine(lineNumber: Int): Result<Unit> {
        return try {
            val currentState = _state.value as? EditorState.Loaded
                ?: return Result.failure(IllegalStateException("Cannot go to line - no file open"))

            val totalLines = _totalLines.value
            if (lineNumber !in 0..<totalLines) {
                return Result.failure(
                    IllegalArgumentException("Line $lineNumber out of range (0..$totalLines)")
                )
            }

            // Use textBuffer to find correct offset (works for any line, not just visible range)
            val offset = currentState.resources.textBuffer.findOffset(lineNumber, 0)

            val position = TextPosition(
                offset = offset,
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

    private fun invalidateSearchResults() {
        _searchResults.value = emptyList()
        _searchQuery.value = ""
    }

    private suspend fun refreshVisibleContent() {
        val currentState = _state.value as? EditorState.Loaded ?: return
        val currentRange = _visibleRange.value

        try {
            currentCoroutineContext().ensureActive()
            val contentResult = currentState.resources.textBuffer.getTextForRange(
                currentRange.first,
                currentRange.last
            )
            if (contentResult.isSuccess) {
                _currentContent.value = contentResult.getOrNull() ?: ""
                log(tag) { "Refreshed visible content for range: ${currentRange.first}..${currentRange.last}" }
            } else {
                log(tag, WARN) { "Failed to refresh content: ${contentResult.exceptionOrNull()?.asLog()}" }
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Error refreshing visible content - ${e.asLog()}" }
        }
    }

    suspend fun updateVisibleRange(startLine: Int, endLine: Int) = stateMutex.withLock {
        if (isInitializing.get()) {
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
                    log(tag, WARN) { "Failed to load content for range: ${contentResult.exceptionOrNull()?.asLog()}" }
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Error loading content for visible range - ${e.asLog()}" }
            }
        }
    }

    suspend fun undo(): Result<EditOperation?> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                try {
                    val result = currentState.resources.textBuffer.undo()
                    if (result.isSuccess) {
                        _totalLines.value = currentState.resources.textBuffer.totalLines.value
                        invalidateSearchResults()
                        refreshVisibleContent()
                    }
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to undo - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot undo - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
                Result.failure(error)
            }
        }
    }

    suspend fun redo(): Result<EditOperation?> = stateMutex.withLock {
        return when (val currentState = _state.value) {
            is EditorState.Loaded -> {
                try {
                    val result = currentState.resources.textBuffer.redo()
                    if (result.isSuccess) {
                        _totalLines.value = currentState.resources.textBuffer.totalLines.value
                        invalidateSearchResults()
                        refreshVisibleContent()
                    }
                    result
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to redo - ${e.asLog()}" }
                    _error.value = e
                    Result.failure(e)
                }
            }
            else -> {
                val error = IllegalStateException("Cannot redo - no file open")
                log(tag, WARN) { error.message ?: "Unknown error" }
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

    suspend fun release() = stateMutex.withLock {
        log(tag, INFO) { "release()" }
        val currentState = _state.value
        if (currentState is EditorState.Loaded) {
            try {
                disposeResources(currentState.resources)
                _state.value = EditorState.Empty
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to dispose resources: ${e.asLog()}" }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, filePath: APath<*>?, initialContent: String? = null): EditorEngine
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