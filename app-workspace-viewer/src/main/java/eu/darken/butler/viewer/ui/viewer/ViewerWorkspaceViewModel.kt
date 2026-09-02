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
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorIncidentStore
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.validation.FilenameValidator
import eu.darken.butler.common.flow.SingleEventFlow
import eu.darken.butler.common.flow.combine as combineMany
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.pkgs.installer.AppInstallEvent
import eu.darken.butler.common.pkgs.installer.AppInstallInspector
import eu.darken.butler.common.pkgs.installer.AppInstaller
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.viewer.R
import eu.darken.butler.viewer.core.ApkIconExporter
import eu.darken.butler.viewer.core.GatewayZoomableImageSource
import eu.darken.butler.viewer.core.IconSaveDecision
import eu.darken.butler.viewer.core.decideIconSave
import eu.darken.butler.viewer.core.PdfPreviewLoader
import eu.darken.butler.viewer.core.ViewerBrokenSymlinkException
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.viewer.core.ViewerExternalChange
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.viewer.core.ViewerFileGoneException
import eu.darken.butler.viewer.core.ViewerIconUnavailableException
import eu.darken.butler.viewer.core.ViewerShareUnavailableException
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.contracts.viewer.ViewerArguments
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.NoAppForFileException
import eu.darken.butler.workspace.core.OpenWithIntentUseCase
import eu.darken.butler.workspace.core.ShareIntentUseCase
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.clipboard.ClipboardClip
import eu.darken.butler.workspace.core.clipboard.ClipboardRepo
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.operations.AppInstallOperation
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    private val appInstallInspector: AppInstallInspector,
    private val appInstaller: AppInstaller,
    private val appInstallOperationFactory: AppInstallOperation.Factory,
    private val apkIconExporter: ApkIconExporter,
    private val filenameValidator: FilenameValidator,
    private val errorIncidentStore: ErrorIncidentStore,
    chromeFactory: WorkspacePageChrome.Factory,
) : ViewModel3(dispatchers, logTag("Viewer", "Workspace", id.shortTag, "Page")) {

    private val chrome = chromeFactory.create(workspaceId = id, scope = vmScope)

    val shareIntentEvent = chrome.shareIntentEvent

    val pendingErrorShare = chrome.pendingErrorShare

    /** One-shot confirmations, e.g. after an icon was written to disk. */
    val toastEvents = SingleEventFlow<CaString>()

    private val workspaceSource = workspaceProvider.retrieve(id)
        .map { it as? ViewerWorkspace }
        .filterNotNull()

    /** Bumped by [retry] so a failed decode gets a fresh image source instead of the poisoned one. */
    private val attemptFlow = MutableStateFlow(0)

    /**
     * Resolve and Coil-load failures reported by the image source, which the workspace cannot see.
     *
     * Stored with the source they belong to: this tab is replaced under its own id when a stream is
     * saved to a file, so the ViewModel survives the swap and a failure kept as a bare throwable
     * would keep the saved file's page showing the old stream's error. A late callback from the
     * disposed source cannot poison the new one either - it names the source it came from.
     */
    private val renderErrorFlow = MutableStateFlow<RenderFailure?>(null)

    /**
     * [WorkspaceProvider.retrieve] is derived from the workspace collection, so it re-emits when an
     * unrelated tab opens or closes. Only the source and the retry attempt may produce a new image
     * source - a fresh one would re-resolve and open another stream for the same picture.
     */
    private val imageSourceFlow = combine(workspaceSource, attemptFlow) { workspace, attempt ->
        workspace.source to attempt
    }
        .distinctUntilChanged()
        .map { (source, attempt) ->
            imageSourceFactory.create(
                source = source,
                // Tagged with the attempt AND the source that created it: [renderErrorFlow] is one
                // global slot, so a disposed source reporting its failure late would otherwise
                // poison whatever replaced it - a fresh attempt on the same source (caught here) or
                // a different source this tab was rebound to (caught where the state is composed).
                onError = { error ->
                    if (attemptFlow.value == attempt) {
                        renderErrorFlow.value = RenderFailure(source = source, error = error)
                    }
                },
            )
        }

    /**
     * Which page the user navigated to, with the source it was chosen in. Not persisted: a restored
     * session starts at page one again.
     *
     * Keyed by source because this tab can be replaced under its own id - stepping to the next file,
     * or saving a stream - while the ViewModel lives on. A bare index would carry the old document's
     * page into the new one, and clearing it on the swap would request page 0 of the document that
     * is on its way out.
     */
    private val pdfPageIndexFlow = MutableStateFlow<PdfPageSelection?>(null)

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
                            .map { selection -> selection?.takeIf { it.source == workspace.source }?.index ?: 0 }
                            // A document that shrank underneath the viewer must not render a page
                            // index that no longer exists.
                            .map { it.coerceIn(0, pdf.pageCount - 1) }
                            .distinctUntilChanged()
                            .flatMapLatest { page ->
                                flow<PdfPage?> {
                                    emit(PdfPage(index = page, bitmap = null))
                                    val bitmap = pdfPreviewLoader.page(workspace.source, page)
                                    emit(PdfPage(index = page, bitmap = bitmap, failed = bitmap == null))
                                }
                            }
                    }
                }
        }

    /**
     * What [snapshots] last carried, for the failure handler below: by then the source flow has
     * already thrown, and recollecting it to name the file would run the failing lookup again.
     */
    private var lastViewerContext: Map<String, String?> = emptyMap()

    /**
     * The failure this tab froze an incident for, with the source it happened on. Read by
     * [freezeFailure], which is the only thing that writes it.
     */
    private var frozenFailure: FrozenFailure? = null

    private val snapshots = workspaceSource.flatMapLatest { workspace ->
        workspace.state.map { workspace.source to it }
    }
        .onEach { (source, _) -> lastViewerContext = viewerContext(source) }

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

    /**
     * The last listing the current origin published, so a paused origin - whose stand-in holds none -
     * does not take the arrows away. Outside the per-workspace flow because a step replaces this tab
     * under its own id and the ViewModel outlives each [ViewerWorkspace] incarnation.
     */
    private var lastListing: Pair<Workspace.Id, List<APath<*>>>? = null

    /** Where a step from the file on display would land, or null when there is nothing to step to. */
    private val neighboursFlow: Flow<ViewerNeighbours?> = workspaceSource.flatMapLatest { workspace ->
        val originId = workspace.listingSourceId
        val path = workspace.storedPath
        if (originId == null || path == null) return@flatMapLatest flowOf(null)
        val live = workspaceProvider.retrieve(originId).flatMapLatest { origin ->
            (origin as? Workspace.FileListingSource)?.fileListing ?: flowOf<List<APath<*>>?>(null)
        }
        // A paused stand-in stays in the info list with a Paused lifecycle; a closed id leaves it.
        val originExists = workspaceRemote.state
            .map { state -> state.infos.any { it.id == originId } }
            .distinctUntilChanged()
        combine(live, originExists) { listing, exists ->
            when {
                !exists -> null
                listing != null -> listing.also { lastListing = originId to it }
                else -> lastListing?.takeIf { it.first == originId }?.second
            }
        }.map { files -> files?.let { resolveNeighbours(path, it) } }
    }.distinctUntilChanged()

    val state = combineMany(
        snapshots,
        renderErrorFlow,
        imageSourceFlow,
        pdfPageFlow,
        trashSettings.enabled.flow,
        neighboursFlow,
    ) { snapshot, renderFailure, imageSource, pdfPage, trashEnabled, rawNeighbours ->
        val (source, workspaceState) = snapshot
        // Only the failure of what is on display now: one recorded for a source this tab has since
        // been rebound away from would hide the new content behind the old one's error.
        val renderError = renderFailure?.takeIf { it.source == source }?.error
        // The workspace did the actual lookup, so its failure outranks a render error. Refreshing a
        // deleted file briefly composes an image source against the missing file, and that generic
        // decode error must not replace the accurate "this file is gone" card.
        val content = when {
            workspaceState.content is ViewerContent.Failed -> workspaceState.content
            renderError != null -> ViewerContent.Failed(renderError)
            else -> workspaceState.content
        }
        // The instance that reaches the page is the one the share action will name: the workspace
        // may have turned what it caught into a ViewerFileGoneException on the way here.
        when {
            content is ViewerContent.Failed -> freezeFailure(source, content.error)
            // Loading is the seed of the very reload that republishes the failure, so only a load
            // that resolved to something ends the failure the anchor stands for.
            content !is ViewerContent.Loading -> frozenFailure = null
        }
        // Every verdict that means "the content is unreachable" has to count. A reload clears the
        // probe's flag and reports the loss as a failure instead: as a gone file when the path
        // itself vanished, or as a broken symlink when the link survived its target. The actions
        // must stay hidden in all three cases.
        val failure = (workspaceState.content as? ViewerContent.Failed)?.error
        val isGone = workspaceState.externalChange == ViewerExternalChange.Gone ||
            failure is ViewerFileGoneException ||
            failure is ViewerBrokenSymlinkException
        // Only the snapshot resolved for the file on display: this tab is replaced under its own id
        // when a step lands, and the neighbours of the file it left would step the wrong way.
        val neighbours = rawNeighbours?.takeIf { it.current == (source as? ViewerSource.Stored)?.path }
        State.Ready(
            content = content,
            fileInfo = workspaceState.fileInfo,
            source = source,
            imageSource = imageSource.takeIf { content is ViewerContent.Image },
            pdfPage = pdfPage.takeIf { content is ViewerContent.PdfPreview },
            actions = viewerActions(
                source = source,
                trashEnabled = trashEnabled,
                content = content,
                isGone = isGone,
                neighbours = neighbours,
            ),
            externalChange = workspaceState.externalChange,
            neighbours = neighbours,
        ) as State
    }
        .catch { error ->
            // The page offers Share on this card too, so it needs an incident like every other
            // failure the user is shown.
            errorIncidentStore.remember(error, lastViewerContext)
            emit(State.Error(error))
        }
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

        // Only the Saver THIS viewer launched may rebind it: several can be open at once, and the
        // event carries no claim on us beyond naming us as its caller.
        workspaceRemote.events
            .handleResult<WorkspaceEvent.SaveResult>(callerWorkspaceId = id) { result ->
                val saving = saveCopyFlow.value as? SaveCopyState.Saving
                if (saving == null || result.workspaceId != saving.saverId) {
                    log(tag, WARN) { "Ignoring a save result from a Saver we did not launch: ${result.workspaceId}" }
                    return@handleResult
                }
                val savedPath = result.savedPaths.firstOrNull()
                if (savedPath == null) {
                    // The Saver is still open and can be retried; the reservation stays with it and
                    // is only released when it reports a written file or closes. Clearing it here
                    // would leave the retry's result with nobody listening.
                    log(tag, WARN) { "The save reported no written file, staying on the stream" }
                    return@handleResult
                }
                rebindToSavedFile(savedPath)
            }
            .launchIn(vmScope)

        // The Saver closing without having saved is terminal as well; without this the reservation
        // would never clear and "Save a copy" would be dead for the rest of this tab's life.
        workspaceRemote.events
            .filterIsInstance<WorkspaceEvent.Closed>()
            .onEach { closed ->
                val saving = saveCopyFlow.value as? SaveCopyState.Saving ?: return@onEach
                if (closed.workspaceId != saving.saverId) return@onEach
                log(tag, INFO) { "The Saver we launched was closed" }
                saveCopyFlow.value = SaveCopyState.Idle
            }
            .launchIn(vmScope)
    }

    /**
     * Freezes [error], unless it is the failure already frozen for [source] coming back on a fresh
     * throwable: the workspace reloads whenever this tab is resumed, and the decode or lookup that
     * failed fails again with a new instance. Minting for that one would stamp the report with the
     * resume instead of the failure, and spool another log trail per resume.
     *
     * The anchor is left on the first throwable rather than moved forward, so the third and fourth
     * recurrence point at the same incident instead of chaining onto each other. Only for as long
     * as the store still holds it: once enough unrelated errors have evicted the anchored entry,
     * there is nothing left to alias against, and this re-bases on the recurrence at hand rather
     * than leaving it with no incident at all.
     */
    private suspend fun freezeFailure(source: ViewerSource, error: Throwable) {
        val anchor = frozenFailure
        if (anchor != null &&
            anchor.source == source &&
            anchor.error.javaClass == error.javaClass &&
            anchor.error.message == error.message
        ) {
            if (errorIncidentStore.alias(error, anchor.error)) return
        }
        errorIncidentStore.remember(error, viewerContext(source))
        frozenFailure = FrozenFailure(source = source, error = error)
    }

    private fun viewerContext(source: ViewerSource): Map<String, String?> = mapOf(
        "viewer.contentPath" to (source as? ViewerSource.Stored)?.path?.path,
        "viewer.contentType" to source.mime.rawType,
    )

    fun shareError(error: Throwable) = launch {
        log(tag) { "shareError($error)" }
        val source = (state.value as? State.Ready)?.source
        val incident = errorIncidentStore.getOrFreeze(
            error = error,
            context = source?.let { viewerContext(it) } ?: emptyMap(),
        )
        chrome.shareWorkspaceError(incident)
    }

    fun confirmErrorShare() = chrome.confirmErrorShare()

    fun dismissErrorShare() = chrome.dismissErrorShare()

    /**
     * Suspends rather than launching: the page polls this on a timer, and a fire-and-forget probe
     * would let the next tick start before a slow root or ADB gateway answered, leaving two probes
     * in flight and outliving the page's RESUMED window.
     */
    suspend fun checkExternalChange() {
        workspaceSource.first().checkExternalChange()
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
        pdfPageIndexFlow.value = PdfPageSelection(source = ready.source, index = target)
    }

    fun showPreviousFile() = stepFile(delta = -1)

    fun showNextFile() = stepFile(delta = 1)

    /** Held for the whole swap, not just the create; see [stepFile]. */
    private var fileStep: Job? = null

    /**
     * Moves this tab to a neighbour by REPLACING it with a viewer on that file - the same mechanism
     * and for the same reasons as [rebindToSavedFile]; a viewer's file comes from its creation
     * arguments and cannot be swapped in place.
     */
    private fun stepFile(delta: Int) {
        val ready = state.value as? State.Ready ?: return
        val neighbours = ready.neighbours ?: return
        val target = (if (delta < 0) neighbours.previous else neighbours.next) ?: run {
            log(tag) { "stepFile($delta) ignored, no neighbour" }
            return
        }
        if (fileStep?.isActive == true) {
            log(tag) { "stepFile($delta) ignored, a step is in flight" }
            return
        }
        fileStep = vmScope.launch {
            val workspace = workspaceSource.first()
            val arguments = workspace.siblingArguments(target) ?: return@launch
            log(tag, INFO) { "stepFile($delta) -> $target" }
            val result = workspaceRemote.createAndFocus(
                type = Workspace.Type.VIEWER,
                arguments = arguments,
                replace = id,
                id = id,
                // The neighbour may already be open in another tab; sending the user there would
                // leave this one on the file they just stepped away from.
                skipContentDedup = true,
            )
            // The guard has to outlive the create: the page state observes the swap asynchronously,
            // and a tap in between would read the old file's neighbours.
            if (result is WorkspaceAction.Create.Result.Success) {
                workspaceSource.first { it.storedPath == target }
            }
        }
    }

    fun close() = launch {
        log(tag, INFO) { "close()" }
        workspaceRemote.execute(WorkspaceAction.Close(id))
    }

    /**
     * Both hand-off actions need a FileProvider URI, which only exists for a real local file. The
     * action bar hides them for streamed content, so a null path here means the UI and this got out
     * of step rather than a case to recover from.
     */
    fun openWith() = launch {
        val workspace = workspaceSource.first()
        val path = workspace.storedPath ?: return@launch
        log(tag, INFO) { "openWith($path)" }
        val launched = openWithIntentUseCase.openWithChooser(
            path = path,
            mime = workspace.source.mime.rawType,
            chooserTitle = context.getString(WorkspaceR.string.workspace_open_with_chooser_title),
        )
        if (!launched) errorEvents.emit(NoAppForFileException(path.name))
    }

    fun share() = launch {
        val workspace = workspaceSource.first()
        val target = workspace.storedPath ?: return@launch
        log(tag, INFO) { "share($target)" }
        val item = object : ShareIntentUseCase.Item {
            override val path = target
            override val mimeType = workspace.source.mime.rawType
            override val displayName = target.name
        }
        val launched = shareIntentUseCase.shareWithChooser(
            items = listOf(item),
            chooserTitle = context.getString(CommonR.string.general_share_single_title, target.name),
        )
        if (!launched) errorEvents.emit(ViewerShareUnavailableException(target))
    }

    /**
     * Writes streamed content somewhere the user picks, which is the only way to keep it: the grant
     * dies with the task, and it cannot be handed to another app. The Saver streams straight from
     * the URI to the destination, so this still copies nothing into Butler's own storage.
     *
     * The reservation is taken synchronously, before the first suspension: two taps in the same
     * frame would otherwise both pass an in-coroutine check and stack two Savers over one file.
     */
    fun saveCopy() {
        if (saveCopyFlow.value != SaveCopyState.Idle) {
            log(tag, WARN) { "A save is already in progress, ignoring" }
            return
        }
        saveCopyFlow.value = SaveCopyState.Preparing

        launch {
            try {
                val streamed = workspaceSource.first().source as? ViewerSource.Streamed
                if (streamed == null) {
                    log(tag, WARN) { "saveCopy() on stored content, nothing to save" }
                    saveCopyFlow.value = SaveCopyState.Idle
                    return@launch
                }
                log(tag, INFO) { "saveCopy($streamed)" }
                val created = workspaceRemote.createAndFocus(
                    type = Workspace.Type.SAVER,
                    arguments = SaverArguments.Default(
                        sourceUris = listOf(streamed.uri.toString()),
                        callerWorkspaceId = id,
                        // So this viewer can rebind to the file once it exists.
                        reportSavedPaths = true,
                    ),
                )
                // Anything but Success means no Saver opened, so the attempt ends here.
                val saverId = (created as? WorkspaceAction.Create.Result.Success)?.newId
                if (saverId == null) {
                    log(tag, WARN) { "Saver was not created: $created" }
                    saveCopyFlow.value = SaveCopyState.Idle
                    return@launch
                }
                saveCopyFlow.value = SaveCopyState.Saving(saverId)
            } catch (e: Throwable) {
                saveCopyFlow.value = SaveCopyState.Idle
                throw e
            }
        }
    }

    /**
     * Binds this tab to the file the Saver just wrote by REPLACING it with a viewer on that path,
     * so everything a real file supports - browsing a container, opening, sharing, deleting -
     * becomes available without the user having to find the copy first.
     *
     * A replacement rather than a mutation: the source decides the workspace's identity (its
     * content path, whether it may be persisted or paused), all of which are read from the
     * creation arguments. The new tab inherits the old one's pane slot and focus.
     *
     * Published under THIS tab's id: the Saver that triggered the rebind is our own sub-workspace,
     * and a replacement carrying a fresh id would leave it hanging off an id that no longer exists,
     * so the repo would close it as orphaned right as it reports success.
     */
    private suspend fun rebindToSavedFile(savedPath: APath<*>) {
        log(tag, INFO) { "rebindToSavedFile($savedPath)" }
        workspaceRemote.createAndFocus(
            type = Workspace.Type.VIEWER,
            arguments = ViewerArguments.Default(
                filePath = savedPath,
                // Still the same shared file, so what the sender wrote about it still applies.
                caption = workspaceSource.first().sharedCaption,
            ),
            replace = id,
            id = id,
            // The user may already have the destination open in another tab; sending them there
            // would strand this one on a stream of a file that now exists.
            skipContentDedup = true,
        )
    }

    /** One "Save a copy" at a time; [Saving] holds the Saver whose result this viewer accepts. */
    private sealed interface SaveCopyState {
        data object Idle : SaveCopyState

        /** Reserved: the Saver is being created. */
        data object Preparing : SaveCopyState

        data class Saving(val saverId: Workspace.Id) : SaveCopyState
    }

    private val saveCopyFlow = MutableStateFlow<SaveCopyState>(SaveCopyState.Idle)

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
            log(tag, WARN) { "clip($mode) without a lookup for ${workspace.source}" }
            errorEvents.emit(ViewerFileGoneException(workspace.source.displayName))
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
        // Streamed content has no folder; the action bar hides this for it.
        val path = workspaceSource.first().storedPath ?: return@launch
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

    /**
     * Opens the archive's contents as an Explorer tab beside the viewer, exactly like
     * [openLocation] does for a folder. Nothing is extracted or copied: the Explorer reads the
     * container through the gateway.
     */
    fun browseArchive() = launch {
        // Streamed content has no container path; the page offers "Save a copy" for it instead.
        val path = workspaceSource.first().storedPath ?: return@launch
        log(tag, INFO) { "browseArchive($path)" }
        workspaceRemote.createAndFocus(
            type = Workspace.Type.EXPLORER,
            arguments = ExplorerArguments.Default(startPath = ArchivePath.root(path)),
            sourceWorkspaceId = id,
        )
    }

    /**
     * Installs the APK or app bundle this tab is showing.
     *
     * Inspection runs here rather than inside the operation so an unreadable, protected or
     * unsupported container is answered right away instead of behind a progress bar. The
     * unknown-sources check is a preflight for the same reason: without elevated access the platform
     * installer is the only route, and it refuses to run until Butler is an authorized install
     * source, so the user goes to the settings page and no operation is created.
     */
    fun install() = launch {
        val path = workspaceSource.first().storedPath ?: return@launch
        log(tag, INFO) { "install($path)" }
        try {
            val plan = appInstallInspector.inspect(path)
            if (!appInstaller.hasElevation() && !appInstaller.canUseSystemInstaller()) {
                log(tag, INFO) { "install($path): Butler is not an authorized install source yet" }
                context.startActivity(appInstaller.unknownSourcesSettings())
                toastEvents.emit(WorkspaceR.string.workspace_install_unknown_sources_required.toCaString())
                return@launch
            }

            val events = MutableSharedFlow<AppInstallEvent>(extraBufferCapacity = 16)
            // Subscribed before submitting, so an event emitted right at the start is not missed.
            events
                .filterIsInstance<AppInstallEvent.ObbFailed>()
                .onEach { toastEvents.emit(WorkspaceR.string.workspace_install_obb_failed.toCaString(it.reason)) }
                .launchInViewModel()

            // Closing this tab cancels the operation outright, which abandons the install session.
            // If the system's confirm dialog is already on screen the platform owns it from there
            // and may still complete the install on its own.
            operationsManager.submit(
                appInstallOperationFactory.create(
                    installOrigin = Operation.Metadata.Origin.Viewer(id),
                    plan = plan,
                    events = events,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "install($path) failed: ${e.asLog()}" }
            errorEvents.emit(e)
        }
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
        // Deleting someone else's shared content is not ours to do; the action bar hides it.
        val path = workspaceSource.first().storedPath ?: return@launch
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
            // APKs always arrive as a real file - the arrival flow imports rather than streams them
            // - so the icon actions are only ever offered for a stored source.
            val path = workspaceSource.first().storedPath ?: return@launch
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

                val apkPath = workspace.storedPath
                    ?: throw IllegalStateException("Streamed content has no APK to export from")
                val bitmap = (iconPreviewFlow.value as? IconPreviewState.Ready)?.bitmap
                    ?: apkIconExporter.render(apkPath)
                    ?: throw ViewerIconUnavailableException(apkPath)

                // The picker stacks on this pane; leaving the preview open would strand it underneath.
                dismissIconPreview()
                val created = workspaceRemote.launchPicker(
                    callerWorkspaceId = id,
                    startPath = apkPath.parent,
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

    /** A render failure and the source it came from, kept together so they cannot get out of step. */
    private data class RenderFailure(
        val source: ViewerSource,
        val error: Throwable,
    )

    /** The throwable an incident was frozen for, and the source it was showing. */
    private data class FrozenFailure(
        val source: ViewerSource,
        val error: Throwable,
    )

    /** The page the user chose, and the document they chose it in. */
    private data class PdfPageSelection(
        val source: ViewerSource,
        val index: Int,
    )

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
            val source: ViewerSource,
            val imageSource: ZoomableImageSource?,
            val pdfPage: PdfPage? = null,
            val neighbours: ViewerNeighbours? = null,
            val actions: List<ViewerActionBarItem> = viewerActions(
                source,
                trashEnabled = false,
                content = content,
                neighbours = neighbours,
            ),
            val externalChange: ViewerExternalChange? = null,
        ) : State
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ViewerWorkspaceViewModel
    }
}

