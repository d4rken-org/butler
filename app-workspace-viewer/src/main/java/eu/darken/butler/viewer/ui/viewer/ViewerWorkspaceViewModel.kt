package eu.darken.butler.viewer.ui.viewer

import android.content.Context
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
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerFileInfo
import eu.darken.butler.viewer.core.ViewerWorkspace
import eu.darken.butler.workspace.core.NoAppForFileException
import eu.darken.butler.workspace.core.OpenWithIntentUseCase
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import me.saket.telephoto.zoomable.ZoomableImageSource
import eu.darken.butler.workspace.R as WorkspaceR

@HiltViewModel(assistedFactory = ViewerWorkspaceViewModel.Factory::class)
class ViewerWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    dispatchers: DispatcherProvider,
    @ApplicationContext private val context: Context,
    private val workspaceProvider: WorkspaceProvider,
    private val imageSourceFactory: GatewayZoomableImageSource.Factory,
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

    private val snapshots = workspaceSource.flatMapLatest { workspace ->
        workspace.state.map { workspace.filePath to it }
    }

    val state = combine(snapshots, renderErrorFlow, imageSourceFlow) { snapshot, renderError, imageSource ->
        val (path, workspaceState) = snapshot
        val content = renderError?.let { ViewerContent.Failed(it) } ?: workspaceState.content
        State.Ready(
            content = content,
            fileInfo = workspaceState.fileInfo,
            path = path,
            imageSource = imageSource.takeIf { content is ViewerContent.Image },
        ) as State
    }
        .catch { emit(State.Error(it)) }
        .asStateFlow(State.Initializing)

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
        ) : State
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): ViewerWorkspaceViewModel
    }
}
