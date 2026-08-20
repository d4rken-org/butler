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
import eu.darken.butler.editor.ui.editor.text.SessionDelta
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

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
        workspace = ::getWorkspace,
        guardedInsert = ::guardedInsertText,
        deleteCut = ::enqueueVerifiedDelete,
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

    val state: Flow<State> = combine(
        workspaceWithState,
        dialogsController.state,
        searchController.state,
        clipboardController.hasSystemClipboardContent,
    ) { (workspace, wsState, contentPath), dialogs, search, hasClipboardContent ->
        // Only emit Ready states - Init/Error are handled globally by WorkspaceMapper
        val readyState = wsState as? EditorWorkspace.State.Ready ?: return@combine null

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
            windowToken = editorState.windowToken,
            windowRangeStart = editorState.windowRangeStart,
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

    // Token chaining for field deltas, touched ONLY by the single edit-command consumer (same
    // pattern as the queue itself, so no synchronization is needed): the first delta of a
    // generation carries the token its window was captured at, every successor is applied against
    // the token its predecessor's acknowledgement returned.
    private var chainToken: EditorEngine.DocumentToken? = null
    private var activeFieldGeneration: Long? = null

    // Generations invalidated by a conflict/failure: their already queued descendants complete as
    // Conflict without touching the document. Bounded - a generation is only consulted while the
    // commands queued behind its rejection drain.
    private val deadFieldGenerations = ArrayDeque<Long>()

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
        observeEngineEpoch()
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
     * One ordered edit pipeline for every text mutation - the design doc's "engine mutation actor".
     *
     * Each entry point used to `launch` its own coroutine on the multi-threaded Default dispatcher,
     * so two edits dispatched back-to-back (Enter, then a character) could reach the engine's mutex
     * in the wrong order and the character would resolve against the pre-Enter document. Entry
     * points now enqueue SYNCHRONOUSLY and a single consumer drains the channel, so arrival order
     * at the engine equals UI-event order.
     *
     * **Actor contract.** Every variant is pure data, resolved before it is enqueued or resolved
     * atomically inside the engine - never in between:
     * - [FieldDelta] is a token-chained delta; its predecessor's acknowledgement supplies the token.
     * - [Edit] carries an [EditorEngine.EditIntent] plus the epoch it was typed against; the ENGINE
     *   resolves cursor and selection under its own lock.
     * - [Confirmed] carries an immutable [EditorEngine.PreparedMutation], [VerifiedDelete] a
     *   [EditorEngine.CutSnapshot] - both re-verified against their token before anything moves.
     * - [Navigate] carries explicit positions; [Undo]/[Redo] are semantic and epoch-stamped.
     *
     * What the queue must NEVER contain: clipboard or file retrieval, dialog waits, arbitrary
     * `suspend () -> Unit` closures, or a command that discovers its target by reading mutable
     * cursor/selection state OUTSIDE the engine's atomic resolution. Those effects run before
     * enqueueing and hand in the data they produced.
     */
    private sealed interface EditCommand {
        /** Enqueue order, for diagnostics. */
        val revision: Long

        /**
         * One keystroke from the hidden field. Carries no materialized token: the consumer resolves
         * it at execution time from the delta's own snapshot (first of a generation) or from the
         * token the previous acknowledgement returned.
         */
        data class FieldDelta(
            override val revision: Long,
            val delta: SessionDelta,
            val outcome: CompletableDeferred<EditorEngine.MutationResult>,
        ) : EditCommand

        /**
         * A mutation stated as an intent plus the document it was meant for. [epoch] is null when
         * nothing was loaded at enqueue time - such a command is dropped rather than guessed at.
         */
        data class Edit(
            override val revision: Long,
            val intent: EditorEngine.EditIntent,
            val epoch: Uuid?,
            /** Completed with whether the edit was APPLIED; null for fire-and-forget edits. */
            val applied: CompletableDeferred<Boolean>? = null,
        ) : EditCommand

        /**
         * A cut's deletion: it carries the range and document version its clipboard copy was taken
         * from, so it can only ever remove that range - "whatever is selected now" would be a
         * different selection by the time the queue reaches it.
         */
        data class VerifiedDelete(
            override val revision: Long,
            val snapshot: EditorEngine.CutSnapshot,
            val outcome: CompletableDeferred<Result<String>>,
        ) : EditCommand

        /**
         * Semantic, not spatial: "revert the latest committed transaction". Epoch-stamped all the
         * same - an undo queued before a file switch must not revert the document that replaced it.
         */
        data class Undo(override val revision: Long, val epoch: Uuid?) : EditCommand
        data class Redo(override val revision: Long, val epoch: Uuid?) : EditCommand

        /** An edit the user confirmed in the large-edit dialog; resolved before the dialog opened. */
        data class Confirmed(
            override val revision: Long,
            val prepared: EditorEngine.PreparedMutation,
        ) : EditCommand

        /**
         * Cursor/selection movement. Ordered with the edits so a keystroke typed after a tap or an
         * arrow key applies after it; navigation never bumps the document version, so it cannot
         * conflict the deltas queued around it.
         */
        sealed interface Navigate : EditCommand {
            data class SetCursor(override val revision: Long, val position: TextPosition) : Navigate
            data class MoveCursor(
                override val revision: Long,
                val direction: CursorDirection,
                val extendSelection: Boolean,
            ) : Navigate

            data class SetSelection(
                override val revision: Long,
                val start: TextPosition,
                val end: TextPosition,
            ) : Navigate

            data class SelectAll(override val revision: Long) : Navigate
            data class GoToLine(override val revision: Long, val lineNumber: Long) : Navigate
        }
    }

    /** Must stay non-suspending: enqueueing from inside a coroutine would reintroduce the reordering. */
    private fun enqueue(command: (Long) -> EditCommand) {
        editCommands.trySend(command(enqueuedRevision.incrementAndGet()))
    }

    /** Stamps the intent with the document that was open when the user triggered it. */
    private fun enqueueEdit(intent: EditorEngine.EditIntent) = enqueue { EditCommand.Edit(it, intent, cachedEpoch) }

    /**
     * Epoch of the currently open document, cached so mutation commands can be stamped from the
     * non-suspending enqueue path. Volatile: enqueueing happens on the UI thread while the collector
     * below runs on the VM scope.
     */
    @Volatile
    private var cachedEpoch: Uuid? = null

    private fun observeEngineEpoch() {
        workspaceWithState
            .map { (_, wsState) -> (wsState as? EditorWorkspace.State.Ready)?.editor?.windowToken?.engineEpoch }
            .distinctUntilChanged()
            .onEach { cachedEpoch = it }
            .launchIn(vmScope)
    }

    /**
     * Drains the queue, folding runs of back-to-back selections into their newest member.
     *
     * A handle drag enqueues one [EditCommand.Navigate.SetSelection] per pointer event, and every
     * one of them re-resolves two offsets, breaks the undo run and refreshes the visible window.
     * Dropping the intermediates of a run is inert: a selection is a whole-state assignment, so the
     * newest one overwrites whatever its predecessors would have set; `breakUndoRun()` is
     * idempotent across a run with no edit between its members; and only the final window refresh
     * is ever observed. Ordering is untouched - only ALREADY-QUEUED successors are drained, and the
     * first command of any other kind stops the drain and runs next.
     */
    private fun consumeEditCommands() = vmScope.launch {
        // Holds the non-selection command that ended a drain, so the next round executes it before
        // receiving again.
        var pending: EditCommand? = null
        while (true) {
            val received = pending ?: editCommands.receiveCatching().getOrNull() ?: break
            pending = null
            val command = if (received is EditCommand.Navigate.SetSelection) {
                var newest: EditCommand.Navigate.SetSelection = received
                while (true) {
                    val next = editCommands.tryReceive().getOrNull() ?: break
                    if (next is EditCommand.Navigate.SetSelection) {
                        newest = next
                    } else {
                        pending = next
                        break
                    }
                }
                newest
            } else {
                received
            }
            try {
                execute(command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Per-command catch: one failed edit must not tear down the pipeline. Identified by
                // kind and revision only - a command's payload is document text.
                val label = "${command::class.simpleName}#${command.revision}"
                log(tag, ERROR) { "Edit command failed: $label - ${e.asLog()}" }
                errorEvents.emit(e)
            }
        }
    }

    private suspend fun execute(command: EditCommand) {
        when (command) {
            is EditCommand.FieldDelta -> {
                try {
                    command.outcome.complete(performFieldDelta(command.delta))
                } catch (e: Throwable) {
                    command.outcome.completeExceptionally(e)
                    throw e
                }
            }
            is EditCommand.Edit -> {
                try {
                    // Evaluated outside the safe call: that would skip the edit for a null deferred
                    val applied = performEdit(command.intent, command.epoch)
                    command.applied?.complete(applied)
                } catch (e: Throwable) {
                    command.applied?.completeExceptionally(e)
                    throw e
                }
            }
            is EditCommand.VerifiedDelete -> {
                try {
                    command.outcome.complete(performVerifiedDelete(command.snapshot))
                } catch (e: Throwable) {
                    command.outcome.completeExceptionally(e)
                    throw e
                }
            }
            // Same rule as an Edit intent: without a document to aim at there is nothing to revert,
            // and applying it to whatever is open now would revert the wrong document
            is EditCommand.Undo -> command.epoch
                ?.let { getWorkspace().undo(it) }
                ?: log(tag, INFO) { "Dropping undo, no document was loaded when it was enqueued" }
            is EditCommand.Redo -> command.epoch
                ?.let { getWorkspace().redo(it) }
                ?: log(tag, INFO) { "Dropping redo, no document was loaded when it was enqueued" }
            is EditCommand.Confirmed -> performConfirmedEdit(command.prepared)
            is EditCommand.Navigate -> executeNavigation(command)
        }
    }

    private suspend fun executeNavigation(command: EditCommand.Navigate) {
        val workspace = getWorkspace()
        when (command) {
            is EditCommand.Navigate.SetCursor -> workspace.setCursorPosition(command.position)
            is EditCommand.Navigate.MoveCursor -> workspace.moveCursor(command.direction, command.extendSelection)
            is EditCommand.Navigate.SetSelection -> workspace.setSelection(command.start, command.end)
            is EditCommand.Navigate.SelectAll -> workspace.selectAll()
            is EditCommand.Navigate.GoToLine -> workspace.goToLine(command.lineNumber)
        }
    }

    /**
     * Resolves the delta's token and applies it. The first delta of a generation carries the token
     * of the window it was computed against; its successors chain on the token the previous
     * acknowledgement returned, because a keystroke burst is typed against the field's own
     * speculative state, not against any version the engine has published yet.
     */
    private suspend fun performFieldDelta(delta: SessionDelta): EditorEngine.MutationResult {
        if (deadFieldGenerations.contains(delta.generation)) {
            // A descendant of an already rejected keystroke: the field rebuilt from the conflict,
            // so applying this would edit against a window that no longer exists.
            return EditorEngine.MutationResult.Conflict(getWorkspace().captureWindowSnapshot())
        }
        if (delta.generation != activeFieldGeneration) {
            activeFieldGeneration = delta.generation
            chainToken = delta.snapshotToken
        }
        val token = delta.snapshotToken ?: chainToken
        if (token == null) {
            log(tag, WARN) { "Field delta without a token to chain on, treating as a conflict" }
            return EditorEngine.MutationResult.Conflict(getWorkspace().captureWindowSnapshot())
        }
        editActivity.tryEmit(Unit)
        val result = getWorkspace().applyFieldDelta(
            EditorEngine.FieldDelta(
                token = token,
                start = delta.start,
                end = delta.end,
                oldText = delta.oldText,
                newText = delta.newText,
                caret = delta.caret,
            ),
        )
        when (result) {
            is EditorEngine.MutationResult.Applied -> chainToken = result.token
            else -> markFieldGenerationDead(delta.generation)
        }
        return result
    }

    private fun markFieldGenerationDead(generation: Long) {
        if (deadFieldGenerations.contains(generation)) return
        deadFieldGenerations.addLast(generation)
        if (deadFieldGenerations.size > MAX_DEAD_FIELD_GENERATIONS) deadFieldGenerations.removeFirst()
    }

    /** Enqueues a field-originated edit; the returned outcome is what the session chains on. */
    fun enqueueFieldDelta(delta: SessionDelta): Deferred<EditorEngine.MutationResult> {
        val outcome = CompletableDeferred<EditorEngine.MutationResult>()
        enqueue { EditCommand.FieldDelta(it, delta, outcome) }
        return outcome
    }

    fun insertText(text: String) = enqueueEdit(EditorEngine.EditIntent.InsertAtCursor(text))

    /**
     * Runs one intent against the document it was stamped for. True ONLY when the engine applied
     * it: a gated (confirmation-pending), refused (read-only) or dropped (file switched) edit must
     * not be reported as done - paste logs its success on this.
     */
    private suspend fun performEdit(intent: EditorEngine.EditIntent, epoch: Uuid?): Boolean {
        if (epoch == null) {
            // Nothing was loaded when this was enqueued; there is no document to apply it to
            log(tag, INFO) { "Dropping $intent, no document was loaded when it was enqueued" }
            return false
        }
        editActivity.tryEmit(Unit)
        val outcome = getWorkspace().performEdit(intent, epoch)
        gateIfNeeded(outcome)
        return outcome is EditorEngine.EditOutcome.Applied
    }

    /**
     * Enqueues a cut's verified deletion and awaits the engine's result, so a cut can do its
     * clipboard write outside the queue while the deletion it produces stays ordered with typing -
     * and still removes exactly the range that was copied, whatever the selection has become.
     */
    private suspend fun enqueueVerifiedDelete(snapshot: EditorEngine.CutSnapshot): Result<String> {
        val outcome = CompletableDeferred<Result<String>>()
        enqueue { EditCommand.VerifiedDelete(it, snapshot, outcome) }
        return outcome.await()
    }

    private suspend fun performVerifiedDelete(snapshot: EditorEngine.CutSnapshot): Result<String> {
        editActivity.tryEmit(Unit)
        return getWorkspace().applyCut(snapshot)
    }

    // The prepared edit stashed behind the large-edit confirm dialog; submitted by
    // [confirmLargeDelete], dropped by [dismissLargeEditConfirm]. AtomicReference gives lock-free
    // first-writer-wins: VM coroutines run on a multi-threaded dispatcher, so a plain var could be
    // raced by two gated edits.
    private val pendingOversizedEdit = AtomicReference<EditorEngine.PreparedMutation?>(null)

    /**
     * True when [outcome] was gated: the edit consumes a selection too large to apply undoably
     * (materializing the removed span for undo would OOM), so the engine resolved it into an
     * immutable [EditorEngine.PreparedMutation] and mutated nothing. Only the first writer shows
     * the dialog; a later gated edit is dropped, not stashed.
     */
    private fun gateIfNeeded(outcome: EditorEngine.EditOutcome): Boolean {
        val prepared = (outcome as? EditorEngine.EditOutcome.RequiresConfirmation)?.prepared ?: return false
        if (pendingOversizedEdit.compareAndSet(null, prepared)) {
            dialogsController.showLargeDeleteConfirmDialog()
        }
        return true
    }

    private suspend fun performConfirmedEdit(prepared: EditorEngine.PreparedMutation) {
        editActivity.tryEmit(Unit)
        // A document that moved on while the dialog was up rejects the edit and mutates nothing;
        // the user's confirmation applied to a state that no longer exists, so it's log-only.
        when (val result = getWorkspace().submitPrepared(prepared)) {
            is EditorEngine.MutationResult.Conflict -> log(tag, WARN) {
                "Confirmed edit dropped, the document moved on"
            }
            is EditorEngine.MutationResult.Failed -> log(tag, WARN) {
                "Confirmed edit failed - ${result.error.asLog()}"
            }
            is EditorEngine.MutationResult.Applied -> log(tag, INFO) { "Confirmed oversized edit applied" }
        }
    }

    fun requestDeleteSelection() = enqueueEdit(EditorEngine.EditIntent.DeleteSelection)

    fun confirmLargeDelete() {
        val prepared = pendingOversizedEdit.getAndSet(null) ?: return
        // Enqueued before the dialog closes: typing right after confirming must land behind it
        enqueue { EditCommand.Confirmed(it, prepared) }
        dialogsController.dismissLargeDeleteConfirmDialog()
    }

    /** Cancel the pending oversized edit without applying it. */
    private fun dismissLargeEditConfirm() {
        pendingOversizedEdit.set(null)
        dialogsController.dismissLargeDeleteConfirmDialog()
    }

    fun deleteForward() = enqueueEdit(EditorEngine.EditIntent.DeleteForward)

    /**
     * Insert (e.g. paste) guarded by the oversized-selection gate. False when the engine did not
     * apply it - deferred behind the confirm dialog, refused, or aimed at a document that is gone.
     * Goes through the same queue as every other mutation, awaiting its command's outcome.
     */
    private suspend fun guardedInsertText(text: String): Boolean {
        val applied = CompletableDeferred<Boolean>()
        enqueue { EditCommand.Edit(it, EditorEngine.EditIntent.InsertAtCursor(text), cachedEpoch, applied) }
        return applied.await()
    }

    fun moveCursor(direction: CursorDirection, extendSelection: Boolean) =
        enqueue { EditCommand.Navigate.MoveCursor(it, direction, extendSelection) }

    fun selectAll() = enqueue { EditCommand.Navigate.SelectAll(it) }

    fun setCursorPosition(position: TextPosition) = enqueue { EditCommand.Navigate.SetCursor(it, position) }

    fun setSelection(start: TextPosition, end: TextPosition) =
        enqueue { EditCommand.Navigate.SetSelection(it, start, end) }

    fun goToLine(lineNumber: Long) = enqueue { EditCommand.Navigate.GoToLine(it, lineNumber) }

    fun undo() = enqueue { EditCommand.Undo(it, cachedEpoch) }

    fun redo() = enqueue { EditCommand.Redo(it, cachedEpoch) }

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
            EditorActionBarItem.CopyToButlerClipboard -> clipboardController.copyToButlerClipboard()
            EditorActionBarItem.CutToButlerClipboard -> clipboardController.cutToButlerClipboard()
            EditorActionBarItem.Paste -> clipboardController.pasteFromClipboard()
            EditorActionBarItem.Delete -> requestDeleteSelection()
            EditorActionBarItem.SelectAll -> selectAll()
            EditorActionBarItem.GoToLine -> dialogsController.showGoToLineDialog()
            EditorActionBarItem.Search -> searchController.showSearchBar()
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
        /** Identity of the document state [currentContent] was read at; null before the first load. */
        val windowToken: EditorEngine.DocumentToken? = null,
        /** First absolute line of [currentContent], captured together with [windowToken]. */
        val windowRangeStart: Long = 0L,
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
                if (hasSelection) add(EditorActionBarItem.CopyToButlerClipboard)
                if (hasSelection && !isReadOnly) add(EditorActionBarItem.CutToButlerClipboard)
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

    companion object {
        /** Enough to cover the commands queued behind one rejection; older entries can't be queued. */
        private const val MAX_DEAD_FIELD_GENERATIONS = 32
    }
}