/**
 * The viewer's action bar, in display order.
 *
 * Streamed content gets exactly one action. Every other entry needs a path Butler owns: handing the
 * file to another app needs a FileProvider URI, and a grant we were given cannot be passed on;
 * copy and cut put paths on the clipboard for a later paste; there is no folder to open; and
 * deleting content another app owns is not ours to do. "Save a copy" is what turns it into a file
 * that has all of them.
 *
 * For a stored file the only thing that varies is applicability: there is no parent folder at a
 * storage root, delete only reads as recoverable when this file can really reach the trash, only a
 * package file can be installed, and browsing is offered only for a container that can actually be
 * opened where it lies - which is why the resolved [content] is an input here rather than just the
 * source.
 *
 * The trash question goes through [partitionByTrashSupport] rather than the setting alone: the
 * setting can be on for a file the trash cannot hold, and the confirmation dialog asks the same
 * function - an icon promising a recoverable delete over a dialog promising a permanent one is the
 * drift that shared function exists to prevent.
 *
 * [isGone] drops everything that needs the file itself, the same way streamed content never gets
 * those entries. Opening its location survives: the folder is still there, and it is where the user
 * would go looking for what happened to the file.
 *
 * [neighbours] leads the bar when the viewer was opened from a listing it can step through, [isGone]
 * or not: a file deleted behind the viewer's back stays in that listing until it is refreshed, and
 * moving on is the useful thing to do then.
 */
