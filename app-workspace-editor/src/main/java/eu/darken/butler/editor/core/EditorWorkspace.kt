package eu.darken.butler.editor.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.common.flow.combine
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.FileInfo
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.parcelize.Parcelize
import okio.buffer
import okio.use


class EditorWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: Arguments?,
    private val gatewaySwitch: GatewaySwitch,
    private val editorEngineFactory: EditorEngine.Factory,
    private val editorSettings: EditorSettings,
    private val operationsManager: OperationsManager,
) : Workspace {

    private val tag = logTag("Editor", "Workspace", id.shortTag)

    private val workspaceScope = CoroutineScope(
        SupervisorJob() +
            CoroutineExceptionHandler { _, throwable ->
                log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
                // Error handled by editorEngine.error StateFlow
            }
    )

    override val type: Workspace.Type = Workspace.Type.EDITOR

    private val _info = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = generateTitle(),
        )
    )
    override val info: MutableStateFlow<Workspace.Info> = _info

    val filePath: APath<*>? get() = arguments?.filePath

    private val engineHolder = DynamicStateFlow<EditorEngine>(
        loggingTag = tag,
        parentScope = workspaceScope,
        startValueProvider = {
            val initialPath = arguments?.filePath ?: LocalPath.build("/sdcard/test-3MB.log")
            log(tag, INFO) { "Creating initial engine with: ${initialPath?.name ?: "scratch buffer"}" }
            editorEngineFactory.create(id, initialPath).apply {
                initialize().getOrThrow()
            }
        },
        onRelease = { engine ->
            launch {
                try {
                    log(tag, VERBOSE) { "DynamicStateFlow releasing engine" }
                    engine.release()
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to release engine in onRelease: ${e.asLog()}" }
                }
            }
        }
    )

    // Combined editor state for UI
    val editorState: Flow<EditorState> = engineHolder.flow.flatMapLatest { engine ->
        combine(
            engine.fileInfo,
            engine.totalLines,
            engine.isModified,
            engine.currentContent,
            engine.cursorPosition,
            engine.selectionRange,
            engine.searchQuery,
            engine.searchResults,
            engine.visibleRange,
            engine.error,
            editorSettings.showLineNumbers.flow,
            editorSettings.wordWrap.flow,
        ) { fileInfo, totalLines, isModified, currentContent, cursorPosition,
            selectionRange, searchQuery, searchResults, visibleRange, error,
            showLineNumbers, wordWrap ->
            EditorState(
                fileInfo = fileInfo,
                totalLines = totalLines,
                isModified = isModified,
                currentContent = currentContent,
                cursorPosition = cursorPosition,
                selectionRange = selectionRange,
                searchQuery = searchQuery,
                searchResults = searchResults,
                visibleRange = visibleRange,
                error = error,
                showLineNumbers = showLineNumbers,
                wordWrap = wordWrap
            )
        }
    }

    init {
        log(tag, INFO) { "Initialized with file: ${filePath?.name ?: "No file"}" }

        // Track operation counts for this workspace
        operationsManager.operationsForWorkspace(id).withOnlyStateChanges()
            .onEach { operations ->
                var operationCount = 0
                var attentionCount = 0

                operations.forEach { operation ->
                    when (val state = operation.state.value) {
                        is Operation.State.Queued -> operationCount++
                        is Operation.State.Active -> operationCount++
                        is Operation.State.Waiting -> {
                            operationCount++
                            attentionCount++
                        }
                        is Operation.State.Completed -> {
                            if (state.error != null && state.error !is CancellationException) {
                                attentionCount++
                            }
                        }
                    }
                }

                _info.value = _info.value.copy(
                    operationCount = operationCount,
                    attentionCount = attentionCount
                )
                log(tag, VERBOSE) { "Updated operation counts: active=$operationCount, attention=$attentionCount" }
            }
            .launchIn(workspaceScope)

        // Update title based on file info
        workspaceScope.launch {
            engineHolder.flow.flatMapLatest { engine ->
                engine.fileInfo
            }.collect { info ->
                updateFileInfo(info)
            }
        }
    }

    fun updateTitle(fileName: String? = null) {
        val newTitle = when {
            fileName != null -> fileName
            filePath != null -> filePath!!.name
            else -> "Editor ${id.shortTag}"
        }

        _info.value = _info.value.copy(title = newTitle.toCaString())
        log(tag, DEBUG) { "Updated title to: $newTitle" }
    }

    fun updateFileInfo(fileInfo: FileInfo?) {
        fileInfo?.let { info ->
            updateTitle(info.path.name)
        }
    }

    private fun generateTitle(): CaString {
        return when {
            arguments?.filePath != null -> arguments.filePath.name.toCaString()
            else -> "Editor ${id.shortTag}".toCaString()
        }
    }

    private suspend fun switchEngine(newFilePath: APath<*>?) {
        log(tag, INFO) { "Switching engine to: ${newFilePath?.name ?: "scratch buffer"}" }

        engineHolder.updateBlocking {
            // 'this' is the old engine (receiver of extension function)
            // Create and initialize new engine
            val newEngine = editorEngineFactory.create(id, newFilePath)
            val initResult = newEngine.initialize()

            if (initResult.isFailure) {
                newEngine.release()
                val error = initResult.exceptionOrNull() ?: Exception("Failed to initialize engine")
                log(tag, ERROR) { "Failed to switch engine: ${error.asLog()}" }
                throw error
            }

            log(tag, DEBUG) { "Engine switched successfully" }
            // Old engine (this) cleanup happens automatically via onRelease callback
            newEngine
        }
    }

    // Editor operations
    suspend fun openFile(filePath: APath<*>) = switchEngine(filePath)
    suspend fun closeFile() = switchEngine(null)
    suspend fun saveFile() = engineHolder.value().saveFile()
    suspend fun saveFileAs(newFilePath: APath<*>): Result<Unit> {
        val engine = engineHolder.value()

        return try {
            log(tag) { "Saving as: ${newFilePath.name}" }

            // Get content stream from engine (Engine manages content)
            val source = engine.getContentStream()

            // Workspace handles file I/O operations
            source.use {
                gatewaySwitch.file(newFilePath, readWrite = true).use { handle ->
                    handle.sink().buffer().use { sink ->
                        sink.writeAll(source)
                    }
                }
            }

            log(tag) { "Content written to: ${newFilePath.name}" }

            // Switch to new engine with the new file path
            switchEngine(newFilePath)

            Result.success(Unit)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to save as: ${newFilePath.name} - ${e.asLog()}" }
            Result.failure(e)
        }
    }
    suspend fun search(query: String) = engineHolder.value().search(query)
    suspend fun goToLine(lineNumber: Int) = engineHolder.value().goToLine(lineNumber)
    suspend fun undo() = engineHolder.value().undo()
    suspend fun redo() = engineHolder.value().redo()
    suspend fun deleteSelection() = engineHolder.value().deleteSelection()
    suspend fun copySelection() = engineHolder.value().copySelection()
    suspend fun selectAll() = engineHolder.value().selectAll()

    suspend fun insertText(text: String) = engineHolder.value().insertText(text)
    suspend fun setCursorPosition(position: TextPosition) = engineHolder.value().setCursorPosition(position)
    suspend fun setSelection(start: TextPosition, end: TextPosition) = engineHolder.value().setSelection(start, end)
    suspend fun updateVisibleRange(startLine: Int, endLine: Int) = engineHolder.value().updateVisibleRange(startLine, endLine)
    fun clearError() = runBlocking { engineHolder.value().clearError() }
    fun canUndo() = runBlocking { engineHolder.value().canUndo() }
    fun canRedo() = runBlocking { engineHolder.value().canRedo() }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        workspaceScope.cancel()
        // DynamicStateFlow's onRelease callback handles engine cleanup automatically
    }

    @Parcelize
    data class Arguments(
        val filePath: APath<*>? = null,
        val goToLine: Int? = null,
    ) : Workspace.Arguments {
        override val type: Workspace.Type
            get() = Workspace.Type.EDITOR
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id, arguments: Arguments?): EditorWorkspace
    }

    data class EditorState(
        val fileInfo: FileInfo? = null,
        val totalLines: Int = 0,
        val isModified: Boolean = false,
        val currentContent: String = "",
        val cursorPosition: TextPosition = TextPosition.ZERO,
        val selectionRange: Pair<TextPosition, TextPosition>? = null,
        val searchQuery: String = "",
        val searchResults: List<SearchResult> = emptyList(),
        val visibleRange: IntRange = 0..50,
        val error: Throwable? = null,
        val showLineNumbers: Boolean = true,
        val wordWrap: Boolean = false
    ) {
        val hasFile: Boolean get() = fileInfo != null
        val fileName: String get() = fileInfo?.path?.name ?: "Untitled"
        val hasSelection: Boolean get() = selectionRange != null
        val hasSearchResults: Boolean get() = searchResults.isNotEmpty()
        val isSearchActive: Boolean get() = searchQuery.isNotEmpty()
        val hasError: Boolean get() = error != null
    }
}