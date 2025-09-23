package eu.darken.butler.explorer.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.filesystem.FileSystemHinter
import eu.darken.butler.explorer.core.operations.DeleteOperation
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.operations.ExplorerOperation
import eu.darken.butler.explorer.core.operations.handlers.CopyOperation
import eu.darken.butler.explorer.core.operations.handlers.CreateOperation
import eu.darken.butler.explorer.core.operations.handlers.MoveOperation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.preview.ExplorerPreviewData
import eu.darken.butler.workspace.core.tracker.PathAccessTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize


class ExplorerWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: Arguments?,
    dispatcherProvider: DispatcherProvider,
    private val browsingEngineFactory: BrowsingEngine.Factory,
    private val fileSystemHinter: FileSystemHinter,
    private val breadcrumbGenerator: BreadcrumbGenerator,
    private val pathAccessTracker: PathAccessTracker,
    private val issueHandler: IssueHandler,
    private val operationsManager: OperationsManager,
    private val deleteOperationFactory: DeleteOperation.Factory,
    private val createOperationFactory: CreateOperation.Factory,
    private val copyOperationFactory: CopyOperation.Factory,
    private val moveOperationFactory: MoveOperation.Factory,
) : Workspace {

    private val tag = logTag("Explorer", "Workspace", id.shortTag)

    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    private val browsingEngine = browsingEngineFactory.create(id)

    override val type: Workspace.Type = Workspace.Type.EXPLORER

    override val info: MutableStateFlow<Workspace.Info> = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = when {
                Bugs.isDebug -> "Explorer ${id.shortTag}".toCaString()
                else -> R.string.explorer_title.toCaString()
            },
            previewData = ExplorerPreviewData(),
        )
    )

    val current = MutableStateFlow<State>(State())

    private val navigationRequests = MutableSharedFlow<ExplorerNavigation>(replay = 1)

    data class State(
        val currentTarget: ExplorerNavigation.Target? = null,
        val currentLocation: ExplorerLocation? = null,
        val currentBreadcrumbs: List<ExplorerBreadcrumb>? = null,
        val navigationHistory: List<ExplorerNavigation.Target> = emptyList(),
        val historyIndex: Int = 0,
        val isLoading: Boolean = false,
        val isLoadingExtended: Boolean = false,
        val error: Throwable? = null,
        val progress: Progress.Data? = null,
        val activeOperations: Map<Operation.Id, ExplorerOperation.State> = emptyMap(),
        val pendingConflicts: Map<Operation.Id, ExplorerOperation.State.Waiting> = emptyMap(),
    ) {
        val canGoBack: Boolean get() = historyIndex > 0
        val canGoForward: Boolean get() = historyIndex < navigationHistory.size - 1
        val hasActiveOperations: Boolean get() = activeOperations.isNotEmpty()
        val hasPendingConflicts: Boolean get() = pendingConflicts.isNotEmpty()
    }

    init {
        // TODO: Refresh directory based on hints
        fileSystemHinter.events
            .onEach { event -> log(tag, INFO) { "Received operation hint: $event" } }
            .launchIn(scope)

        // Set up navigation flow processing
        navigationRequests
            .onEach { log(tag, INFO) { "New navigation request: $it" } }
            .flatMapLatest { request ->
                flow<Unit> {
                    try {
                        processNavigationRequest(request)
                        emit(Unit)
                    } catch (e: CancellationException) {
                        log(tag, INFO) { "Navigation cancelled" }
                        current.value = current.value.copy(
                            isLoading = false,
                            isLoadingExtended = false,
                        )
                        throw e  // Re-throw to maintain cancellation semantics
                    } catch (e: Exception) {
                        // Handle all other exceptions here to prevent flow termination
                        log(tag, ERROR) { "Navigation failed: $e" }
                        current.value = current.value.copy(
                            error = e,
                            isLoading = false,
                            isLoadingExtended = false,
                        )
                        emit(Unit)
                    }
                }
            }
            .launchIn(scope)

        // Load initial location
        scope.launch {
            log(tag, INFO) { "Loading initial data... ($arguments)" }
            try {
                current.value = current.value.copy(isLoading = true)

                val startPath = arguments?.startPath
                if (startPath != null) {
                    navigationRequests.emit(ExplorerNavigation.Target.Directory(startPath))
                } else {
                    navigationRequests.emit(ExplorerNavigation.Target.Home)
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to initialize: $e" }
                current.value = current.value.copy(
                    isLoading = false,
                    error = e
                )
            }
        }
    }

    private suspend fun processNavigationRequest(target: ExplorerNavigation) {
        log(tag, INFO) { "Processing navigation target: $target" }

        when (target) {
            is ExplorerNavigation.Target -> {
                loadTarget(target, addToHistory = true)
            }

            is ExplorerNavigation.Back -> {
                val currentHistory = current.value.navigationHistory
                val currentIndex = current.value.historyIndex

                if (currentIndex > 0) {
                    val targetLocation = currentHistory[currentIndex - 1]
                    loadTarget(targetLocation, addToHistory = false)
                    current.value = current.value.copy(historyIndex = currentIndex - 1)
                }
            }
            is ExplorerNavigation.Forward -> {
                val currentHistory = current.value.navigationHistory
                val currentIndex = current.value.historyIndex

                if (currentIndex < currentHistory.size - 1) {
                    val targetLocation = currentHistory[currentIndex + 1]
                    loadTarget(targetLocation, addToHistory = false)
                    current.value = current.value.copy(historyIndex = currentIndex + 1)
                }
            }
            is ExplorerNavigation.Refresh -> {
                current.value.currentTarget?.let { target ->
                    loadTarget(target, addToHistory = false)
                }
            }
            is ExplorerNavigation.Cancel -> {
                // Just reset the loading state, flatMapLatest will have already cancelled the previous operation
                log(tag, INFO) { "Navigation cancel request processed" }
                current.value = current.value.copy(
                    isLoading = false,
                    isLoadingExtended = false,
                )
            }
        }
    }

    fun navigate(request: ExplorerNavigation) {
        log(tag) { "navigate(): $request" }
        scope.launch {
            log(tag) { "navigate(): Launching $request" }
            navigationRequests.emit(request)
            log(tag) { "navigate(): Submitted $request" }
        }
    }

    fun execute(command: ExplorerCommand) {
        log(tag) { "execute(): $command" }
        scope.launch {
            val executable = when (command) {
                is ExplorerCommand.Delete -> deleteOperationFactory.create(
                    workspaceId = id,
                    command = command,
                )
                is ExplorerCommand.Create -> createOperationFactory.create(
                    workspaceId = id,
                    command = command,
                )
                is ExplorerCommand.Copy -> copyOperationFactory.create(
                    workspaceId = id,
                    command = command,
                )
                is ExplorerCommand.Move -> moveOperationFactory.create(
                    workspaceId = id,
                    command = command,
                )
            }
            operationsManager.submit(executable)
            log(tag) { "execute(): Submitted $executable" }
        }
    }

    private suspend fun loadTarget(target: ExplorerNavigation.Target, addToHistory: Boolean) {
        log(tag, INFO) { "loadTarget($target, $addToHistory)" }
        current.value = current.value.copy(
            currentTarget = target,
            isLoading = true,
            error = null
        )
        try {
            browsingEngine.loadLocation(target).collectIndexed { index, location ->
                if (index == 0) {
                    val newHistory = if (addToHistory) {
                        val currentHistory = current.value.navigationHistory
                        val currentIndex = current.value.historyIndex

                        // Remove forward history when navigating to new location
                        val trimmedHistory = currentHistory.take(currentIndex + 1)
                        trimmedHistory + target
                    } else {
                        current.value.navigationHistory
                    }

                    val breadcrumbs = breadcrumbGenerator.getBreadcrumbs(location)
                    log(tag) { "loadTarget(): Generated breadcrumbs: $breadcrumbs" }

                    current.value = current.value.copy(
                        currentLocation = location,
                        currentBreadcrumbs = breadcrumbs,
                        isLoading = false,
                        navigationHistory = newHistory,
                        historyIndex = if (addToHistory) newHistory.size - 1 else current.value.historyIndex
                    )

                    // Track path access for shortcuts (only for new navigations to directories)
                    if (addToHistory && target is ExplorerNavigation.Target.Directory) {
                        pathAccessTracker.trackPathAccess(target.path)
                    }

                    val newTitle = location.toString().toCaString()
                    info.value = info.value.copy(title = newTitle)
                } else {
                    current.value = current.value.copy(
                        currentLocation = location,
                    )
                }
            }
            current.value = current.value.copy(isLoadingExtended = false)
        } catch (e: Exception) {
            log(tag, ERROR) { "loadTarget(): Failed to navigate to $target: $e" }
            current.value = current.value.copy(error = e)
        } finally {
            current.value = current.value.copy(
                isLoading = false,
                isLoadingExtended = false,
            )
        }
    }

    fun resolveConflict(operationId: Operation.Id, resolution: PathActionIssue.Resolution) {
        log(tag, INFO) { "Resolving conflict for operation $operationId: $resolution" }
        scope.launch {
            issueHandler.resolveIssue(operationId, resolution)
        }
    }

    fun cancelOperation(operationId: Operation.Id) {
        log(tag, INFO) { "Cancelling operation: $operationId" }
        scope.launch {
            operationsManager.cancel(operationId)
        }
    }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        scope.cancel()
    }


    @Parcelize
    data class Arguments(
        val startPath: APath? = null,
    ) : Workspace.Arguments {
        override val type: Workspace.Type get() = Workspace.Type.EXPLORER
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id, arguments: Arguments?): ExplorerWorkspace
    }
}