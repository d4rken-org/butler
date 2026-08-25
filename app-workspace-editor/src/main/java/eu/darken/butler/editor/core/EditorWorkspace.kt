package eu.darken.butler.editor.core

import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.exists
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.flow.combine as combineMany
import eu.darken.butler.editor.core.syntax.Token
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.EditorState as EngineState
import eu.darken.butler.editor.core.engine.LineEnding
import eu.darken.butler.editor.core.engine.ReadOnlyFileException
import eu.darken.butler.editor.core.engine.SearchOptions
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.ui.editor.text.CursorDirection
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceDisplay
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import eu.darken.butler.common.files.write.AtomicFileWriter
import eu.darken.butler.editor.core.sources.CommitIntegrityException
import java.nio.charset.Charset
import kotlin.uuid.Uuid


class EditorWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: EditorArguments,
    private val gatewaySwitch: GatewaySwitch,
    private val editorEngineFactory: EditorEngine.Factory,
    private val editorSettings: EditorSettings,
    private val operationsManager: OperationsManager,
    private val pasteFileReader: PasteFileReader,
) : Workspace<EditorArguments> {

    private val tag = logTag("Editor", "Workspace", id.shortTag)
    private val atomicFileWriter = AtomicFileWriter(gatewaySwitch, tag, ::CommitIntegrityException)

    override suspend fun createArguments(): EditorArguments {
        // A failed or still-loading file engine must not demote the tab to a scratch buffer:
        // the engine's target path is the file identity even while contentSource reads Memory.
        // Empty means no file is attached - either a scratch tab or a load the user cancelled -
        // so only then does the persisted tab drop the path.
        val engine = _engine.value ?: return creationArguments
        val ready = (_state.value as? State.Ready)?.editor
        val args = creationArguments as? EditorArguments.Default
        return EditorArguments.Default(
            filePath = if (engine.state.value is EngineState.Empty) null else engine.filePath,
            cursorLine = ready?.cursorPosition?.line ?: args?.cursorLine,
            cursorColumn = ready?.cursorPosition?.column ?: args?.cursorColumn,
            scrollToLine = ready?.visibleRange?.first ?: args?.scrollToLine,
            // Without this a restored scratch tab loses its name and falls back to "Untitled"
            suggestedTitle = args?.suggestedTitle,
            charsetOverride = charsetOverride?.name(),
        )
    }

    private val workspaceScope = CoroutineScope(
        SupervisorJob() +
            CoroutineExceptionHandler { _, throwable ->
                log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
                // Error handled by editorEngine.error StateFlow
            }
    )

    override val type: Workspace.Type = Workspace.Type.EDITOR

    // Same derivation the factory hands the paused stand-in, so both name this tab identically
    private val seedDisplay = deriveEditorDisplay(creationArguments)
    private val scratchTitle = editorScratchTitle(creationArguments)

    // Seeded synchronously so per-path open dedup sees this tab's file before the engine
    // finishes loading (rapid double-open, in-batch duplicates)
    private val _info = MutableStateFlow(
        initialInfo(
            title = seedDisplay.title ?: type.label,
            subtitle = seedDisplay.subtitle,
            arguments = creationArguments,
            // Stays false until the engine reports file-backed content: createArguments() drops
            // initialContent, so pausing a scratch buffer would throw away its text.
        ).copy(isPausable = false)
    )
    override val info: MutableStateFlow<Workspace.Info> = _info

    /**
     * Last content source reported, tagged with the engine that reported it. A [ContentSource.File]
     * names the file itself, so one left over from a previous engine would name the PREVIOUS file:
     * the tag is what lets a switch discard it instead of publishing a path and a name that belong
     * to different files.
     */
    private data class ReportedSource(val engine: EditorEngine, val contentSource: ContentSource)

    @Volatile
    private var reportedSource: ReportedSource? = null

    /**
     * What the current engine has reported, or the in-memory placeholder every engine starts on -
     * which is also what a freshly installed engine reports until it has loaded anything.
     */
    private fun currentContentSource(): ContentSource = reportedSource
        ?.takeIf { it.engine === _engine.value }
        ?.contentSource
        ?: ContentSource.Memory(size = 0L)

    /**
     * Publishes the file identity for per-path open dedup ([Workspace.Info.contentPath]). Called
     * synchronously wherever the current engine changes - claim flows rely on the path being
     * visible the moment an open completes, without waiting for an async observer.
     *
     * The tab name follows it: [Workspace.Info.contentPath] is what this tab CLAIMS to hold, so
     * clearing it (the user cancelling an open, closing the file) must drop the file name too -
     * otherwise the tab keeps advertising a file that a session save no longer persists.
     */
    private fun publishContentPath(path: APath<*>?) {
        _info.update { current ->
            val display = editorContentDisplay(currentContentSource(), path, scratchTitle)
            current.copy(
                contentPath = path,
                title = display.title ?: type.label,
                subtitle = display.subtitle,
            )
        }
    }

    /**
     * Republishes the tab name for what [engine] reports. Title AND subtitle move together: after a
     * Save-As or a file switch a stale path subtitle would describe the previous file.
     *
     * Emissions from an engine that is no longer current are dropped - they describe a file this
     * tab has already moved off.
     */
    private fun publishContentSource(engine: EditorEngine, contentSource: ContentSource) {
        if (_engine.value !== engine) {
            log(tag, VERBOSE) { "Ignoring content source of a replaced engine: $contentSource" }
            return
        }
        reportedSource = ReportedSource(engine, contentSource)
        _info.update { current ->
            val display = editorContentDisplay(contentSource, current.contentPath, scratchTitle)
            current.copy(
                title = display.title ?: type.label,
                subtitle = display.subtitle,
                // Only file-backed content survives a pause: an in-memory buffer exists nowhere but
                // here. A file whose backing was lost (deleted, moved, unreadable) is in the same
                // boat - the buffer is the only copy left and the resume would reopen an
                // unavailable path.
                isPausable = contentSource is ContentSource.File && !contentSource.isBackingLost,
            )
        }
        log(tag, DEBUG) { "Updated identity for: $contentSource" }
    }

    val filePath: APath<*>? get() = (creationArguments as? EditorArguments.Default)?.filePath

    // Active charset override; restored from session arguments (validated against the allowlist
    // so an unknown persisted name degrades to auto-detection instead of breaking restore)
    private var charsetOverride: Charset? =
        (creationArguments as? EditorArguments.Default)?.charsetOverride?.let { name ->
            EditorCharsets.resolve(name).also {
                if (it == null) log(tag, WARN) { "Ignoring unknown persisted charset override: $name" }
            }
        }

    // Track engine being initialized to allow cancellation
    @Volatile
    private var pendingEngine: EditorEngine? = null

    private val engineMutex = Mutex()
    // Serializes saveFile/auto-save against saveFileAs (see saveFileAs doc)
    private val saveMutex = Mutex()
    private val _engine = MutableStateFlow<EditorEngine?>(null)

    // Unified workspace state - emits Initializing immediately
    private val _state = MutableStateFlow<State>(State.Initializing)
    val state: StateFlow<State> = _state.asStateFlow()

    private data class DisplaySettings(
        val showLineNumbers: Boolean,
        val wordWrap: Boolean,
        val fontSize: Int,
        val tabSize: Int,
    )

    private val displaySettings: Flow<DisplaySettings> = combine(
        editorSettings.showLineNumbers.flow,
        editorSettings.wordWrap.flow,
        editorSettings.fontSize.flow,
        editorSettings.tabSize.flow,
    ) { showLineNumbers, wordWrap, fontSize, tabSize ->
        DisplaySettings(showLineNumbers, wordWrap, fontSize, tabSize)
    }

    // Combined editor state for internal use
    private val editorStateInternal: Flow<EditorState> = _engine.flatMapLatest { engine ->
        if (engine == null) return@flatMapLatest emptyFlow()
        combineMany(
            engine.contentSource,
            engine.totalLines,
            engine.isModified,
            engine.visibleContent,
            engine.cursorPosition,
            engine.selectionRange,
            engine.searchQuery,
            engine.searchState,
            engine.visibleRange,
            engine.error,
            engine.externalChange,
            engine.progress,
            engine.canUndo,
            engine.canRedo,
            engine.maxUndoableEditChars,
            engine.nonUndoableEditPending,
            engine.highlighter.highlightedLines,
            displaySettings,
        ) { contentSource, totalLines, isModified, visibleContent, cursorPosition,
            selectionRange, searchQuery, searchState, visibleRange, error,
            externalChange, progress, canUndo, canRedo, maxUndoableEditChars,
            nonUndoableEditPending, highlightedLines, display ->
            EditorState(
                contentSource = contentSource,
                totalLines = totalLines,
                isModified = isModified,
                currentContent = visibleContent.text,
                truncatedLines = visibleContent.truncatedLines,
                startColumns = visibleContent.startColumns,
                windowToken = visibleContent.token,
                windowRangeStart = visibleContent.rangeStart,
                cursorPosition = cursorPosition,
                selectionRange = selectionRange,
                searchQuery = searchQuery,
                searchResults = searchState.results,
                searchTruncated = searchState.truncated,
                visibleRange = visibleRange,
                error = error,
                externalChange = externalChange,
                showLineNumbers = display.showLineNumbers,
                wordWrap = display.wordWrap,
                fontSize = display.fontSize,
                tabSize = display.tabSize,
                progress = progress,
                canUndo = canUndo,
                canRedo = canRedo,
                maxUndoableEditChars = maxUndoableEditChars,
                nonUndoableEditPending = nonUndoableEditPending,
                highlightedLines = highlightedLines,
            )
        }
    }

    init {
        log(tag, INFO) { "Initialized with file: ${filePath?.name ?: "No file"}" }

        // Collect editorState and emit as State.Ready
        workspaceScope.launch {
            try {
                editorStateInternal.collect { editorState ->
                    // A terminal init failure is final; later internal emissions (engine error
                    // flow, settings changes) must not flip the tab back to a Ready scratch view
                    _state.update { prev -> if (prev is State.Error) prev else State.Ready(editorState) }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _state.value = State.Error(e)
                    log(tag, ERROR) { "Workspace error: ${e.asLog()}" }
                }
            }
        }

        // Update info.lifecycleState when state changes
        workspaceScope.launch {
            _state.collect { state ->
                val lifecycle = when (state) {
                    is State.Initializing -> Workspace.LifecycleState.Initializing
                    is State.Error -> Workspace.LifecycleState.Error(state.error)
                    is State.Ready -> Workspace.LifecycleState.Ready
                }
                val hasUnsavedChanges = (state as? State.Ready)?.editor?.isModified == true
                _info.update { it.copy(lifecycleState = lifecycle, hasUnsavedChanges = hasUnsavedChanges) }
            }
        }

        // Track operation counts for this workspace
        operationsManager.operationsForWorkspace(id).withOnlyStateChanges()
            .onEach { operations ->
                var operationCount = 0
                var attentionCount = 0

                operations.forEach { operation ->
                    when (val opState = operation.state.value) {
                        is Operation.State.Queued -> operationCount++
                        is Operation.State.Active -> operationCount++
                        is Operation.State.Waiting -> {
                            operationCount++
                            attentionCount++
                        }
                        is Operation.State.Completed -> {
                            if (opState.error != null && opState.error !is CancellationException) {
                                attentionCount++
                            }
                        }
                    }
                }

                _info.update {
                    it.copy(
                        operationCount = operationCount,
                        attentionCount = attentionCount,
                    )
                }
                log(tag, VERBOSE) { "Updated operation counts: active=$operationCount, attention=$attentionCount" }
            }
            .launchIn(workspaceScope)

        // Republish the tab name as the content source changes. The identity path it is paired
        // with is the tab's own contentPath, which tells a real scratch buffer apart from a file
        // that is still loading (or failed to) - both surface as an in-memory source.
        workspaceScope.launch {
            _engine.flatMapLatest { engine ->
                engine?.contentSource?.map { source -> engine to source } ?: emptyFlow()
            }.collect { (engine, source) ->
                publishContentSource(engine, source)
            }
        }

        // Auto-save logic: debounce after changes; read-only and binary files are skipped so
        // an edited unsaveable document doesn't produce a failing save on every interval.
        // Scratch buffers are skipped too: "saving" to the in-memory source persists nothing
        // but clears the modified flag, disabling the Save/Save-As actions. Files flagged as
        // externally changed pause too: every save would be refused by the staleness guard.
        // A pending non-undoable edit pauses auto-save so an accidental oversized replace isn't
        // silently persisted before the user can discard it (undo can't recover it).
        // Sourced from the engine flows directly, NOT editorStateInternal: that flow is cold and
        // already collected for state publication - a second collection would run every upstream
        // (including the highlighter pipeline) twice in parallel.
        combine(
            _engine.flatMapLatest { engine ->
                if (engine == null) return@flatMapLatest flowOf(false)
                combine(
                    engine.contentSource,
                    engine.isModified,
                    engine.externalChange,
                    engine.nonUndoableEditPending,
                ) { contentSource, isModified, externalChange, nonUndoableEditPending ->
                    val file = contentSource as? ContentSource.File
                    isModified && file != null && file.canWrite && !file.isLikelyBinary &&
                        !file.isBackingLost && externalChange == null && !nonUndoableEditPending
                }
            }.distinctUntilChanged(),
            editorSettings.autoSaveEnabled.flow,
            editorSettings.autoSaveInterval.flow,
        ) { isModified, enabled, interval ->
            Triple(isModified, enabled, interval)
        }
            .flatMapLatest { (isModified, enabled, interval) ->
                if (isModified && enabled) {
                    // Debounce: wait for interval after last modification
                    flow {
                        delay(interval)
                        emit(Unit)
                    }
                } else {
                    emptyFlow()
                }
            }
            .onEach {
                log(tag, INFO) { "Auto-save triggered" }
                try {
                    saveFile()
                } catch (e: Exception) {
                    log(tag, WARN) { "Auto-save failed: ${e.asLog()}" }
                }
            }
            .launchIn(workspaceScope)

        // Initialize engine asynchronously - allows workspace to reach Ready state immediately
        // showing loading progress during file load instead of "Initializing tab"
        workspaceScope.launch {
            val args = creationArguments as? EditorArguments.Default
            val initialPath = args?.filePath
            val initialContent = args?.initialContent
            log(tag, INFO) { "Creating initial engine with: ${initialPath?.name ?: "scratch buffer"}" }

            val engine = editorEngineFactory.create(id, initialPath, initialContent, charsetOverride)
            _engine.value = engine
            pendingEngine = engine

            try {
                val result = engine.initialize()

                if (result.isFailure) {
                    val error = result.exceptionOrNull() ?: Exception("Engine initialization failed")
                    if (error is CancellationException) {
                        // The engine stays current but Empty: no file is attached, so the tab
                        // must stop claiming the path (mirrors createArguments())
                        publishContentPath(null)
                        return@launch
                    }
                    log(tag, ERROR) { "Engine initialization failed: ${error.asLog()}" }
                    _state.value = State.Error(error)
                    return@launch
                }
            } finally {
                pendingEngine = null
            }

            // Restore cursor and scroll position from saved arguments
            // Only if file was loaded AND positions are within bounds
            if (initialPath != null) {
                val lines = engine.totalLines.value
                val cursorLine = args.cursorLine
                val cursorColumn = args.cursorColumn
                if (cursorLine != null && cursorColumn != null && cursorLine < lines) {
                    log(tag, INFO) { "Restoring cursor position: line=$cursorLine, column=$cursorColumn" }
                    engine.setCursorPosition(TextPosition(offset = 0, line = cursorLine, column = cursorColumn))
                }
                val scrollLine = args.scrollToLine
                if (scrollLine != null && scrollLine < lines) {
                    val windowSize = 50
                    log(tag, INFO) { "Restoring scroll position: line=$scrollLine" }
                    engine.updateVisibleRange(scrollLine, scrollLine + windowSize)
                }
            }
        }
    }

    /**
     * Swaps in a fresh engine for [newFilePath]. [discardOld] releases the previous engine
     * WITHOUT flushing unsaved changes - required for every flow where the user explicitly
     * discarded (close-with-discard, reopen-with-encoding) or redirected them (Save-As);
     * the default flush-on-release stays as the safety net for unprompted teardown.
     */
    private suspend fun switchEngine(
        newFilePath: APath<*>?,
        charset: Charset? = null,
        discardOld: Boolean = false,
    ) {
        log(tag, INFO) { "Switching engine to: ${newFilePath?.name ?: "scratch buffer"} (charset=$charset, discardOld=$discardOld)" }

        val newEngine = editorEngineFactory.create(id, newFilePath, charsetOverride = charset)
        pendingEngine = newEngine

        // Swap engine before initialize so progress flows through editorStateInternal
        val oldEngine = engineMutex.withLock {
            val old = _engine.value
            _engine.value = newEngine
            old
        }
        // Intent-based: a still-loading (or later failed) engine keeps the identity so duplicate
        // opens focus this tab instead of spawning a sibling
        publishContentPath(newFilePath)

        try {
            val initResult = newEngine.initialize()

            if (initResult.isFailure) {
                val error = initResult.exceptionOrNull() ?: Exception("Failed to initialize engine")
                if (error is CancellationException) throw error
                log(tag, ERROR) { "Failed to switch engine: ${error.asLog()}" }
                throw error
            }

            // Release old engine after successful init
            oldEngine?.let { old ->
                try {
                    old.release(flush = !discardOld)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Failed to release old engine: ${e.asLog()}" }
                }
            }

            charsetOverride = charset
            log(tag, DEBUG) { "Engine switched successfully" }
        } catch (e: CancellationException) {
            log(tag, INFO) { "Engine switch cancelled" }
            // Restore old engine on cancellation; the fresh engine has nothing worth flushing
            rollbackEngine(newEngine, oldEngine)
            try {
                newEngine.release(flush = false)
            } catch (releaseError: Exception) {
                log(tag, ERROR) { "Failed to release cancelled engine: ${releaseError.asLog()}" }
            }
            throw e
        } catch (e: Exception) {
            // Restore old engine on failure
            rollbackEngine(newEngine, oldEngine)
            try {
                newEngine.release(flush = false)
            } catch (releaseError: Exception) {
                log(tag, ERROR) { "Failed to release failed engine: ${releaseError.asLog()}" }
            }
            throw e
        } finally {
            pendingEngine = null
        }
    }

    /**
     * Rolls a failed/cancelled switch back to [oldEngine] - but only if [newEngine] is still the
     * current one: a newer switch may have installed its own engine meanwhile, and restoring over
     * it would strand the tab on a stale engine and publish a stale contentPath.
     */
    private suspend fun rollbackEngine(newEngine: EditorEngine, oldEngine: EditorEngine?) {
        engineMutex.withLock {
            if (_engine.value !== newEngine) return
            _engine.value = oldEngine
            publishContentPath(oldEngine?.takeUnless { it.state.value is EngineState.Empty }?.filePath)
        }
    }

    // Editor operations
    suspend fun openFile(filePath: APath<*>) {
        // Progress is emitted by EditorEngine during initialization
        switchEngine(filePath)
    }

    /** Closes the current file; only reached directly or after the user confirmed discarding changes. */
    suspend fun closeFile() = switchEngine(null, discardOld = true)

    /** Reopens the current file decoding it with [charsetName]; unsaved changes are discarded. */
    suspend fun reopenWithCharset(charsetName: String) {
        val charset = EditorCharsets.resolve(charsetName) ?: run {
            log(tag, WARN) { "Ignoring reopen with unknown charset: $charsetName" }
            return
        }
        val currentPath = ((_state.value as? State.Ready)?.editor?.contentSource as? ContentSource.File)?.path
            ?: run {
                log(tag, WARN) { "Cannot reopen with charset - no file open" }
                return
            }
        // Discard is what the user confirmed; flushing here would ALSO corrupt the reopen -
        // the new engine indexes the file BEFORE the old one is released
        switchEngine(currentPath, charset, discardOld = true)
    }

    /** Converts the document's line endings to [target] and saves; serialized like every other save. */
    suspend fun convertLineEndings(target: LineEnding): Result<Unit> = saveMutex.withLock {
        currentEngine().convertLineEndings(target)
    }

    /** Re-reads the current file from disk, discarding unsaved changes; only reached directly or after confirmation. */
    suspend fun reloadFromDisk() {
        val currentPath = ((_state.value as? State.Ready)?.editor?.contentSource as? ContentSource.File)?.path
            ?: run {
                log(tag, WARN) { "Cannot reload - no file open" }
                return
            }
        log(tag, INFO) { "Reloading from disk: ${currentPath.name}" }
        switchEngine(currentPath, charsetOverride, discardOld = true)
    }

    /** Probes whether the open file changed on disk; detections surface via the editor state. */
    suspend fun checkExternalChange() {
        _engine.value?.checkExternalChange()
    }

    /**
     * Cancels an in-progress file open operation.
     * Safe to call even if no operation is running.
     */
    fun cancelFileOpen() {
        pendingEngine?.let { engine ->
            log(tag, INFO) { "Cancelling file open for pending engine" }
            engine.cancelInitialization()
        }
    }

    private fun currentEngine(): EditorEngine =
        _engine.value ?: throw IllegalStateException("No engine available")

    suspend fun saveFile() {
        // TODO: Wrap as an Operation submitted via OperationsManager so editor saves appear in
        // the global Operation History (kind = SAVE, pathPlan targets = [filePath]). Same applies to
        // auto-save call sites. Out of scope for History v1.
        // Progress is emitted by EditorEngine during save
        saveMutex.withLock { currentEngine().saveFile() }
    }

    /**
     * [saveMutex] serializes this against saveFile/auto-save: without it a save landing between
     * the destination write and the engine switch would flush the redirected edits back into
     * the ORIGINAL file.
     */
    suspend fun saveFileAs(newFilePath: APath<*>): Result<Unit> = saveMutex.withLock {
        val engine = currentEngine()

        val editor = (_state.value as? State.Ready)?.editor
        val fileSource = editor?.contentSource as? ContentSource.File
        if (fileSource?.isLikelyBinary == true && editor.isModified) {
            // An UNMODIFIED binary Save-As is a byte-exact copy (legitimate); a modified one
            // would push text-pipeline corruption into the new file
            return@withLock Result.failure(
                ReadOnlyFileException("Binary file, saving modifications is disabled: ${fileSource.path}"),
            )
        }

        val currentPath = fileSource?.path
        if (currentPath == newFilePath) {
            // Streaming into the current file would truncate the very source the original byte
            // ranges are read from; the normal atomic save handles this case
            log(tag) { "Save-as targets the current file, using the atomic save path" }
            return@withLock engine.saveFile()
        }

        return@withLock try {
            log(tag) { "Saving as: ${newFilePath.name}" }

            // Atomic write: a crash mid-stream must not leave a truncated target or destroy a
            // pre-existing file at the destination. The writer never reads the target's old
            // content - everything comes from the current engine.
            atomicFileWriter.replace(newFilePath, AtomicFileWriter.OriginalAccess.None) { context ->
                engine.writeContentTo(context.sink)
            }

            log(tag) { "Content written to: ${newFilePath.name}" }

            // The unsaved changes now live in the new file; releasing the old engine must NOT
            // flush them into the ORIGINAL file
            switchEngine(newFilePath, charsetOverride, discardOld = true)

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to save as: ${newFilePath.name} - ${e.asLog()}" }
            Result.failure(e)
        }
    }

    suspend fun search(query: String, options: SearchOptions = SearchOptions()) =
        currentEngine().search(query, options)

    suspend fun replaceCurrent(
        query: String,
        options: SearchOptions,
        match: SearchResult,
        replacement: String,
    ) = currentEngine().replaceCurrent(query, options, match, replacement)

    suspend fun replaceAll(query: String, options: SearchOptions, replacement: String) =
        currentEngine().replaceAll(query, options, replacement)

    suspend fun goToLine(lineNumber: Long) = currentEngine().goToLine(lineNumber)
    suspend fun undo(epoch: Uuid) = currentEngine().undo(epoch)
    suspend fun redo(epoch: Uuid) = currentEngine().redo(epoch)
    suspend fun copySelection(maxChars: Long? = null) = currentEngine().copySelection(maxChars)
    suspend fun prepareCut(maxChars: Long? = null) = currentEngine().prepareCut(maxChars)
    suspend fun applyCut(snapshot: EditorEngine.CutSnapshot) = currentEngine().applyCut(snapshot)
    suspend fun selectAll() = currentEngine().selectAll()

    /** Applies an edit whose target the engine resolves atomically; [epoch] pins it to one document. */
    suspend fun performEdit(intent: EditorEngine.EditIntent, epoch: Uuid) = currentEngine().performEdit(intent, epoch)

    /** Applies a verified field edit; [EditorEngine.MutationResult.Conflict] means nothing changed. */
    suspend fun applyFieldDelta(delta: EditorEngine.FieldDelta) = currentEngine().applyFieldDelta(delta)

    /** Applies an oversized edit the user confirmed; a stale token mutates nothing. */
    suspend fun submitPrepared(prepared: EditorEngine.PreparedMutation) = currentEngine().submitPrepared(prepared)

    /** Window, cursor and selection as one value - what a conflicted field rebuilds from. */
    suspend fun captureWindowSnapshot() = currentEngine().captureWindowSnapshot()

    suspend fun setCursorPosition(position: TextPosition) = currentEngine().setCursorPosition(position)
    suspend fun setSelection(start: TextPosition, end: TextPosition) = currentEngine().setSelection(start, end)
    suspend fun updateVisibleRange(startLine: Long, endLine: Long) =
        currentEngine().updateVisibleRange(startLine, endLine)

    suspend fun revealMoreColumns(forward: Boolean) = currentEngine().revealMoreColumns(forward)

    suspend fun moveCursor(direction: CursorDirection, extendSelection: Boolean) {
        log(tag) { "moveCursor(direction=$direction, extendSelection=$extendSelection)" }
        currentEngine().moveCursor(direction, extendSelection)
    }

    fun clearError() = _engine.value?.clearError()

    /** Reads file content for pasting from clipboard. */
    suspend fun readFileContent(path: APath<*>): Result<String> = pasteFileReader.read(path)

    enum class SaveAsTarget { FREE, EXISTS_FILE, EXISTS_DIRECTORY }

    /** Classifies a Save-As destination so the UI can confirm overwrites and reject directories. */
    suspend fun inspectSaveAsTarget(path: APath<*>): SaveAsTarget = gatewaySwitch.useRes {
        if (!path.exists(gatewaySwitch)) {
            SaveAsTarget.FREE
        } else {
            val lookup = path.lookup(gatewaySwitch, LookupOptions.BASE)
            if (lookup.fileType == FileType.DIRECTORY) SaveAsTarget.EXISTS_DIRECTORY else SaveAsTarget.EXISTS_FILE
        }
    }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        workspaceScope.cancel()
        try {
            _engine.value?.release()
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to release engine: ${e.asLog()}" }
        }
    }

    data class EditorState(
        val contentSource: ContentSource = ContentSource.Memory(size = 0L),
        val totalLines: Long = 0,
        val isModified: Boolean = false,
        val currentContent: String = "",
        /** Absolute line number -> chars hidden AFTER the window on display-truncated lines. */
        val truncatedLines: Map<Long, Long> = emptyMap(),
        /** Absolute line number -> chars hidden BEFORE the window (the window's anchor column). */
        val startColumns: Map<Long, Long> = emptyMap(),
        /** Identity of the document state [currentContent] was read at; null before the first load. */
        val windowToken: EditorEngine.DocumentToken? = null,
        /** First absolute line of [currentContent], captured together with [windowToken]. */
        val windowRangeStart: Long = 0L,
        val cursorPosition: TextPosition = TextPosition.ZERO,
        val selectionRange: Pair<TextPosition, TextPosition>? = null,
        val searchQuery: String = "",
        val searchResults: List<SearchResult> = emptyList(),
        val searchTruncated: Boolean = false,
        val visibleRange: LongRange = 0L..50L,
        val error: Throwable? = null,
        val externalChange: EditorEngine.ExternalChange? = null,
        val showLineNumbers: Boolean = true,
        val wordWrap: Boolean = false,
        val fontSize: Int = 14,
        val tabSize: Int = 4,
        val progress: Progress.Data? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        /** Delete/replace larger than this is applied non-undoably; null until a file is loaded. */
        val maxUndoableEditChars: Long? = null,
        /** True while a non-undoable edit is pending a save/recorded edit; pauses auto-save. */
        val nonUndoableEditPending: Boolean = false,
        /** Absolute line number -> syntax tokens (RAW offsets); empty when highlighting is off/unavailable. */
        val highlightedLines: Map<Long, List<Token>> = emptyMap(),
    )

    sealed interface State {
        data object Initializing : State
        data class Ready(val editor: EditorState) : State {
            val progress: Progress.Data? get() = editor.progress
        }
        data class Error(val error: Throwable) : State
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<EditorArguments> {

        override fun create(id: Workspace.Id, arguments: EditorArguments): EditorWorkspace

        override val argumentsSerializer: KSerializer<EditorArguments> get() = serializer()

        override fun deriveDisplay(arguments: EditorArguments): WorkspaceDisplay =
            deriveEditorDisplay(arguments)
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.EDITOR)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}
