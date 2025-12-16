package eu.darken.butler.explorer.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.ERROR
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.flow.DynamicStateFlow
import eu.darken.butler.common.flow.shareLatest
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.filesystem.FileSystemHinter
import eu.darken.butler.explorer.core.operations.CopyOperation
import eu.darken.butler.explorer.core.operations.CreateOperation
import eu.darken.butler.explorer.core.operations.DeleteOperation
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.operations.ExplorerOperation
import eu.darken.butler.explorer.core.operations.MoveOperation
import eu.darken.butler.explorer.core.picker.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.awaitCompletion
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.submitAndGet
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import eu.darken.butler.workspace.core.operations.withStateUpdates
import eu.darken.butler.workspace.core.tracker.PathAccessTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement


class ExplorerWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: ExplorerArguments,
    dispatcherProvider: DispatcherProvider,
    browsingEngineFactory: BrowsingEngine.Factory,
    fileSystemHinter: FileSystemHinter,
    private val pathAccessTracker: PathAccessTracker,
    private val issueHandler: IssueHandler,
    private val operationsManager: OperationsManager,
    private val deleteOperationFactory: DeleteOperation.Factory,
    private val createOperationFactory: CreateOperation.Factory,
    private val copyOperationFactory: CopyOperation.Factory,
    private val moveOperationFactory: MoveOperation.Factory,
    private val explorerSettings: ExplorerSettings,
) : Workspace<ExplorerArguments> {

    private val tag = logTag("Explorer", "Workspace", id.shortTag)

    override val type: Workspace.Type = Workspace.Type.EXPLORER

    private val scope = CoroutineScope(
        dispatcherProvider.IO +
                CoroutineName(tag) +
                CoroutineExceptionHandler { _, throwable ->
                    log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
                    _state.updateAsync { copy(error = throwable) }
                }
    )

    private val _state: DynamicStateFlow<State> = DynamicStateFlow<State>(parentScope = scope) { State() }
    val state: Flow<State> = _state.flow


    override suspend fun createArguments(): ExplorerArguments {
        // Extract current path from state for session restoration
        val currentState = _state.value()
        val currentPath = (currentState.currentLocation as? ExplorerLocation.Directory)?.path
        return ExplorerArguments.Default(startPath = currentPath)
    }

    private val browsingEngine = browsingEngineFactory.create(id, scope)

    // Picker configuration if this is a picker workspace
    val pickerConfig: PickerConfig? = (creationArguments as? ExplorerArguments.Picker)?.let {
        PickerConfig(
            callerWorkspaceId = it.callerWorkspaceId ?: error("callerWorkspaceId required for picker mode"),
            selection = it.selection,
            requireWritable = it.requireWritable,
        )
    }

    // SaveAs filename state (only used when pickerConfig.selection is SaveAs)
    private val _saveAsFilename = MutableStateFlow(
        (pickerConfig?.selection as? PickerConfig.Selection.SaveAs)?.suggestedFilename ?: ""
    )
    val saveAsFilename: StateFlow<String> = _saveAsFilename.asStateFlow()

    fun updateSaveAsFilename(filename: String) {
        log(tag) { "updateSaveAsFilename($filename)" }
        _saveAsFilename.value = filename
    }


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
            if (value is Operation.State.Completed && value.error != null && value.error !is CancellationException) return@count true
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
            subtitle = state.currentTarget?.description,
            operationCount = activeOperations,
            attentionCount = attentionCount,
            callerWorkspaceId = pickerConfig?.callerWorkspaceId,
        )
    }


    private val navigationRequests = MutableSharedFlow<ExplorerNavigation>(replay = 1)

    data class State(
        val historyIndex: Int = 0,
        val navigationHistory: List<ExplorerNavigation.Target> = emptyList(),
        val currentTarget: ExplorerNavigation.Target? = null,
        val currentLocation: ExplorerLocation? = null,
        val currentBreadcrumbs: List<ExplorerBreadcrumb>? = null,
        val error: Throwable? = null,
    ) {
        val canGoBack: Boolean get() = historyIndex > 0
        val canGoForward: Boolean get() = historyIndex < navigationHistory.size - 1
    }

    init {
        browsingEngine.location
            .onEach { engineState ->
                _state.updateBlocking {
                    copy(
                        currentLocation = engineState.location,
                        currentBreadcrumbs = engineState.breadcrumbs ?: currentBreadcrumbs,
                        error = engineState.error
                    )
                }
            }
            .launchIn(scope)

        // Process navigation requests
        navigationRequests
            .onEach { request ->
                log(tag, INFO) { "New navigation request: $request" }
                _state.updateBlocking { copy(error = null) }
                try {
                    processNavigationRequest(request)
                } catch (e: Exception) {
                    when (e) {
                        is CancellationException -> {
                            log(tag, INFO) { "Navigation cancelled" }
                            _state.updateBlocking { copy(currentLocation = null) }
                            throw e
                        }

                        else -> {
                            log(tag, ERROR) { "Navigation failed: $e" }
                            _state.updateBlocking {
                                copy(
                                    currentLocation = null,
                                    error = e,
                                )
                            }
                        }
                    }
                }
            }
            .launchIn(scope)

        // Forward filesystem hints to browsing engine
        fileSystemHinter.events
            .onEach { event -> browsingEngine.hint(event) }
            .launchIn(scope)

        // Load initial location
        scope.launch {
            log(tag, INFO) { "Loading initial data... ($creationArguments)" }
            try {
                val startPath = when (creationArguments) {
                    is ExplorerArguments.Picker -> creationArguments.startPath
                    is ExplorerArguments.Default -> creationArguments.startPath
                }
                when {
                    startPath != null -> {
                        navigationRequests.emit(ExplorerNavigation.Target.Directory(startPath))
                    }
                    else -> {
                        val defaultPath = explorerSettings.defaultStartPath.value()
                        if (defaultPath != null) {
                            log(tag, INFO) { "Using default start path from settings: $defaultPath" }
                            navigationRequests.emit(ExplorerNavigation.Target.Directory(defaultPath))
                        } else {
                            navigationRequests.emit(ExplorerNavigation.Target.Home)
                        }
                    }
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to initialize: $e" }
                Bugs.report(e)
            }
        }
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
                log(tag, INFO) { "Refreshing current location" }
                browsingEngine.refresh()
            }

            is ExplorerNavigation.Cancel -> {
                log(tag, INFO) { "Navigation cancel request processed" }
                _state.updateBlocking {
                    copy(
                        currentLocation = null,
                        error = null
                    )
                }
            }
        }
    }

    private suspend fun loadTarget(target: ExplorerNavigation.Target, addToHistory: Boolean) {
        log(tag, INFO) { "loadTarget($target, $addToHistory)" }

        _state.updateBlocking {
            copy(currentTarget = target)
        }

        if (addToHistory) {
            log(tag) { "loadTarget(): Updating history" }

            val currentHistory = _state.value().navigationHistory
            val currentIndex = _state.value().historyIndex

            // Remove forward history when navigating to new location
            val trimmedHistory = currentHistory.take(currentIndex + 1)
            val newHistory = trimmedHistory + target

            _state.updateBlocking {
                log(tag) { "loadTarget(): Old history: index=$historyIndex history=$navigationHistory" }
                copy(
                    navigationHistory = newHistory,
                    historyIndex = newHistory.size - 1
                ).apply {
                    log(tag) { "loadTarget(): New history: index=$historyIndex history=$navigationHistory" }
                }
            }

            // Track path access for shortcuts (only for new navigations to directories)
            if (target is ExplorerNavigation.Target.Directory) {
                pathAccessTracker.trackPathAccess(target.path)
            }
        }

        // Trigger load in browsing engine
        browsingEngine.setTarget(target)
    }

    fun navigate(request: ExplorerNavigation) {
        log(tag, INFO) { "navigate(): $request" }
        scope.launch {
            navigationRequests.emit(request)
        }
    }

    suspend fun execute(command: ExplorerCommand): ExplorerOperation.State.Completed {
        log(tag) { "execute(): $command" }
        val executable = createOperation(command)
        val managed = operationsManager.submitAndGet(executable)
        log(tag) { "execute(): Submitted ${managed.id}, awaiting completion" }
        val completed = managed.awaitCompletion() as ExplorerOperation.State.Completed
        log(tag) { "execute(): ${managed.id} completed" }
        return completed
    }

    private fun createOperation(command: ExplorerCommand): ExplorerOperation = when (command) {
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
        browsingEngine.release()
        scope.cancel()
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<ExplorerArguments> {

        override fun create(id: Workspace.Id, arguments: ExplorerArguments): ExplorerWorkspace

        override fun serialize(json: Json, arguments: ExplorerArguments): JsonElement {
            return json.encodeToJsonElement<ExplorerArguments>(arguments)
        }

        override fun deserialize(json: Json, element: JsonElement): ExplorerArguments {
            return json.decodeFromJsonElement<ExplorerArguments>(element)
        }
    }
}