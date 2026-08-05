package eu.darken.butler.editor.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.darken.butler.common.SystemClipboardHelper
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.combine as combineMany
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.editorLocationSubtitle
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.LineEnding
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import eu.darken.butler.editor.core.syntax.Token
import eu.darken.butler.editor.ui.editor.elements.EditorActionBarItem
import eu.darken.butler.editor.ui.editor.text.CursorDirection
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@HiltViewModel(assistedFactory = EditorWorkspaceViewModel.Factory::class)
class EditorWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    private val workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
    clipboardHelper: SystemClipboardHelper,
    clipboardRepo: ClipboardRepo,
    private val filenameValidator: FilenameValidator,
) : ViewModel4(dispatchers, logTag("Editor", "Workspace", id.shortTag, "Page")) {

    private val workspaceSource: Flow<EditorWorkspace?> = workspaceProvider.retrieve(id).map { it as EditorWorkspace? }
    private suspend fun getWorkspace(): EditorWorkspace = workspaceSource.filterNotNull().first()

    // Error-handled launch shared with the controllers so their failures surface like ours
    private val doLaunch: (suspend CoroutineScope.() -> Unit) -> Unit = { block -> launch(block = block) }

    private val searchController = EditorSearchController(vmScope, doLaunch, ::getWorkspace, tag)
    private val clipboardController = EditorClipboardController(
        id = id,
        doLaunch = doLaunch,
        enqueueClipboardOp = ::enqueueClipboardOp,
        workspace = ::getWorkspace,
        guardedInsert = ::performGuardedInsert,
        clipboardHelper = clipboardHelper,
        clipboardRepo = clipboardRepo,
        tag = tag,
    )
    private val dialogsController = EditorDialogsController(doLaunch, ::getWorkspace)

    private var openFileJob: Job? = null

    /**
     * The workspace, its state, and the path the tab CLAIMS to hold.
     *
     * The claimed path is carried separately from the content source: it is already correct while
     * the file is still loading, which is what makes it usable as a content identity (see
     * [editorBarResetIdentity]). Only the path is taken off the info flow, so the rest of that
     * flow's churn (operation counts, titles) doesn't re-emit the editor state.
     */
    private data class WorkspaceSnapshot(
        val workspace: EditorWorkspace,
        val state: EditorWorkspace.State,
        val contentPath: APath<*>?,
    )

    private val workspaceWithState: Flow<WorkspaceSnapshot> = workspaceSource
        .filterNotNull()
        .flatMapLatest { ws ->
            combine(
                ws.state,
                ws.info.map { it.contentPath }.distinctUntilChanged(),
            ) { state, contentPath -> WorkspaceSnapshot(ws, state, contentPath) }
        }

    // Bumped whenever a field-originated edit (typing/backspace) does NOT reach the document:
    // gated behind the large-edit confirm dialog, or rejected by the engine (stale positions from
    // a diverged field). LazyTextEditor applies its edit to the hidden field optimistically before
    // dispatching, so an unapplied edit would leave the field diverged from the engine; observing
    // this signal makes the field revert to engine content. Normal typing never bumps it.
    private val _editResyncSignal = MutableStateFlow(0)

    // Reference count of clipboard ops that are queued or running. Incremented SYNCHRONOUSLY in
    // [enqueueClipboardOp], so it is already nonzero before any keystroke that follows the paste
    // gesture can be enqueued. Clipboard retrieval happens inside the queued op, so the queue can
    // sit in that op for a while - and a field edit enqueued meanwhile carries positions captured
    // from the pre-paste document, which frequently stay representable and thus pass the engine's
    // column check instead of being rejected. The field is made read-only while this is nonzero.
    private val _clipboardMutationPending = MutableStateFlow(0)

    val state: Flow<State> = combineMany(
        workspaceWithState,
        dialogsController.state,
        searchController.state,
        clipboardController.hasSystemClipboardContent,
        _editResyncSignal,
        _clipboardMutationPending,
    ) { (workspace, wsState, contentPath), dialogs, search, hasClipboardContent, editResyncSignal, clipboardPending ->
        // Only emit Ready states - Init/Error are handled globally by WorkspaceMapper
        val readyState = wsState as? EditorWorkspace.State.Ready ?: return@combineMany null

        val editorState = readyState.editor

        val displayPath = (editorState.contentSource as? ContentSource.File)?.path
        val title = displayPath?.userReadableName ?: editorState.contentSource.name.toCaString()
        // Shared with the tab identity, so this toolbar and the tab describe the same location
        val subTitle = editorLocationSubtitle(displayPath) ?: "In-Memory-Buffer".toCaString()

        State(
            id = id,
            contentSource = editorState.contentSource,
            contentPath = contentPath,
            title = title,
            subTitle = subTitle,
            totalLines = editorState.totalLines,
            isModified = editorState.isModified,
            currentContent = editorState.currentContent,
            truncatedLines = editorState.truncatedLines,
            startColumns = editorState.startColumns,
            highlightedLines = editorState.highlightedLines,
            cursorPosition = editorState.cursorPosition,
            selectionRange = editorState.selectionRange,
            progress = readyState.progress,
            error = editorState.error,
            externalChange = editorState.externalChange,
            externalChangeDismissedGeneration = dialogs.externalChangeDismissedGeneration,
            showReloadConfirmDialog = dialogs.showReloadConfirmDialog,
            showLineEndingDialog = dialogs.showLineEndingDialog,
            searchQuery = editorState.searchQuery,
            searchResults = editorState.searchResults,
            searchTruncated = editorState.searchTruncated,
            visibleRange = editorState.visibleRange,
            showLineNumbers = editorState.showLineNumbers,
            wordWrap = editorState.wordWrap,
            fontSize = editorState.fontSize,
            tabSize = editorState.tabSize,
            showGoToLineDialog = dialogs.showGoToLineDialog,
            showSearchBar = search.showSearchBar,
            showCloseConfirmDialog = dialogs.showCloseConfirmDialog,
            showEncodingDialog = dialogs.showEncodingDialog,
            pendingEncoding = dialogs.pendingEncoding,
            pendingSaveAsOverwrite = dialogs.pendingSaveAsOverwrite,
            backupNoticeDismissed = dialogs.backupNoticeDismissed,
            longLinesNoticeDismissed = dialogs.longLinesNoticeDismissed,
            searchQueryInput = search.queryInput,
            currentSearchResultIndex = search.currentResultIndex,
            searchCaseSensitive = search.caseSensitive,
            searchRegexEnabled = search.regexEnabled,
            searchWholeWord = search.wholeWord,
            scrollTrigger = search.scrollTrigger,
            replaceQueryInput = search.replaceQueryInput,
            showReplaceRow = search.showReplaceRow,
            replaceNotice = search.replaceNotice,
            canUndo = editorState.canUndo,
            canRedo = editorState.canRedo,
            hasSystemClipboardContent = hasClipboardContent,
            maxUndoableEditChars = editorState.maxUndoableEditChars,
            showLargeDeleteConfirmDialog = dialogs.showLargeDeleteConfirmDialog,
            editResyncSignal = editResyncSignal,
            isClipboardBusy = clipboardPending > 0,
        )
    }.filterNotNull()

    // Delegated surfaces consumed by the Host/Page
    val clipboard = clipboardController.clipboard
    val clipboardInfoClip = clipboardController.clipboardInfoClip
    val pasteableClipboard = clipboardController.pasteableClipboard
    fun refreshClipboardState() = clipboardController.refreshClipboardState()

    // Pulsed on every user edit; sampled to poll backing availability while actively typing.
    private val editActivity = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Declared before the init block that starts the consumer - property initialization is ordered.
    private val editCommands = Channel<EditCommand>(Channel.UNLIMITED)
    private val enqueuedRevision = AtomicLong(0L)

    // Revision of the newest enqueued Replace. Only Replace commands can reject and leave the field
    // diverged, so only they may hold back the resync - Undo/paste/delete queued behind a rejection
    // must not suppress it.
    private val latestReplaceRevision = AtomicLong(0L)

    // Set when a Replace is rejected, cleared when the resync is emitted. Touched only by the single
    // edit-command consumer, so it needs no synchronization.
    private var resyncPending = false

    /**
     * Append/EOF edits need no original bytes, so they can't trip the buffer's read backstop, and
     * the resumed-page external-change poll is coarse (15s). Sampling edit activity checks the
     * file every ~1.5s while typing, so a document whose backing file vanished flips read-only
     * quickly instead of accumulating edits that can never be saved.
     */
    @OptIn(FlowPreview::class)
    private fun observeEditActivityForAvailability() {
        editActivity
            .sample(1_500L)
            .onEach { getWorkspace().checkExternalChange() }
            .launchIn(vmScope)
    }

    init {
        consumeEditCommands()
        observeEditActivityForAvailability()

        // Dismissed backup/long-lines notices belong to ONE path: any path change (open,
        // Save-As, scratch-to-file) re-arms the notices for the new document
        workspaceWithState
            .map { (_, wsState) ->
                ((wsState as? EditorWorkspace.State.Ready)?.editor?.contentSource as? ContentSource.File)?.path
            }
            .distinctUntilChanged()
            .onEach {
                dialogsController.rearmBackupNotice()
                dialogsController.rearmLongLinesNotice()
            }
            .launchIn(vmScope)

        // A dismissed external-change banner belongs to ONE engine's detection generations:
        // whenever the flag clears (reload, save, engine swap), forget the dismissal so the
        // fresh engine's generation counter can't collide with a stale dismissed value
        workspaceWithState
            .map { (_, wsState) -> (wsState as? EditorWorkspace.State.Ready)?.editor?.externalChange }
            .distinctUntilChanged()
            .onEach { if (it == null) dialogsController.rearmExternalChangeNotice() }
            .launchIn(vmScope)

        // Listen for picker results. `filename` is non-null exactly when the result came from a
        // SaveAs picker - stateless discrimination, immune to cancel/reopen races.
        workspaceRemote.events
            .handleResult<WorkspaceEvent.PickerResult>(callerWorkspaceId = id) { result ->
                log(tag) { "Received picker result: ${result.selectedPaths.firstOrNull()} (filename=${result.filename})" }
                val directory = result.selectedPaths.firstOrNull() ?: return@handleResult
                val filename = result.filename
                if (filename != null) {
                    handleSaveAsDestination(directory, filename)
                } else {
                    openFile(directory)
                }
            }
            .launchIn(vmScope)
    }

    // ==================== File operations ====================

    fun launchFilePicker() = launch {
        val currentState = state.first()
        val currentPath = (currentState.contentSource as? ContentSource.File)?.path?.parent
        workspaceRemote.launchPicker(id, currentPath, PickerConfig.Selection.FileSingle)
    }

    fun saveFileAs() = launch {
        val currentState = state.first()
        val source = currentState.contentSource
        val suggested = (source as? ContentSource.File)?.path?.name ?: "untitled.txt"
        val startPath = (source as? ContentSource.File)?.path?.parent
        workspaceRemote.launchPicker(id, startPath, PickerConfig.Selection.SaveAs(suggestedFilename = suggested))
    }

    private fun handleSaveAsDestination(directory: APath<*>, filename: String) = launch {
        // A new Save-As result supersedes any overwrite decision still pending from an earlier one
        dialogsController.setPendingSaveAsOverwrite(null)
        // The picker validates too; re-validating here means a malformed event can't produce a
        // path with separators or storage-invalid characters
        val validation = filenameValidator.validate(filename, directory)
        if (validation is FilenameValidator.ValidationResult.Invalid) {
            throw IllegalArgumentException(
                "Filename contains invalid characters: ${validation.invalidChars.joinToString("")}",
            )
        }
        val destination = directory.child(filename)
        when (getWorkspace().inspectSaveAsTarget(destination)) {
            EditorWorkspace.SaveAsTarget.EXISTS_DIRECTORY ->
                throw IllegalArgumentException("A folder named \"$filename\" already exists here")
            EditorWorkspace.SaveAsTarget.EXISTS_FILE -> dialogsController.setPendingSaveAsOverwrite(destination)
            EditorWorkspace.SaveAsTarget.FREE -> performSaveAs(destination)
        }
    }

    private fun performSaveAs(destination: APath<*>) = launch {
        getWorkspace().saveFileAs(destination).getOrThrow()
        log(tag, INFO) { "Saved as: $destination" }
    }

    fun confirmSaveAsOverwrite() {
        val destination = dialogsController.takePendingSaveAsOverwrite() ?: return
        performSaveAs(destination)
    }

    fun openFile(filePath: APath<*>) {
        val previousJob = openFileJob
        openFileJob = vmScope.launch {
            try {
                val workspace = getWorkspace()
                if (workspace.info.value.contentPath == filePath) {
                    // Re-picking the tab's own (or currently loading) file must not re-open it:
                    // switchEngine would show stale disk content and the old engine's release
                    // would then flush unsaved edits over it - and cancelling an in-flight load
                    // of this very path would roll the tab back to the previous file
                    log(tag, INFO) { "File already open(ing) in this tab: $filePath" }
                    return@launch
                }
                // A different target supersedes any in-flight open; await its rollback so the
                // engine switches can't interleave
                previousJob?.cancelAndJoin()
                val claim = workspaceRemote.execute(
                    WorkspaceAction.ClaimContentPath(Workspace.Type.EDITOR, filePath, id),
                )
                if (claim is WorkspaceAction.ClaimContentPath.Result.AlreadyOpen) {
                    log(tag, INFO) { "File already open in ${claim.existingId}, focusing it: $filePath" }
                    workspaceRemote.emitEvent(WorkspaceEvent.SelectionRequested(claim.existingId, id))
                    return@launch
                }
                try {
                    workspace.openFile(filePath)  // Workspace handles loading state
                } finally {
                    // The engine swap published contentPath before openFile returned, so the
                    // claim is redundant from here on; on failure/cancel it must not keep
                    // blocking the path
                    withContext(NonCancellable) {
                        workspaceRemote.execute(WorkspaceAction.ReleaseContentPath(id, filePath))
                    }
                }
            } catch (e: CancellationException) {
                log(tag, INFO) { "File open cancelled: $filePath" }
                throw e
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to open file: $filePath - ${e.asLog()}" }
            }
        }
    }

    fun cancelFileOpen() {
        log(tag) { "Cancelling file open" }
        // Cancel the ViewModel's waiting job
        openFileJob?.cancel()
        openFileJob = null
        // Also cancel the engine initialization directly (bypasses scope isolation)
        vmScope.launch {
            getWorkspace().cancelFileOpen()
        }
    }

    fun closeFile() {
        launch {
            val currentState = state.first()
            if (currentState.isModified) {
                dialogsController.showCloseConfirmDialog()
            } else {
                performCloseFile()
            }
        }
    }

    private fun performCloseFile() {
        launch {
            try {
                getWorkspace().closeFile()  // Workspace handles loading state
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to close file - ${e.asLog()}" }
            }
        }
    }

    fun confirmCloseFile() {
        dialogsController.dismissCloseConfirmDialog()
        performCloseFile()
    }

    fun saveFile() {
        launch {
            val currentState = state.first()
            if (currentState.contentSource is ContentSource.Memory) {
                // A scratch buffer has no file to save into - route through Save-As
                saveFileAs()
            } else {
                getWorkspace().saveFile()  // Workspace handles loading state
            }
        }
    }

    // ==================== External change handling ====================

    /** Probes whether the open file changed on disk; called on resume and by the page's poll loop. */
    fun checkExternalChange() = launch {
        getWorkspace().checkExternalChange()
    }

    fun reloadFromDisk() = launch {
        val currentState = state.first()
        if (currentState.isModified) {
            dialogsController.showReloadConfirmDialog()
        } else {
            performReload()
        }
    }

    private fun performReload() = launch {
        getWorkspace().reloadFromDisk()
    }

    fun confirmReload() {
        dialogsController.dismissReloadConfirmDialog()
        performReload()
    }

    fun dismissExternalChange() = launch {
        val generation = state.first().externalChange?.generation ?: return@launch
        dialogsController.dismissExternalChange(generation)
    }

    // ==================== Line endings ====================

    fun convertLineEndings(target: LineEnding) = launch {
        dialogsController.dismissLineEndingDialog()
        // No UI-level same-target short-circuit: the buffer's no-op path still verifies the
        // on-disk baseline, so a stale file surfaces instead of silently reporting success
        getWorkspace().convertLineEndings(target).getOrThrow()
        log(tag, INFO) { "Line endings converted to $target" }
    }

    // ==================== Editing delegates ====================

    fun updateVisibleRange(startLine: Long, endLine: Long) = launch {
        getWorkspace().updateVisibleRange(startLine, endLine)
    }

    fun revealMoreColumns(forward: Boolean) = launch {
        getWorkspace().revealMoreColumns(forward)
    }

    /**
     * One ordered edit pipeline for every text mutation.
     *
     * Each entry point used to `launch` its own coroutine on the multi-threaded Default dispatcher,
     * so two edits dispatched back-to-back (Enter, then a character) could reach the engine's mutex
     * in the wrong order and the character would resolve against the pre-Enter document. Entry
     * points now enqueue SYNCHRONOUSLY and a single consumer drains the channel, so arrival order
     * at the engine equals UI-event order.
     */
    private sealed interface EditCommand {
        /** Enqueue order; a rejected [Replace] only resyncs the field when no newer one is queued. */
        val revision: Long

        data class Replace(
            override val revision: Long,
            val start: TextPosition,
            val end: TextPosition,
            val text: String,
            val caret: TextPosition,
        ) : EditCommand

        data class Insert(override val revision: Long, val text: String) : EditCommand

        data class DeleteSelection(override val revision: Long, val gated: Boolean) : EditCommand
        data class DeleteAtCursor(override val revision: Long, val count: Int) : EditCommand
        data class DeleteForward(override val revision: Long) : EditCommand
        data class Undo(override val revision: Long) : EditCommand
        data class Redo(override val revision: Long) : EditCommand

        /** Replay of an edit the user confirmed in the large-edit dialog; the gate already ran. */
        data class Confirmed(override val revision: Long, val action: suspend () -> Unit) : EditCommand

        /** Clipboard retrieval + document mutation as one unit, so neither can be overtaken. */
        data class Clipboard(override val revision: Long, val op: suspend () -> Unit) : EditCommand
    }

    /** Must stay non-suspending: enqueueing from inside a coroutine would reintroduce the reordering. */
    private fun enqueue(command: (Long) -> EditCommand) {
        val created = command(enqueuedRevision.incrementAndGet())
        // Only Replace revisions gate the resync; concurrent enqueues may assign out of order
        if (created is EditCommand.Replace) latestReplaceRevision.updateAndGet { maxOf(it, created.revision) }
        editCommands.trySend(created)
    }

    private fun consumeEditCommands() = vmScope.launch {
        for (command in editCommands) {
            try {
                execute(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Per-command catch: one failed edit must not tear down the pipeline
                log(tag, ERROR) { "Edit command failed: $command - ${e.asLog()}" }
                errorEvents.emit(e)
            }
        }
    }

    private suspend fun execute(command: EditCommand) {
        when (command) {
            is EditCommand.Replace -> {
                // Typing/backspace over an oversized selection replaces it non-undoably (materializing
                // it for undo would OOM). The field already applied the edit locally, so a deferred
                // gate bumps the resync signal to revert it; on confirm we replay the replace.
                val replay: suspend () -> Boolean = {
                    performReplaceText(command.start, command.end, command.text, command.caret)
                }
                if (deferIfOversized(fromField = true) { replay() }) {
                    // The gate reverted the field itself, which also settles an earlier rejection
                    resyncPending = false
                } else if (!replay()) {
                    // A rejected edit (stale field positions) leaves the field diverged too
                    resyncPending = true
                }
                if (command.revision == latestReplaceRevision.get() && resyncPending) {
                    resyncPending = false
                    _editResyncSignal.update { it + 1 }
                }
            }
            is EditCommand.Insert -> performGuardedInsert(command.text)
            is EditCommand.DeleteSelection -> {
                if (command.gated && deferIfOversized { performDeleteSelection() }) return
                performDeleteSelection()
            }
            is EditCommand.DeleteAtCursor -> {
                // Over an oversized selection this deletes non-undoably; confirm and replay as a
                // selection delete. A plain backspace (no selection) is never gated.
                if (deferIfOversized { performDeleteSelection() }) return
                editActivity.tryEmit(Unit)
                getWorkspace().deleteAtCursor(command.count)
            }
            is EditCommand.DeleteForward -> {
                if (deferIfOversized { performDeleteSelection() }) return
                editActivity.tryEmit(Unit)
                getWorkspace().deleteForward()
            }
            is EditCommand.Undo -> getWorkspace().undo()
            is EditCommand.Redo -> getWorkspace().redo()
            // The gate already ran before the dialog; re-running it would defer the confirmed edit again
            is EditCommand.Confirmed -> command.action()
            is EditCommand.Clipboard -> {
                try {
                    command.op()
                    // The field was read-only across the op, so it never saw the mutation; rebuild
                    // it from engine content before input is handed back.
                    _editResyncSignal.update { it + 1 }
                } finally {
                    // Also on failure - otherwise a throwing op would leave the editor mute
                    _clipboardMutationPending.update { it - 1 }
                }
            }
        }
    }

    fun insertText(text: String) = enqueue { EditCommand.Insert(it, text) }

    private suspend fun performInsertText(text: String) {
        editActivity.tryEmit(Unit)
        getWorkspace().insertText(text)
    }

    /**
     * A field edit carries positions captured from the content the field was showing. While a
     * clipboard mutation is pending that content is about to change, so the edit must not enter the
     * queue - the field is already read-only via [State.isClipboardBusy], but events dispatched
     * before that recomposition lands still arrive here. Reject them and resync the field instead.
     */
    fun replaceText(start: TextPosition, end: TextPosition, text: String, caret: TextPosition) {
        if (_clipboardMutationPending.value > 0) {
            log(tag, WARN) { "Dropping field edit while a clipboard op is pending: $text" }
            _editResyncSignal.update { it + 1 }
            return
        }
        enqueue { EditCommand.Replace(it, start, end, text, caret) }
    }

    private suspend fun performReplaceText(
        start: TextPosition,
        end: TextPosition,
        text: String,
        caret: TextPosition,
    ): Boolean {
        editActivity.tryEmit(Unit)
        return getWorkspace().replaceText(start, end, text, caret)
    }

    fun deleteSelection() = enqueue { EditCommand.DeleteSelection(it, gated = false) }

    private suspend fun performDeleteSelection() {
        editActivity.tryEmit(Unit)
        getWorkspace().deleteSelection()
    }

    // Deferred edit stashed behind the large-edit confirm dialog; replayed by [confirmLargeDelete],
    // dropped by [dismissLargeEditConfirm]. AtomicReference gives lock-free first-writer-wins: VM
    // coroutines run on a multi-threaded dispatcher, so a plain var could be raced by two gated edits.
    private val pendingOversizedEdit = AtomicReference<(suspend () -> Unit)?>(null)

    /**
     * True (edit deferred behind the confirm dialog) when the current selection is too large to edit
     * undoably - replacing/deleting it clears history (materializing it for undo would OOM). Reads the
     * engine's authoritative selection via [EditorWorkspace.selectionExceedsUndoThreshold] (not the
     * async state projection, which can lag a just-set selection and let the gate be skipped).
     * [onConfirm] is stashed (first-writer-wins) and replayed on confirm.
     *
     * For paste/delete the engine replaces the whole selection, so this is exact. For a field edit
     * (typing/backspace) the field may only dispatch a window-bounded replace, so this can over-fire
     * (confirm an edit that would actually be undoable) - the safe direction; it never skips a genuine
     * non-undoable edit. [fromField] edits already mutated the hidden field locally, so a gate bumps
     * [_editResyncSignal] to revert it to engine content.
     *
     * Runs inside the edit-command consumer, so it stays ordered against the edits it gates.
     */
    private suspend fun deferIfOversized(fromField: Boolean = false, onConfirm: suspend () -> Unit): Boolean {
        if (!getWorkspace().selectionExceedsUndoThreshold()) return false
        // Only the first writer shows the dialog; a later gated edit is dropped, not stashed.
        if (pendingOversizedEdit.compareAndSet(null, onConfirm)) {
            dialogsController.showLargeDeleteConfirmDialog()
        }
        // Revert the field's optimistic local edit (deferred first edit or a dropped later one).
        if (fromField) _editResyncSignal.update { it + 1 }
        return true
    }

    fun requestDeleteSelection() = enqueue { EditCommand.DeleteSelection(it, gated = true) }

    fun confirmLargeDelete() {
        val action = pendingOversizedEdit.getAndSet(null) ?: return
        // Enqueued before the dialog closes: typing right after confirming must land behind the replay
        enqueue { EditCommand.Confirmed(it, action) }
        dialogsController.dismissLargeDeleteConfirmDialog()
    }

    /** Cancel the pending oversized edit without applying it. */
    private fun dismissLargeEditConfirm() {
        pendingOversizedEdit.set(null)
        dialogsController.dismissLargeDeleteConfirmDialog()
    }

    fun deleteAtCursor(count: Int) = enqueue { EditCommand.DeleteAtCursor(it, count) }

    fun deleteForward() = enqueue { EditCommand.DeleteForward(it) }

    /**
     * Insert (e.g. paste) guarded by the oversized-selection gate. Returns false when deferred.
     *
     * Runs INSIDE the edit-command consumer (as an [EditCommand.Insert] or from a clipboard op), so
     * it must never enqueue and wait: the consumer that would have to run the enqueued command is
     * the one calling this.
     */
    private suspend fun performGuardedInsert(text: String): Boolean {
        if (deferIfOversized { performInsertText(text) }) return false
        performInsertText(text)
        return true
    }

    /**
     * Runs a clipboard op - retrieval included - inside the ordered pipeline.
     *
     * The pending count is raised here rather than in the consumer: the increment has to happen
     * before the gesture returns to the UI, so a keystroke arriving right after it already sees it.
     */
    private fun enqueueClipboardOp(op: suspend () -> Unit) {
        _clipboardMutationPending.update { it + 1 }
        enqueue { EditCommand.Clipboard(it, op) }
    }

    fun moveCursor(direction: CursorDirection, extendSelection: Boolean) = launch {
        getWorkspace().moveCursor(direction, extendSelection)
    }

    fun selectAll() = launch {
        getWorkspace().selectAll()
    }

    fun setCursorPosition(position: TextPosition) = launch {
        getWorkspace().setCursorPosition(position)
    }

    fun setSelection(start: TextPosition, end: TextPosition) = launch {
        getWorkspace().setSelection(start, end)
    }

    fun goToLine(lineNumber: Long) = launch {
        getWorkspace().goToLine(lineNumber)
    }

    fun undo() = enqueue { EditCommand.Undo(it) }

    fun redo() = enqueue { EditCommand.Redo(it) }

    fun clearError() = launch {
        getWorkspace().clearError()
    }

    // ==================== Action dispatch ====================

    /**
     * Executes workspace-level domain actions from action bar.
     * Routes EditorAction objects to appropriate handlers.
     */
    fun executeAction(action: EditorActionBarItem) {
        when (action) {
            EditorActionBarItem.Copy -> clipboardController.copyToClipboard()
            EditorActionBarItem.Cut -> clipboardController.cutToClipboard()
            EditorActionBarItem.Paste -> clipboardController.pasteFromClipboard()
            EditorActionBarItem.Delete -> requestDeleteSelection()
            EditorActionBarItem.SelectAll -> selectAll()
            EditorActionBarItem.GoToLine -> dialogsController.showGoToLineDialog()
            EditorActionBarItem.Search -> searchController.showSearchBar()
        }
    }

    /**
     * Handles long-press on action bar buttons.
     * Copy/Cut long press copies/cuts to Butler clipboard.
     */
    fun executeActionLongClick(action: EditorActionBarItem) {
        when (action) {
            EditorActionBarItem.Copy -> clipboardController.copyToButlerClipboard()
            EditorActionBarItem.Cut -> clipboardController.cutToButlerClipboard()
            else -> { /* Other actions don't have long press behavior */
            }
        }
    }

    /**
     * Unified handler for all page-level actions.
     * Dispatches to appropriate ViewModel methods based on action type.
     */
    fun onPageAction(action: EditorPageAction) {
        when (action) {
            // File actions
            is EditorPageAction.File.LaunchPicker -> launchFilePicker()
            is EditorPageAction.File.Save -> saveFile()
            is EditorPageAction.File.SaveAs -> saveFileAs()
            is EditorPageAction.File.Close -> closeFile()
            is EditorPageAction.File.CancelOpen -> cancelFileOpen()
            is EditorPageAction.File.ShowEncodingPicker -> dialogsController.showEncodingDialog()
            is EditorPageAction.File.ReopenWithEncoding -> dialogsController.selectEncoding(action.charsetName)
            is EditorPageAction.File.DismissBackupNotice -> dialogsController.dismissBackupNotice()
            is EditorPageAction.File.DismissLongLinesNotice -> dialogsController.dismissLongLinesNotice()
            is EditorPageAction.File.ReloadFromDisk -> reloadFromDisk()
            is EditorPageAction.File.DismissExternalChange -> dismissExternalChange()
            is EditorPageAction.File.ShowLineEndingPicker -> dialogsController.showLineEndingDialog()
            is EditorPageAction.File.ConvertLineEndings -> convertLineEndings(action.target)

            // Edit actions
            is EditorPageAction.Edit.InsertText -> insertText(action.text)
            is EditorPageAction.Edit.DeleteAtCursor -> deleteAtCursor(action.count)
            is EditorPageAction.Edit.ReplaceRange -> replaceText(action.start, action.end, action.text, action.caret)
            is EditorPageAction.Edit.ForwardDelete -> deleteForward()
            is EditorPageAction.Edit.Undo -> undo()
            is EditorPageAction.Edit.Redo -> redo()

            // Navigation actions
            is EditorPageAction.Navigation.MoveCursor -> moveCursor(action.direction, action.extendSelection)
            is EditorPageAction.Navigation.SetCursor -> setCursorPosition(action.position)
            is EditorPageAction.Navigation.SetSelection -> setSelection(action.start, action.end)
            is EditorPageAction.Navigation.ClearSelection -> setCursorPosition(action.cursorPosition)
            is EditorPageAction.Navigation.GoToLine -> goToLine(action.lineNumber)
            is EditorPageAction.Navigation.UpdateVisibleRange -> updateVisibleRange(action.startLine, action.endLine)
            is EditorPageAction.Navigation.RevealMoreColumns -> revealMoreColumns(action.forward)

            // Search UI actions
            is EditorPageAction.Search.UpdateQuery -> searchController.updateQuery(action.query)
            is EditorPageAction.Search.ToggleCaseSensitive -> searchController.toggleCaseSensitivity()
            is EditorPageAction.Search.ToggleRegex -> searchController.toggleRegexMode()
            is EditorPageAction.Search.ToggleWholeWord -> searchController.toggleWholeWord()
            is EditorPageAction.Search.ToggleReplaceRow -> searchController.toggleReplaceRow()
            is EditorPageAction.Search.UpdateReplaceQuery -> searchController.updateReplaceQuery(action.query)
            is EditorPageAction.Search.ReplaceCurrent -> searchController.replaceCurrent()
            is EditorPageAction.Search.ReplaceAll -> searchController.replaceAll()
            is EditorPageAction.Search.NextResult -> searchController.nextResult()
            is EditorPageAction.Search.PreviousResult -> searchController.previousResult()
            is EditorPageAction.Search.Close -> searchController.closeSearch()

            // Dialog actions
            is EditorPageAction.Dialog.DismissGoToLine -> dialogsController.dismissGoToLineDialog()
            is EditorPageAction.Dialog.DismissCloseConfirm -> dialogsController.dismissCloseConfirmDialog()
            is EditorPageAction.Dialog.ConfirmClose -> confirmCloseFile()
            is EditorPageAction.Dialog.DismissEncoding -> dialogsController.dismissEncodingDialog()
            is EditorPageAction.Dialog.ConfirmEncodingDiscard -> dialogsController.confirmEncodingDiscard()
            is EditorPageAction.Dialog.DismissEncodingDiscard -> dialogsController.dismissEncodingDiscard()
            is EditorPageAction.Dialog.ConfirmSaveAsOverwrite -> confirmSaveAsOverwrite()
            is EditorPageAction.Dialog.DismissSaveAsOverwrite -> dialogsController.dismissSaveAsOverwrite()
            is EditorPageAction.Dialog.ConfirmReload -> confirmReload()
            is EditorPageAction.Dialog.DismissReloadConfirm -> dialogsController.dismissReloadConfirmDialog()
            is EditorPageAction.Dialog.DismissLineEnding -> dialogsController.dismissLineEndingDialog()
            is EditorPageAction.Dialog.ConfirmLargeDelete -> confirmLargeDelete()
            is EditorPageAction.Dialog.DismissLargeDeleteConfirm -> dismissLargeEditConfirm()

            // Clipboard actions
            is EditorPageAction.Clipboard.Paste -> clipboardController.pasteFromClipboard(action.clip)
            is EditorPageAction.Clipboard.Remove -> clipboardController.removeClipboardEntry(action.clip)
            is EditorPageAction.Clipboard.ShowInfo -> clipboardController.showClipboardInfo(action.clip)
            is EditorPageAction.Clipboard.DismissInfo -> clipboardController.dismissClipboardInfo()
            is EditorPageAction.Clipboard.Clear -> clipboardController.clearAllClipboard()

            // Workspace actions
            is EditorPageAction.Workspace.ShareError -> { /* Handled globally by WorkspaceMapper */
            }
            is EditorPageAction.Workspace.Close -> closeWorkspace()

            // Error actions
            is EditorPageAction.Error.Clear -> clearError()
        }
    }

    fun closeWorkspace() = launch {
        workspaceRemote.execute(WorkspaceAction.Close(id))
    }

    data class State(
        val id: Workspace.Id,
        val contentSource: ContentSource = ContentSource.Memory(size = 0L),
        /**
         * The file this tab claims to hold - set from the moment the open starts, not when it
         * completes, and null for a scratch buffer. Unlike [contentSource] it does not move while
         * a file loads or after it is saved.
         */
        val contentPath: APath<*>? = null,
        val title: CaString,
        val subTitle: CaString,
        val totalLines: Long = 0,
        val isModified: Boolean = false,
        val currentContent: String = "",
        /** Absolute line number -> chars hidden AFTER the window on display-truncated lines. */
        val truncatedLines: Map<Long, Long> = emptyMap(),
        /** Absolute line number -> chars hidden BEFORE the window (the window's anchor column). */
        val startColumns: Map<Long, Long> = emptyMap(),
        /** Absolute line number -> syntax tokens (RAW offsets); empty when highlighting is off. */
        val highlightedLines: Map<Long, List<Token>> = emptyMap(),
        val cursorPosition: TextPosition = TextPosition.ZERO,
        val selectionRange: Pair<TextPosition, TextPosition>? = null,
        val progress: Progress.Data? = null,
        val error: Throwable? = null,
        val externalChange: EditorEngine.ExternalChange? = null,
        val externalChangeDismissedGeneration: Int? = null,
        val showReloadConfirmDialog: Boolean = false,
        val showLineEndingDialog: Boolean = false,
        val searchQuery: String = "",
        val searchResults: List<SearchResult> = emptyList(),
        val searchTruncated: Boolean = false,
        val visibleRange: LongRange = 0L..50L,
        val showLineNumbers: Boolean = true,
        val wordWrap: Boolean = false,
        val fontSize: Int = 14,
        val tabSize: Int = 4,
        val showGoToLineDialog: Boolean = false,
        val showSearchBar: Boolean = false,
        val showCloseConfirmDialog: Boolean = false,
        val showEncodingDialog: Boolean = false,
        val pendingEncoding: String? = null,
        val pendingSaveAsOverwrite: APath<*>? = null,
        val backupNoticeDismissed: Boolean = false,
        val longLinesNoticeDismissed: Boolean = false,
        val searchQueryInput: TextFieldValue = TextFieldValue(""),
        val currentSearchResultIndex: Int = 0,
        val searchCaseSensitive: Boolean = false,
        val searchRegexEnabled: Boolean = false,
        val searchWholeWord: Boolean = false,
        val scrollTrigger: Int = 0,
        val replaceQueryInput: TextFieldValue = TextFieldValue(""),
        val showReplaceRow: Boolean = false,
        val replaceNotice: EditorSearchController.ReplaceNotice? = null,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val hasSystemClipboardContent: Boolean = false,
        /** Delete/replace above this is applied non-undoably; null until a file is loaded. */
        val maxUndoableEditChars: Long? = null,
        val showLargeDeleteConfirmDialog: Boolean = false,
        /** Increments when a field edit is gated behind the confirm dialog; signals the text field to revert to engine content. */
        val editResyncSignal: Int = 0,
        /**
         * A clipboard mutation is queued or running. Kept apart from [isReadOnly] on purpose: that
         * one also disables saving in the toolbar, which a paste must not do. Only the text field
         * ORs it in.
         */
        val isClipboardBusy: Boolean = false,
    ) {
        val isLoading: Boolean get() = progress != null
        val hasFile: Boolean get() = contentSource is ContentSource.File
        val isBinary: Boolean get() = (contentSource as? ContentSource.File)?.isLikelyBinary == true

        /** The backing file vanished/lost read access mid-session: read-only, save disabled. */
        val isBackingLost: Boolean get() = (contentSource as? ContentSource.File)?.isBackingLost == true
        val isReadOnly: Boolean
            get() = (contentSource as? ContentSource.File)?.canWrite == false || isBinary || isBackingLost
        val hasContent: Boolean get() = contentSource.hasContent
        val isFileReady: Boolean get() = contentSource is ContentSource.File && progress == null
        val hasSelection: Boolean get() = selectionRange != null
        val hasSearchResults: Boolean get() = searchResults.isNotEmpty()
        val isSearchActive: Boolean get() = searchQuery.isNotEmpty()
        val hasError: Boolean get() = error != null
        val isSearchBarVisible: Boolean get() = showSearchBar
        val staleBackups: List<APath<*>>
            get() = (contentSource as? ContentSource.File)?.staleBackups ?: emptyList()
        val showBackupNotice: Boolean get() = staleBackups.isNotEmpty() && !backupNoticeDismissed
        val hasLongLines: Boolean get() = (contentSource as? ContentSource.File)?.hasLongLines == true
        val showLongLinesNotice: Boolean get() = hasLongLines && !longLinesNoticeDismissed
        val showExternalChangeBanner: Boolean
            get() = externalChange != null && externalChange.generation != externalChangeDismissedGeneration

        // Info bar properties
        val fileSize: Long get() = contentSource.size
        val totalCharacters: Long get() = fileSize
        val fileEncoding: String
            get() = (contentSource as? ContentSource.File)?.detectedCharset?.name() ?: "UTF-8"
        val lineEnding: LineEnding?
            get() = (contentSource as? ContentSource.File)?.lineEnding
        val selectedCharacterCount: Long
            get() {
                if (selectionRange == null) return 0
                val (start, end) = selectionRange
                // setSelection stores the received order, which may be reversed; measure absolute
                return maxOf(start.offset, end.offset) - minOf(start.offset, end.offset)
            }

        val selectedLineCount: Long
            get() {
                if (selectionRange == null) return 0
                val (start, end) = selectionRange
                return (maxOf(start.line, end.line) - minOf(start.line, end.line)) + 1
            }

        // Available actions based on current state; mutating actions vanish on read-only/binary
        val availableActions: List<EditorActionBarItem>
            get() = buildList {
                if (hasSelection) add(EditorActionBarItem.Copy)
                if (hasSelection && !isReadOnly) add(EditorActionBarItem.Cut)
                if (hasSelection && !isReadOnly) add(EditorActionBarItem.Delete)
                if (hasSystemClipboardContent && !isReadOnly) add(EditorActionBarItem.Paste)
                if (hasContent || currentContent.isNotEmpty()) add(EditorActionBarItem.SelectAll)
                if (hasContent) add(EditorActionBarItem.GoToLine)
                if (hasContent && !isSearchBarVisible) add(EditorActionBarItem.Search)
            }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): EditorWorkspaceViewModel
    }
}
