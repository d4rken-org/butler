package eu.darken.butler.explorer.core

import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.explorer.core.operations.CompressOperation
import eu.darken.butler.explorer.core.operations.CopyOperation
import eu.darken.butler.explorer.core.operations.CreateOperation
import eu.darken.butler.explorer.core.operations.CreateTextFileOperation
import eu.darken.butler.explorer.core.operations.DeleteOperation
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.operations.ExplorerOperation
import eu.darken.butler.explorer.core.operations.ExtractOperation
import eu.darken.butler.explorer.core.operations.MoveOperation
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.awaitCompletion
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.submitAndGet
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import eu.darken.butler.workspace.core.stateInWorkspace
import eu.darken.butler.workspace.core.tracker.PathAccessTracker
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer


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
    private val createTextFileOperationFactory: CreateTextFileOperation.Factory,
    private val copyOperationFactory: CopyOperation.Factory,
    private val moveOperationFactory: MoveOperation.Factory,
    private val compressOperationFactory: CompressOperation.Factory,
    private val extractOperationFactory: ExtractOperation.Factory,
    private val explorerSettings: ExplorerSettings,
) : Workspace<ExplorerArguments> {

    private val tag = logTag("Explorer", "Workspace", id.shortTag)

    override val type: Workspace.Type = Workspace.Type.EXPLORER

    private val scope = CoroutineScope(
        dispatcherProvider.IO + CoroutineName(tag) + CoroutineExceptionHandler { _, throwable ->
            log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
            _state.value = State.Error(throwable)
        }
    )

    private val _state = MutableStateFlow<State>(State.Initializing)
    val state: Flow<State> = _state.asStateFlow()


    override suspend fun createArguments(): ExplorerArguments {
        // Extract current path from state for session restoration
        val currentState = _state.value as? State.Ready
        val currentPath = (currentState?.currentLocation as? ExplorerLocation.Directory)?.path
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


    override val info: StateFlow<Workspace.Info> = combine(
        _state,
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
        val readyState = state as? State.Ready
        Workspace.Info(
            id = id,
            type = type,
            title = when {
                readyState?.currentTarget != null -> readyState.currentTarget.label
                Bugs.isDebug -> "Explorer ${id.shortTag}".toCaString()
                else -> R.string.explorer_title.toCaString()
            },
            subtitle = readyState?.currentTarget?.description,
            lifecycleState = when (state) {
                is State.Initializing -> Workspace.LifecycleState.Initializing
                is State.Error -> Workspace.LifecycleState.Error(state.error)
                is State.Ready -> Workspace.LifecycleState.Ready
            },
            operationCount = activeOperations,
            attentionCount = attentionCount,
            callerWorkspaceId = pickerConfig?.callerWorkspaceId,
            modalPresentation = (creationArguments as? Workspace.ArgumentsWithCaller)?.modalPresentation
                ?: Workspace.ModalPresentationMode.PANE_LOCAL,
        )
    }.stateInWorkspace(
        scope = scope,
        initial = initialInfo(
            title = R.string.explorer_title.toCaString(),
            arguments = creationArguments,
        ),
    )


    private val navigationRequests = MutableSharedFlow<ExplorerNavigation>(replay = 1)

    sealed interface State {
        data object Initializing : State

        data class Ready(
            val historyIndex: Int = 0,
            val navigationHistory: List<ExplorerNavigation.Target> = emptyList(),
            val currentTarget: ExplorerNavigation.Target? = null,
            val currentLocation: ExplorerLocation? = null,
            val currentBreadcrumbs: List<ExplorerBreadcrumb>? = null,
            val error: Throwable? = null,
        ) : State {
            val canGoBack: Boolean get() = historyIndex > 0
            val canGoForward: Boolean get() = historyIndex < navigationHistory.size - 1
        }

        data class Error(val error: Throwable) : State
    }

    private inline fun updateReady(block: State.Ready.() -> State.Ready) {
        _state.update {
            when (it) {
                is State.Initializing -> it
                is State.Ready -> it.block()
                is State.Error -> it
            }
        }
    }

    init {
        browsingEngine.location
            .onEach { engineState ->
                updateReady {
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
                updateReady { copy(error = null) }
                try {
                    processNavigationRequest(request)
                } catch (e: Exception) {
                    when (e) {
                        is CancellationException -> {
                            log(tag, INFO) { "Navigation cancelled" }
                            updateReady { copy(currentLocation = null) }
                            throw e
                        }

                        else -> {
                            log(tag, ERROR) { "Navigation failed: $e" }
                            updateReady { copy(currentLocation = null, error = e) }
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
            // Transition to Ready state before processing navigation
            _state.value = State.Ready()
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
                        val defaultLocation = explorerSettings.defaultStartLocation.value()
                        log(tag, INFO) { "Using default start location from settings: $defaultLocation" }
                        when (defaultLocation) {
                            is DefaultStartLocation.Device -> navigationRequests.emit(ExplorerNavigation.Target.Device)
                            is DefaultStartLocation.Directory -> navigationRequests.emit(
                                ExplorerNavigation.Target.Directory(
                                    defaultLocation.path
                                )
                            )
                            is DefaultStartLocation.Home, null -> navigationRequests.emit(ExplorerNavigation.Target.Home)
                        }
                    }
                }
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to initialize: ${e.asLog()}" }
                Bugs.report(e)
                _state.value = State.Error(e)
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
                val readyState = _state.value as? State.Ready ?: return
                if (readyState.historyIndex > 0) {
                    val targetLocation = readyState.navigationHistory[readyState.historyIndex - 1]
                    loadTarget(targetLocation, addToHistory = false)
                    updateReady { copy(historyIndex = historyIndex - 1) }
                }
            }

            is ExplorerNavigation.Forward -> {
                val readyState = _state.value as? State.Ready ?: return
                if (readyState.historyIndex < readyState.navigationHistory.size - 1) {
                    val targetLocation = readyState.navigationHistory[readyState.historyIndex + 1]
                    loadTarget(targetLocation, addToHistory = false)
                    updateReady { copy(historyIndex = historyIndex + 1) }
                }
            }

            is ExplorerNavigation.Refresh -> {
                log(tag, INFO) { "Refreshing current location" }
                browsingEngine.refresh()
            }

            is ExplorerNavigation.Cancel -> {
                log(tag, INFO) { "Navigation cancel request processed" }
                updateReady { copy(currentLocation = null, error = null) }
            }
        }
    }

    private suspend fun loadTarget(target: ExplorerNavigation.Target, addToHistory: Boolean) {
        log(tag, INFO) { "loadTarget($target, $addToHistory)" }

        updateReady { copy(currentTarget = target) }

        if (addToHistory) {
            log(tag) { "loadTarget(): Updating history" }

            updateReady {
                log(tag) { "loadTarget(): Old history: index=$historyIndex history=$navigationHistory" }
                // Remove forward history when navigating to new location
                val trimmedHistory = navigationHistory.take(historyIndex + 1)
                val newHistory = trimmedHistory + target
                copy(
                    navigationHistory = newHistory,
                    historyIndex = newHistory.size - 1
                ).also {
                    log(tag) { "loadTarget(): New history: index=${it.historyIndex} history=${it.navigationHistory}" }
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

    suspend fun execute(command: ExplorerCommand): Operation.State.Completed {
        log(tag) { "execute(): $command" }
        val executable = createOperation(command)
        val managed = operationsManager.submitAndGet(executable)
        log(tag) { "execute(): Submitted ${managed.id}, awaiting completion" }
        val completed = managed.awaitCompletion()
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
        is ExplorerCommand.CreateTextFile -> createTextFileOperationFactory.create(
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
        is ExplorerCommand.Compress -> compressOperationFactory.create(
            workspaceId = id,
            command = command,
        )
        is ExplorerCommand.Extract -> extractOperationFactory.create(
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

        override val argumentsSerializer: KSerializer<ExplorerArguments> get() = serializer()
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.EXPLORER)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}
