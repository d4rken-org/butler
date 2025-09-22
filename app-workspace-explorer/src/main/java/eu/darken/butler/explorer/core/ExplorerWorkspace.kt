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
import eu.darken.butler.common.files.operations.Issue
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.explorer.core.engine.ExplorerOperation
import eu.darken.butler.explorer.core.operations.OperationResult
import eu.darken.butler.explorer.core.operations.OperationState
import eu.darken.butler.explorer.core.operations.OperationsEngine
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
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
    private val operationsEngineFactory: OperationsEngine.Factory,
    private val breadcrumbGenerator: BreadcrumbGenerator,
    private val pathAccessTracker: PathAccessTracker,
) : Workspace {

    private val tag = logTag("Explorer", "Workspace", id.shortTag)

    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    private val browsingEngine = browsingEngineFactory.create(id)
    private val operationEngine = operationsEngineFactory.create(id)

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
    private val operationRequests = MutableSharedFlow<ExplorerOperation>(replay = 1)


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
        val activeOperations: Map<Operation.Id, OperationState> = emptyMap(),
        val operationHistory: List<OperationResult> = emptyList(),
        val pendingConflicts: Map<Operation.Id, OperationState.AwaitingInput> = emptyMap(),
    ) {
        val canGoBack: Boolean get() = historyIndex > 0
        val canGoForward: Boolean get() = historyIndex < navigationHistory.size - 1
        val hasActiveOperations: Boolean get() = activeOperations.isNotEmpty()
        val hasPendingConflicts: Boolean get() = pendingConflicts.isNotEmpty()
    }

    init {
        // TODO: Refresh directory based on hints
        operationEngine.hints
            .onEach { hint -> log(tag, INFO) { "Received operation hint: $hint" } }
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

        // Set up operation flow processing
        operationRequests
            .onEach { log(tag, INFO) { "New operation request: $it" } }
            .onEach { operation ->
                scope.launch {
                    operationEngine.execute(
                        operation = operation,
                        scope = scope,
                    ).collect { state ->
                        // Update active operations map
                        current.value = current.value.copy(
                            activeOperations = if (state is OperationState.Completed) {
                                current.value.activeOperations - operation.operationId
                            } else {
                                current.value.activeOperations + (operation.operationId to state)
                            },
                            pendingConflicts = if (state is OperationState.AwaitingInput) {
                                current.value.pendingConflicts + (operation.operationId to state)
                            } else {
                                current.value.pendingConflicts - operation.operationId
                            },
                        )

                        // Handle completed operations
                        if (state is OperationState.Completed) {
                            // Add to history
                            current.value = current.value.copy(
                                operationHistory = current.value.operationHistory + state.result
                            )

                            // Refresh on success
                            if (state.result is OperationResult.Success) {
                                log(tag, INFO) { "Operation successful: ${state.result}" }
                                current.value.currentTarget?.let {
                                    processNavigationRequest(ExplorerNavigation.Refresh)
                                }
                            } else if (state.result is OperationResult.Failure) {
                                log(tag, ERROR) { "Operation failed: ${state.result}" }
                                current.value = current.value.copy(
                                    error = state.result.exception
                                )
                            }
                        }
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

    fun execute(operation: ExplorerOperation) {
        log(tag) { "execute(): $operation" }
        scope.launch {
            log(tag) { "execute(): Launching $operation" }
            operationRequests.emit(operation)
            log(tag) { "execute(): Submitted $operation" }
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

    fun resolveConflict(operationId: Operation.Id, resolution: Issue.Resolution) {
        log(tag, INFO) { "Resolving conflict for operation $operationId: $resolution" }
        scope.launch {
            operationEngine.resolveConflict(operationId, resolution)
        }
    }

    fun cancelOperation(operationId: Operation.Id) {
        log(tag, INFO) { "Cancelling operation: $operationId" }
        operationEngine.cancelOperation(operationId)
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