internal fun viewerActions(
    source: ViewerSource,
    trashEnabled: Boolean,
    content: ViewerContent = ViewerContent.Loading,
    isGone: Boolean = false,
    neighbours: ViewerNeighbours? = null,
): List<ViewerActionBarItem> = when (source) {
    is ViewerSource.Streamed -> listOf(ViewerActionBarItem.SaveCopy)

    is ViewerSource.Stored -> buildList {
        if (neighbours != null) {
            add(ViewerActionBarItem.PreviousFile(isEnabled = neighbours.previous != null))
            add(ViewerActionBarItem.NextFile(isEnabled = neighbours.next != null))
        }
        if (!isGone) {
            // Leads for a package file: it is what the user came for, and everything below applies
            // to the file rather than to the app it would install.
            if (content is ViewerContent.Apk || content is ViewerContent.AppBundle) {
                add(ViewerActionBarItem.Install)
            }
            // Leads for a container the Explorer can open in place: it is what the user came for,
            // and the file actions below apply to the container, not to what is inside it. Opening
            // a container that is gone would land the Explorer on nothing, so it goes with them.
            // A bundle keeps the offer too - it is a browsable zip, and a tap no longer browses it.
            val browsable = (content as? ViewerContent.Archive)?.access ==
                ViewerContent.Archive.Access.BROWSABLE
            if (browsable || content is ViewerContent.AppBundle) {
                add(ViewerActionBarItem.BrowseArchive)
            }
            // Handing a file to another app needs a file:// or content:// URI, which a file on a
            // server does not have, so those two are not offered for it.
            if (source.path !is SmbPath) {
                add(ViewerActionBarItem.OpenWith)
                add(ViewerActionBarItem.Share)
            }
            add(ViewerActionBarItem.Copy)
            add(ViewerActionBarItem.Cut)
        }
        add(ViewerActionBarItem.OpenLocation(isEnabled = source.path.parent != null))
        if (!isGone) {
            add(
                ViewerActionBarItem.Delete(
                    trashEnabled = trashEnabled &&
                        partitionByTrashSupport(setOf(source.path)).trashable.isNotEmpty(),
                ),
            )
        }
    }
}

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
