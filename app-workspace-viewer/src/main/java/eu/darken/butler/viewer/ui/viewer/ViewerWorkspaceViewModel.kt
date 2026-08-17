package eu.darken.butler.viewer.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.ui.ViewModel3
import eu.darken.butler.viewer.core.GatewayZoomableImageSource
import eu.darken.butler.viewer.core.PdfPreviewLoader
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.viewer.core.ViewerPdfPreviewFailedException
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.core.NoAppForFileException
import eu.darken.butler.workspace.core.OpenWithIntentUseCase
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import me.saket.telephoto.zoomable.ZoomableImageSource
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
    chromeFactory: WorkspacePageChrome.Factory,
) : ViewModel3(dispatchers, logTag("Viewer", "Workspace", id.shortTag, "Page")) {

    private val chrome = chromeFactory.create(workspaceId = id, scope = vmScope)

    val shareIntentEvent = chrome.shareIntentEvent

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

    /**
     * The first page is rendered here, not in the workspace: a full-screen page bitmap must not
     * survive in workspace state, where a paused tab would keep holding it. Emits null first so the
     * page shows its spinner while the render runs, and reports a failed render as a page error.
     */
    private val pdfFirstPageFlow = combine(workspaceSource, attemptFlow) { workspace, attempt ->
        workspace to attempt
    }
        .distinctUntilChanged()
        .flatMapLatest { (workspace, _) ->
            workspace.state
                .map { it.content as? ViewerContent.PdfPreview }
                .distinctUntilChanged()
                .flatMapLatest { pdf ->
                    if (pdf == null) {
                        flowOf<Bitmap?>(null)
                    } else {
                        flow<Bitmap?> {
                            emit(null)
                            val bitmap = pdfPreviewLoader.firstPage(workspace.filePath)
                            if (bitmap == null) {
                                renderErrorFlow.value = ViewerPdfPreviewFailedException(workspace.filePath)
                            }
                            emit(bitmap)
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
        pdfFirstPageFlow,
    ) { snapshot, renderError, imageSource, firstPage ->
        val (path, workspaceState) = snapshot
        val content = renderError?.let { ViewerContent.Failed(it) } ?: workspaceState.content
        State.Ready(
            content = content,
            fileInfo = workspaceState.fileInfo,
            path = path,
            imageSource = imageSource.takeIf { content is ViewerContent.Image },
            pdfFirstPage = firstPage.takeIf { content is ViewerContent.PdfPreview },
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

    init {
        log(tag) { "Initialized for workspace $id" }
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

    sealed interface State {
        data object Initializing : State

        data class Error(val error: Throwable) : State

        data class Ready(
            val content: ViewerContent,
            val fileInfo: ViewerFileInfo?,
            val path: APath<*>,
            val imageSource: ZoomableImageSource?,
            val pdfFirstPage: Bitmap? = null,
        ) : State
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ViewerWorkspaceViewModel
    }
}
