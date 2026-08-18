package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ApkIconExporter
import eu.darken.butler.viewer.core.GatewayZoomableImageSource
import eu.darken.butler.viewer.core.IconSaveDecision
import eu.darken.butler.viewer.core.decideIconSave
import eu.darken.butler.viewer.core.PdfPreviewLoader
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.viewer.core.ViewerFileGoneException
import eu.darken.butler.viewer.core.ViewerIconUnavailableException
import eu.darken.butler.viewer.core.ViewerShareUnavailableException
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.NoAppForFileException
import eu.darken.butler.workspace.core.OpenWithIntentUseCase
import eu.darken.butler.workspace.core.ShareIntentUseCase
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.partitionByTrashSupport
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceEvent
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.handleResult
import eu.darken.butler.workspace.core.launchPicker
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import me.saket.telephoto.zoomable.ZoomableImageSource
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.workspace.R as WorkspaceR

@HiltViewModel(assistedFactory = ViewerWorkspaceViewModel.Factory::class)
class ViewerWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    @ApplicationContext private val context: Context,
    private val workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val imageSourceFactory: GatewayZoomableImageSource.Factory,
    private val pdfPreviewLoader: PdfPreviewLoader,
    private val openWithIntentUseCase: OpenWithIntentUseCase,
    private val shareIntentUseCase: ShareIntentUseCase,
    private val clipboardRepo: ClipboardRepo,
    private val trashSettings: TrashSettings,
    private val operationsManager: OperationsManager,
    private val apkIconExporter: ApkIconExporter,
    private val filenameValidator: FilenameValidator,
    chromeFactory: WorkspacePageChrome.Factory,
) : ViewModel3(dispatchers, logTag("Viewer", "Workspace", id.shortTag, "Page")) {

    private val chrome = chromeFactory.create(workspaceId = id, scope = vmScope)

    val shareIntentEvent = chrome.shareIntentEvent

    /** One-shot confirmations, e.g. after an icon was written to disk. */
    val toastEvents = SingleEventFlow<CaString>()

    private val workspaceSource = workspaceProvider.retrieve(id)
        .map { it as? ViewerWorkspace }
        .filterNotNull()

    /** Bumped by [retry] so a failed decode gets a fresh image source instead of the poisoned one. */
    private val attemptFlow = MutableStateFlow(0)

    /** Resolve and Coil-load failures reported by the image source, which the workspace cannot see. */
    private val renderErrorFlow = MutableStateFlow<Throwable?>(null)

    /**
     * [WorkspaceProvider.retrieve] is derived from the workspace collection, so it re-emits when an
     * unrelated tab opens or closes. Only the path and the retry attempt may produce a new image
     * source - a fresh one would re-resolve and open another gateway stream for the same picture.
     */
    private val imageSourceFlow = combine(workspaceSource, attemptFlow) { workspace, attempt ->
        workspace.filePath to attempt
    }
        .distinctUntilChanged()
        .map { (path, _) ->
            imageSourceFactory.create(
                path = path,
                onError = { renderErrorFlow.value = it },
            )
        }

    /** Which page the user navigated to. Not persisted: a restored session starts at page one again. */
    private val pdfPageIndexFlow = MutableStateFlow(0)

    /**
     * The selected page is rendered here, not in the workspace: a full-screen page bitmap must not
     * survive in workspace state, where a paused tab would keep holding it. Emits the page without a
     * bitmap first so the page shows its spinner while the render runs.
     *
     * A failed render stays inside this flow instead of reaching [renderErrorFlow]: the document
     * itself is fine, so only the one page reports the failure and the page bar keeps working.
     */
    private val pdfPageFlow = combine(workspaceSource, attemptFlow) { workspace, attempt ->
        workspace to attempt
    }
        .distinctUntilChanged()
        .flatMapLatest { (workspace, _) ->
            workspace.state
                .map { it.content as? ViewerContent.PdfPreview }
                .distinctUntilChanged()
                .flatMapLatest { pdf ->
                    if (pdf == null) {
                        flowOf<PdfPage?>(null)
                    } else {
                        pdfPageIndexFlow
                            // A document that shrank underneath the viewer must not render a page
                            // index that no longer exists.
                            .map { it.coerceIn(0, pdf.pageCount - 1) }
                            .distinctUntilChanged()
                            .flatMapLatest { page ->
                                flow<PdfPage?> {
                                    emit(PdfPage(index = page, bitmap = null))
                                    val bitmap = pdfPreviewLoader.page(workspace.filePath, page)
                                    emit(PdfPage(index = page, bitmap = bitmap, failed = bitmap == null))
                                }
                            }
                    }
                }
        }

    private val snapshots = workspaceSource.flatMapLatest { workspace ->
        workspace.state.map { workspace.filePath to it }
    }

    /**
     * Kept out of [State] on purpose: the page needs it in every phase, and [State.Initializing] and
     * [State.Error] carry no workspace data. Back has to work while the file loads and after a
     * decode failure - a pane-local overlay has no enclosing dialog the user could dismiss instead.
     */
    val callerWorkspaceId = workspaceSource
        .flatMapLatest { it.info }
        .map { it.callerWorkspaceId }
        .distinctUntilChanged()
        .asStateFlow()

    val state = combine(
        snapshots,
        renderErrorFlow,
        imageSourceFlow,
        pdfPageFlow,
        trashSettings.enabled.flow,
    ) { snapshot, renderError, imageSource, pdfPage, trashEnabled ->
        val (path, workspaceState) = snapshot
        val content = renderError?.let { ViewerContent.Failed(it) } ?: workspaceState.content
        State.Ready(
            content = content,
            fileInfo = workspaceState.fileInfo,
            path = path,
            imageSource = imageSource.takeIf { content is ViewerContent.Image },
            pdfPage = pdfPage.takeIf { content is ViewerContent.PdfPreview },
            actions = viewerActions(path = path, trashEnabled = trashEnabled),
        ) as State
    }
        .catch { emit(State.Error(it)) }
        // The replay cache must not retain the rendered PDF page bitmap after the page stops
        // collecting: keyed page ViewModels outlive their composables, so an infinite replay
        // expiration would accumulate one bitmap per visited PDF tab.
        .stateIn(
            scope = vmScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
            initialValue = State.Initializing,
        )

    /**
     * The full-size icon, held only while its dialog is open. Deliberately outside [State]: the
     * dialog is composed from the overlay slot, and a bitmap of this size must not sit in the state
     * a paused tab keeps replaying.
     */
    private val iconPreviewFlow = MutableStateFlow<IconPreviewState?>(null)
    val iconPreview: StateFlow<IconPreviewState?> = iconPreviewFlow

    /** Cancelled on dismiss and on re-open, so a stale render can never publish into a newer dialog. */
    private var iconPreviewJob: Job? = null

    /**
     * One save attempt at a time, as a single value.
     *
     * Three separate fields (in-flight marker, rendered bitmap, pending destination) had to be
     * cleared in agreement on every one of a dozen exit paths, and the reservation could not be
     * taken before the first suspension. Collapsing them means a save is reserved synchronously and
     * every terminal path is one assignment back to [IconSaveState.Idle].
     */
    private val iconSaveFlow = MutableStateFlow<IconSaveState>(IconSaveState.Idle)

    /** Non-null only while the user is being asked to confirm replacing a file. */
    val pendingIconOverwrite: StateFlow<IconSaveState.Confirming?> = iconSaveFlow
        .map { it as? IconSaveState.Confirming }
        .stateIn(vmScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        log(tag) { "Initialized for workspace $id" }

        workspaceRemote.events
            .handleResult<WorkspaceEvent.PickerResult>(callerWorkspaceId = id) { result ->
                log(tag) { "Picker result: ${result.selectedPaths.firstOrNull()} (${result.filename})" }
                val picking = iconSaveFlow.value as? IconSaveState.Picking
                if (picking == null || result.workspaceId != picking.pickerId) {
                    log(tag, WARN) { "Ignoring result from a picker we are not waiting on: ${result.workspaceId}" }
                    return@handleResult
                }
                val directory = result.selectedPaths.firstOrNull()
                val filename = result.filename
                if (directory == null || filename == null) {
                    log(tag, WARN) { "Picker returned no destination, dropping the save" }
                    iconSaveFlow.value = IconSaveState.Idle
                    return@handleResult
                }
                handleIconSaveDestination(picking.bitmap, directory, filename)
            }
            .launchIn(vmScope)

        // Backing out of the picker is terminal too. Without this the reservation would never clear
        // and every later save attempt would be refused as "one is already in progress".
        workspaceRemote.events
            .handleResult<WorkspaceEvent.ResultCancelled>(callerWorkspaceId = id) { cancelled ->
                val picking = iconSaveFlow.value as? IconSaveState.Picking ?: return@handleResult
                if (cancelled.workspaceId != picking.pickerId) return@handleResult
                log(tag, INFO) { "Save picker cancelled" }
                iconSaveFlow.value = IconSaveState.Idle
            }
            .launchIn(vmScope)
    }

    fun shareError(error: Throwable) {
        log(tag) { "shareError($error)" }
        chrome.shareWorkspaceError(error, "Viewer workspace ${id.shortTag}")
    }

    fun retry() = launch {
        log(tag, INFO) { "retry()" }
        renderErrorFlow.value = null
        attemptFlow.update { it + 1 }
        workspaceSource.first().reload()
    }

    fun nextPdfPage() = movePdfPage(delta = 1)

    fun previousPdfPage() = movePdfPage(delta = -1)

    private fun movePdfPage(delta: Int) {
        val ready = state.value as? State.Ready ?: return
        val pdf = ready.content as? ViewerContent.PdfPreview ?: return
        val displayed = ready.pdfPage ?: run {
            log(tag) { "movePdfPage($delta) ignored, no page on display yet" }
            return
        }
        // A native page render ignores cancellation and keeps allocating, so a second one may only
        // start once the current one has produced a bitmap or reported its failure.
        if (displayed.bitmap == null && !displayed.failed) {
            log(tag) { "movePdfPage($delta) ignored, page ${displayed.index} is still rendering" }
            return
        }
        val target = resolvePdfNavTarget(
            displayedIndex = displayed.index,
            pageCount = pdf.pageCount,
            delta = delta,
        ) ?: return
        log(tag) { "movePdfPage($delta) -> page $target" }
        pdfPageIndexFlow.value = target
    }

    fun close() = launch {
        log(tag, INFO) { "close()" }
        workspaceRemote.execute(WorkspaceAction.Close(id))
    }

    fun openWith() = launch {
        val path = workspaceSource.first().filePath
        log(tag, INFO) { "openWith($path)" }
        val launched = openWithIntentUseCase.openWithChooser(
            path = path,
            mime = MimeInfo.fromFileName(path.name).rawType,
            chooserTitle = context.getString(WorkspaceR.string.workspace_open_with_chooser_title),
        )
        if (!launched) errorEvents.emit(NoAppForFileException(path.name))
    }

    fun share() = launch {
        val target = workspaceSource.first().filePath
        log(tag, INFO) { "share($target)" }
        val item = object : ShareIntentUseCase.Item {
            override val path = target
            override val mimeType = MimeInfo.fromFileName(target.name).rawType
            override val displayName = target.name
        }
        val launched = shareIntentUseCase.shareWithChooser(
            items = listOf(item),
            chooserTitle = context.getString(CommonR.string.general_share_single_title, target.name),
        )
        if (!launched) errorEvents.emit(ViewerShareUnavailableException(target))
    }

    fun copyToClipboard() = clip(ClipboardClip.Paths.Mode.COPY)

    fun cutToClipboard() = clip(ClipboardClip.Paths.Mode.CUT)

    /**
     * The clipboard stores lookups, so this reuses the one the workspace already resolved. A state
     * without one means the file was never successfully looked up, i.e. there is nothing to clip.
     */
    private fun clip(mode: ClipboardClip.Paths.Mode) = launch {
        val workspace = workspaceSource.first()
        val lookup = workspace.state.value.lookup
        if (lookup == null) {
            log(tag, WARN) { "clip($mode) without a lookup for ${workspace.filePath}" }
            errorEvents.emit(ViewerFileGoneException(workspace.filePath))
            return@launch
        }
        log(tag, INFO) { "clip($mode): ${lookup.lookedUp}" }
        clipboardRepo.add(
            ClipboardClip.Paths(
                mode = mode,
                origin = id,
                paths = listOf(lookup),
            )
        )
        // The viewer shows no clipboard bar of its own, so without this the tap looks like a no-op.
        val message = when (mode) {
            ClipboardClip.Paths.Mode.COPY -> R.string.viewer_clipboard_copied
            ClipboardClip.Paths.Mode.CUT -> R.string.viewer_clipboard_cut
        }
        toastEvents.emit(caString { it.getString(message, lookup.name) })
    }

    /** Opens the file's folder as an Explorer tab of its own, beside the viewer rather than over it. */
    fun openLocation() = launch {
        val path = workspaceSource.first().filePath
        val parent = path.parent
        if (parent == null) {
            log(tag, WARN) { "openLocation() without a parent for $path" }
            return@launch
        }
        log(tag, INFO) { "openLocation($parent)" }
        workspaceRemote.createAndFocus(
            type = Workspace.Type.EXPLORER,
            arguments = ExplorerArguments.Default(startPath = parent),
            sourceWorkspaceId = id,
        )
    }

    /** Non-null while the user is being asked to confirm the delete. */
    private val deleteRequestFlow = MutableStateFlow<Set<APath<*>>?>(null)
    val deleteRequest: StateFlow<Set<APath<*>>?> = deleteRequestFlow

    /** Drives the confirmation dialog's trash-vs-permanent wording, same source as the delete icon. */
    val trashEnabled: StateFlow<Boolean> = trashSettings.enabled.flow
        .stateIn(vmScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Reserved synchronously so a double tap cannot submit the delete twice. */
    private val deleteInFlight = MutableStateFlow(false)

    /**
     * The conflict on display and the operation waiting on it, as one value.
     *
     * Deliberately one flow and eagerly started: as two WhileSubscribed flows, the id half had no
     * subscriber of its own - only the issue half is collected, for the sheet - so reading its
     * `.value` always returned the initial null and no resolution ever reached the operation.
     */
    private val pendingConflict: StateFlow<PendingConflict?> = chrome.pendingConflicts
        .map { conflicts ->
            conflicts.entries.firstOrNull()?.let { PendingConflict(operationId = it.key, issue = it.value) }
        }
        .stateIn(vmScope, SharingStarted.Eagerly, null)

    val issueState: StateFlow<Issue?> = pendingConflict
        .map { it?.issue }
        .stateIn(vmScope, SharingStarted.Eagerly, null)

    fun requestDelete() = launch {
        val path = workspaceSource.first().filePath
        log(tag, INFO) { "requestDelete($path)" }
        deleteRequestFlow.value = setOf(path)
    }

    fun dismissDelete() {
        log(tag) { "dismissDelete()" }
        deleteRequestFlow.value = null
    }

    /**
     * Closes the viewer once the delete actually succeeded. The workspace does not re-read the file
     * after an operation, so a viewer left open would keep rendering a file that no longer exists -
     * but a failed or cancelled delete must leave it exactly where it was.
     */
    fun confirmDelete(forcePermDelete: Boolean) {
        if (!deleteInFlight.compareAndSet(expect = false, update = true)) {
            log(tag, WARN) { "A delete is already in progress, ignoring" }
            return
        }
        deleteRequestFlow.value = null

        launch {
            try {
                val workspace = workspaceSource.first()
                // Attached before the submit and undispatched: `completedOperations` has no replay,
                // so a delete that finishes quickly would otherwise complete unobserved and leave
                // the viewer showing a deleted file. The id is not known until submit returns, so it
                // arrives through this gate - matching on the workspace alone would also accept a
                // delete this ViewModel did not start.
                val submittedId = CompletableDeferred<Operation.Id>()
                val completion = async(start = CoroutineStart.UNDISPATCHED) {
                    operationsManager.completedOperations.first { it.id == submittedId.await() }
                }
                submittedId.complete(workspace.delete(forcePermDelete = forcePermDelete))

                val snapshot = completion.await()
                val completed = snapshot.state
                // Nothing in the viewer can dismiss a finished operation - it has no operations bar -
                // so it would sit in the manager until the tab closes. History already has it.
                operationsManager.remove(snapshot.id)

                val error = completed.error
                if (error != null) {
                    // The viewer has no operations bar to carry the failure, so it reports it here.
                    log(tag, WARN) { "Delete failed, leaving the viewer open: ${error.asLog()}" }
                    if (error !is CancellationException) errorEvents.emit(error)
                    return@launch
                }
                // A delete the user resolved by skipping succeeds without removing anything, and
                // the file is still there to look at.
                if (completed.report?.affectedPaths.isNullOrEmpty()) {
                    log(tag, INFO) { "Delete removed nothing, leaving the viewer open" }
                    return@launch
                }
                log(tag, INFO) { "Delete completed, closing the viewer" }
                workspaceRemote.execute(WorkspaceAction.Close(id))
            } finally {
                deleteInFlight.value = false
            }
        }
    }

    fun resolveIssue(resolution: PathActionIssue.Resolution) = launch {
        val pending = pendingConflict.value
        if (pending == null) {
            log(tag, WARN) { "resolveIssue($resolution) with no pending issue" }
            return@launch
        }
        log(tag, INFO) { "resolveIssue(${pending.operationId}, $resolution)" }
        workspaceSource.first().resolveConflict(pending.operationId, resolution)
    }

    /** A conflict and the operation blocked on it, kept together so they cannot get out of step. */
    data class PendingConflict(
        val operationId: Operation.Id,
        val issue: Issue,
    )

    fun showIconPreview() {
        // One live render at a time, keyed by the job itself: a render started for a dialog that has
        // since been dismissed or reopened is cancelled outright, so neither its bitmap nor its
        // failure can land in the dialog that replaced it.
        iconPreviewJob?.cancel()
        iconPreviewFlow.value = IconPreviewState.Loading
        iconPreviewJob = vmScope.launch {
            val path = workspaceSource.first().filePath
            log(tag, INFO) { "showIconPreview($path)" }
            val bitmap = try {
                apkIconExporter.render(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(tag, ERROR) { "Icon render failed for $path: ${e.asLog()}" }
                null
            }
            if (bitmap == null) {
                iconPreviewFlow.value = null
                errorEvents.emit(ViewerIconUnavailableException(path))
                return@launch
            }
            iconPreviewFlow.value = IconPreviewState.Ready(bitmap)
        }
    }

    fun dismissIconPreview() {
        log(tag) { "dismissIconPreview()" }
        iconPreviewJob?.cancel()
        iconPreviewJob = null
        iconPreviewFlow.value = null
    }

    /**
     * Renders the icon once, here, and carries that bitmap through the picker and any overwrite
     * prompt. Re-reading the archive after the destination is chosen would export whatever occupies
     * the source path by then, which is not necessarily the APK the user was looking at.
     */
    /**
     * The reservation is taken synchronously, before the first suspension: two taps arriving in the
     * same frame would otherwise both pass an in-coroutine check and open two pickers.
     */
    fun saveIcon() {
        if (iconSaveFlow.value != IconSaveState.Idle) {
            log(tag, WARN) { "A save is already in progress, ignoring" }
            return
        }
        iconSaveFlow.value = IconSaveState.Preparing

        launch {
            try {
                val workspace = workspaceSource.first()
                val apk = workspace.state.value.content as? ViewerContent.Apk
                    ?: throw IllegalStateException("Not an APK, nothing to export")
                log(tag, INFO) { "saveIcon(${apk.apkInfo.id})" }

                val bitmap = (iconPreviewFlow.value as? IconPreviewState.Ready)?.bitmap
                    ?: apkIconExporter.render(workspace.filePath)
                    ?: throw ViewerIconUnavailableException(workspace.filePath)

                // The picker stacks on this pane; leaving the preview open would strand it underneath.
                dismissIconPreview()
                val created = workspaceRemote.launchPicker(
                    callerWorkspaceId = id,
                    startPath = workspace.filePath.parent,
                    selection = PickerConfig.Selection.SaveAs(
                        suggestedFilename = "${apk.apkInfo.id.name}-icon.png",
                    ),
                )
                // Anything but Success means no picker opened (e.g. the tab limit blocked it, which
                // the workspace layer reports itself), so the attempt ends here.
                val pickerId = (created as? WorkspaceAction.Create.Result.Success)?.newId
                if (pickerId == null) {
                    log(tag, WARN) { "Save picker was not created: $created" }
                    iconSaveFlow.value = IconSaveState.Idle
                    return@launch
                }
                iconSaveFlow.value = IconSaveState.Picking(pickerId, bitmap)
            } catch (e: Throwable) {
                iconSaveFlow.value = IconSaveState.Idle
                throw e
            }
        }
    }

    private fun handleIconSaveDestination(
        bitmap: Bitmap,
        directory: APath<*>,
        filename: String,
    ) = launch {
        try {
            // The picker validates too; re-validating here means a malformed event cannot produce a
            // path with separators or storage-invalid characters.
            val validation = filenameValidator.validate(filename, directory)
            if (validation is FilenameValidator.ValidationResult.Invalid) {
                throw IllegalArgumentException(
                    "Filename contains invalid characters: ${validation.invalidChars.joinToString("")}",
                )
            }

            val target = directory.child(filename)
            when (val decision = decideIconSave(target, apkIconExporter.inspectTarget(target))) {
                is IconSaveDecision.Write -> writeIcon(bitmap, decision.target, overwriteAuthorized = false)
                is IconSaveDecision.Confirm -> iconSaveFlow.value =
                    IconSaveState.Confirming(decision.target, bitmap)

                is IconSaveDecision.Reject -> throw decision.error
            }
        } catch (e: Throwable) {
            iconSaveFlow.value = IconSaveState.Idle
            throw e
        }
    }

    fun confirmIconOverwrite() {
        val confirming = iconSaveFlow.value as? IconSaveState.Confirming ?: return
        log(tag, INFO) { "confirmIconOverwrite(${confirming.target})" }
        writeIcon(confirming.bitmap, confirming.target, overwriteAuthorized = true)
    }

    fun dismissIconOverwrite() {
        log(tag) { "dismissIconOverwrite()" }
        if (iconSaveFlow.value is IconSaveState.Confirming) iconSaveFlow.value = IconSaveState.Idle
    }

    private fun writeIcon(bitmap: Bitmap, target: APath<*>, overwriteAuthorized: Boolean) = launch {
        iconSaveFlow.value = IconSaveState.Writing
        try {
            apkIconExporter.save(bitmap, target, overwriteAuthorized = overwriteAuthorized)
        } finally {
            iconSaveFlow.value = IconSaveState.Idle
        }
        toastEvents.emit(caString { it.getString(R.string.viewer_apk_icon_saved, target.name) })
    }

    /** Lifecycle of the full-size icon dialog; null means it is closed. */
    sealed interface IconPreviewState {
        data object Loading : IconPreviewState

        data class Ready(val bitmap: Bitmap) : IconPreviewState
    }

    /**
     * One icon export, start to finish. Every non-[Idle] state holds the single rendered bitmap, so
     * returning to [Idle] is the only cleanup any exit path has to perform.
     */
    sealed interface IconSaveState {
        data object Idle : IconSaveState

        /** Reserved: rendering the icon and opening the picker. */
        data object Preparing : IconSaveState

        data class Picking(val pickerId: Workspace.Id, val bitmap: Bitmap) : IconSaveState

        /** Waiting for the user to agree to replace what is already at [target]. */
        data class Confirming(val target: APath<*>, val bitmap: Bitmap) : IconSaveState

        data object Writing : IconSaveState
    }

    /** One rendered PDF page. A null [bitmap] with [failed] false means the render is still running. */
    data class PdfPage(
        val index: Int,
        val bitmap: Bitmap?,
        val failed: Boolean = false,
    )

    sealed interface State {
        data object Initializing : State

        data class Error(val error: Throwable) : State

        data class Ready(
            val content: ViewerContent,
            val fileInfo: ViewerFileInfo?,
            val path: APath<*>,
            val imageSource: ZoomableImageSource?,
            val pdfPage: PdfPage? = null,
            val actions: List<ViewerActionBarItem> = viewerActions(path, trashEnabled = false),
        ) : State
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ViewerWorkspaceViewModel
    }
}

/**
 * The viewer's action bar, in display order. Every entry acts on the one file the tab is showing,
 * so the only thing that varies is whether each is applicable: there is no parent folder to open at
 * a storage root, and delete only reads as recoverable when this file can really reach the trash.
 *
 * The trash question goes through [partitionByTrashSupport] rather than the setting alone: the
 * setting can be on for a file the trash cannot hold, and the confirmation dialog asks the same
 * function - an icon promising a recoverable delete over a dialog promising a permanent one is the
 * drift that shared function exists to prevent.
 */
internal fun viewerActions(path: APath<*>, trashEnabled: Boolean): List<ViewerActionBarItem> = listOf(
    ViewerActionBarItem.OpenWith,
    ViewerActionBarItem.Share,
    ViewerActionBarItem.Copy,
    ViewerActionBarItem.Cut,
    ViewerActionBarItem.OpenLocation(isEnabled = path.parent != null),
    ViewerActionBarItem.Delete(
        trashEnabled = trashEnabled && partitionByTrashSupport(setOf(path)).trashable.isNotEmpty(),
    ),
)

/**
 * Where a page step lands, or null when it would not move. [displayedIndex] is clamped first: a
 * document that shrank underneath the viewer may still have a larger index on display.
 */
internal fun resolvePdfNavTarget(displayedIndex: Int, pageCount: Int, delta: Int): Int? {
    if (pageCount < 1) return null
    val current = displayedIndex.coerceIn(0, pageCount - 1)
    val target = (current + delta).coerceIn(0, pageCount - 1)
    return target.takeIf { it != displayedIndex }
}
