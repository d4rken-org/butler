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
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.common.flow.shareLatest
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.filesystem.FileSystemHinter
import eu.darken.butler.explorer.core.operations.CopyOperation
import eu.darken.butler.explorer.core.operations.CreateOperation
import eu.darken.butler.explorer.core.operations.DeleteOperation
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.operations.MoveOperation
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import eu.darken.butler.workspace.core.operations.withStateUpdates
import eu.darken.butler.workspace.core.preview.ExplorerPreviewData
import eu.darken.butler.workspace.core.tracker.PathAccessTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize


class ExplorerWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: Arguments?,
    dispatcherProvider: DispatcherProvider,
    private val browsingEngineFactory: BrowsingEngine.Factory,
    private val breadcrumbGenerator: BreadcrumbGenerator,
    private val fileSystemHinter: FileSystemHinter,
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

    private val _state = DynamicStateFlow<State>(parentScope = scope) {
        State()
    }
    val state: Flow<State> = _state.flow

    data class OperationsState(
        val operations: Collection<ManagedOperation> = emptySet(),
        val states: Map<Operation.Id, Operation.State> = emptyMap(),
        val pendingConflicts: Map<Operation.Id, Issue>,
    )

    val operations: Flow<OperationsState> = operationsManager.operationsForWorkspace(id)
        .withStateUpdates()
        .map { operations ->
            OperationsState(
                operations = operations,
                states = operations.associate { it.id to it.state.value },
                pendingConflicts = operations.map { it to it.state.value }
                    .filter { it.second is Operation.State.Waiting }
                    .associate {
                        val waitingState = it.second as Operation.State.Waiting
                        it.first.id to waitingState.issue
                    },
            )
        }
        .shareLatest(scope)

    override val info: Flow<Workspace.Info> = combine(
        _state.flow,
        operationsManager.operationsForWorkspace(id).withOnlyStateChanges()
    ) { state, operations ->
        val states = operations.map { it.id to it.state.value }
        val activeOperations: Int = states.count { it.second !is Operation.State.Completed }
        val attentionCount: Int = states.count {
            val value = it.second
            if (value is Operation.State.Waiting) return@count true
            if (value is Operation.State.Completed && value.error != null) return@count true
            return@count false
        }
        Workspace.Info(
            id = id,
            type = type,
            title = when {
                state.currentTarget != null -> state.currentTarget.label
                Bugs.isDebug -> "Explorer ${id.shortTag}".toCaString()
                else -> R.string.explorer_title.toCaString()
            },
            previewData = ExplorerPreviewData(),
            operationCount = activeOperations,
            attentionCount = attentionCount,
        )
    }


    private val navigationRequests = MutableSharedFlow<ExplorerNavigation>(replay = 1)

    data class State(
        val currentTarget: ExplorerNavigation.Target? = null,
        val historyIndex: Int = 0,
        val navigationHistory: List<ExplorerNavigation.Target> = emptyList(),
        val currentBreadcrumbs: List<ExplorerBreadcrumb>? = null,
        val currentLocation: ExplorerLocation? = null,
        val error: Throwable? = null,
    ) {
        val canGoBack: Boolean get() = historyIndex > 0
        val canGoForward: Boolean get() = historyIndex < navigationHistory.size - 1
    }

    init {
        // Load initial location
        scope.launch {
            log(tag, INFO) { "Loading initial data... ($arguments)" }
            try {
                val startPath = arguments?.startPath
                if (startPath != null) {
                    navigationRequests.emit(ExplorerNavigation.Target.Directory(startPath))
                } else {
                    navigationRequests.emit(ExplorerNavigation.Target.Home)
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to initialize: $e" }
                Bugs.report(e)
            }
        }

        navigationRequests
            .onEach { log(tag, INFO) { "New navigation request: $it" } }
            .flatMapLatest { request ->
                flow<Unit> {
                    _state.updateBlocking {
                        copy(error = null, currentLocation = null)
                    }
                    try {
                        processNavigationRequest(request)
                        emit(Unit)
                    } catch (e: CancellationException) {
                        log(tag, INFO) { "Navigation cancelled" }
                        throw e  // Re-throw to maintain cancellation semantics
                    } catch (e: Exception) {
                        // Handle all other exceptions here to prevent flow termination
                        log(tag, ERROR) { "Navigation failed: $e" }
                        _state.updateBlocking {
                            copy(error = e)
                        }
                        emit(Unit)
                    }
                }
            }
            .launchIn(scope)

        fileSystemHinter.events
            .onEach { event -> browsingEngine.hint(event) }
            .launchIn(scope)
    }

    private suspend fun processNavigationRequest(request: ExplorerNavigation) {
        log(tag, INFO) { "Processing navigation request: $request" }
        when (request) {
            is ExplorerNavigation.Target -> {
                loadTarget(request, addToHistory = true)
            }

            is ExplorerNavigation.Back -> {
                val currentHistory = _state.value().navigationHistory
                val currentIndex = _state.value().historyIndex

                if (currentIndex > 0) {
                    val targetLocation = currentHistory[currentIndex - 1]
                    loadTarget(targetLocation, addToHistory = false)
                    _state.updateBlocking {
                        copy(historyIndex = currentIndex - 1)
                    }
                }
            }
            is ExplorerNavigation.Forward -> {
                val currentHistory = _state.value().navigationHistory
                val currentIndex = _state.value().historyIndex

                if (currentIndex < currentHistory.size - 1) {
                    val targetLocation = currentHistory[currentIndex + 1]
                    loadTarget(targetLocation, addToHistory = false)
                    _state.updateBlocking {
                        copy(historyIndex = currentIndex + 1)
                    }
                }
            }
            is ExplorerNavigation.Refresh -> {
                _state.value().currentTarget?.let { target ->
                    loadTarget(target, addToHistory = false)
                }
            }
            is ExplorerNavigation.Cancel -> {
                // Just reset the loading state, flatMapLatest will have already cancelled the previous operation
                log(tag, INFO) { "Navigation cancel request processed" }
            }
        }
    }

    private suspend fun loadTarget(target: ExplorerNavigation.Target, addToHistory: Boolean) {
        log(tag, INFO) { "loadTarget($target, $addToHistory)" }
        browsingEngine.loadLocation(target).collectIndexed { index, state ->
            if (index == 0) {
                val newHistory = if (addToHistory) {
                    val currentHistory = _state.value().navigationHistory
                    val currentIndex = _state.value().historyIndex

                    // Remove forward history when navigating to new location
                    val trimmedHistory = currentHistory.take(currentIndex + 1)
                    trimmedHistory + target
                } else {
                    _state.value().navigationHistory
                }

                _state.updateBlocking {
                    copy(
                        navigationHistory = newHistory,
                        historyIndex = if (addToHistory) newHistory.size - 1 else historyIndex
                    )
                }

                // Track path access for shortcuts (only for new navigations to directories)
                if (addToHistory && target is ExplorerNavigation.Target.Directory) {
                    pathAccessTracker.trackPathAccess(target.path)
                }
            }

            val breadcrumbs = breadcrumbGenerator.getBreadcrumbs(state)
            log(tag) { "loadTarget(): Generated breadcrumbs: $breadcrumbs" }

            _state.updateBlocking {
                copy(
                    currentBreadcrumbs = breadcrumbs,
                    currentLocation = state,
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