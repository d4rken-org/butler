package eu.darken.butler.searcher.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import android.os.Environment
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.searcher.core.operations.DeleteOperation
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import eu.darken.butler.workspace.core.operations.withStateUpdates
import eu.darken.butler.workspace.core.permissions.PathPermissionCheck
import eu.darken.butler.workspace.core.permissions.WorkspaceRequirements
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize


class SearcherWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: Arguments?,
    dispatcherProvider: DispatcherProvider,
    private val operationsManager: OperationsManager,
    private val deleteOperationFactory: DeleteOperation.Factory,
    private val searchEngine: SearchEngine,
    private val searcherSettings: SearcherSettings,
    private val storageManager2: StorageManager2,
    private val pathPermissionCheck: PathPermissionCheck,
) : Workspace {

    private val tag = logTag( "Searcher","Workspace", id.shortTag)
    private val scope = CoroutineScope(
        dispatcherProvider.IO +
            CoroutineName(tag) +
            CoroutineExceptionHandler { _, throwable ->
                log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
                // TODO: Add error state to workspace if needed
            }
    )

    override val type: Workspace.Type = Workspace.Type.SEARCHER

    override val info: MutableStateFlow<Workspace.Info> = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = "Searcher ${id.shortTag}".toCaString(),
        )
    )

    data class State(
        val currentSearchQuery: SearchQuery? = null,
        val searchStatus: SearchStatus = SearchStatus.IDLE,
        val results: List<SearchItem> = emptyList(),
        val progress: SearchProgress? = null,
        val error: Exception? = null,
        val searchTargets: List<SearchTarget> = emptyList(),
        val setupRequirements: WorkspaceRequirements = WorkspaceRequirements(),
    ) {
        enum class SearchStatus {
            IDLE, SEARCHING, COMPLETED, ERROR, CANCELLED
        }

        data class SearchProgress(
            val currentPath: APath<*>,
            val itemsScanned: Int,
            val resultsFound: Int,
        )
    }

    private val _state = MutableStateFlow(State())
    val state: Flow<State> = _state

    private val searchRequests = MutableSharedFlow<SearcherCommand.Search>(replay = 1)
    private var activeSearchJob: Job? = null

    fun search(command: SearcherCommand.Search) {
        log(tag) { "search(): $command" }
        scope.launch {
            searchRequests.emit(command)
        }
    }

    data class OperationsState(
        val operations: Collection<ManagedOperation> = emptySet(),
        val pendingConflicts: Map<Operation.Id, Issue> = emptyMap(),
    )

    val operations: Flow<OperationsState> = operationsManager.operationsForWorkspace(id)
        .withStateUpdates()
        .map { ops -> OperationsState(operations = ops) }

    init {
        log(tag, INFO) { "Initialized" }

        // Initialize search targets from arguments, settings, or defaults
        scope.launch {
            val initialTargets = when {
                arguments?.startTargets != null -> {
                    log(tag, INFO) { "Using targets from arguments: ${arguments.startTargets}" }
                    arguments.startTargets!!
                }
                else -> {
                    val savedTargets = searcherSettings.defaultSearchTargets.value()
                    if (savedTargets != null) {
                        log(tag, INFO) { "Loaded ${savedTargets.size} targets from settings" }
                        savedTargets
                    } else {
                        log(tag, INFO) { "No saved targets, using defaults" }
                        getDefaultSearchPaths()
                    }
                }
            }
            _state.update { it.copy(searchTargets = initialTargets) }
        }

        // Track operation counts for this workspace
        operationsManager.operationsForWorkspace(id).withOnlyStateChanges()
            .onEach { operations ->
                var operationCount = 0
                var attentionCount = 0

                operations.forEach { operation ->
                    when (val state = operation.state.value) {
                        is Operation.State.Queued -> operationCount++
                        is Operation.State.Active -> operationCount++
                        is Operation.State.Waiting -> {
                            operationCount++
                            attentionCount++
                        }
                        is Operation.State.Completed -> {
                            if (state.error != null && state.error !is CancellationException) {
                                attentionCount++
                            }
                        }
                    }
                }

                info.value = info.value.copy(
                    operationCount = operationCount,
                    attentionCount = attentionCount
                )
                log(tag, VERBOSE) { "Updated operation counts: active=$operationCount, attention=$attentionCount" }
            }
            .launchIn(scope)

        // Process search requests
        searchRequests
            .onEach { command ->
                log(tag, INFO) { "Processing search request: ${command.query}" }
                processSearchRequest(command)
            }
            .launchIn(scope)
    }

    private fun processSearchRequest(command: SearcherCommand.Search) {
        log(tag) { "processSearchRequest(): ${command.query}" }

        // Cancel any active search
        activeSearchJob?.cancel()

        // Validate query
        if (command.query.isBlank()) {
            log(tag, WARN) { "Skipping search with blank query" }
            return
        }

        // Validate targets
        if (command.targets.isEmpty()) {
            log(tag, ERROR) { "Cannot start search: No search targets" }
            _state.update {
                it.copy(
                    searchStatus = State.SearchStatus.ERROR,
                    error = IllegalArgumentException("No search targets specified"),
                )
            }
            return
        }

        // Check permissions for enabled paths
        val enabledPaths = command.targets
            .filterIsInstance<SearchTarget.Path>()
            .filter { it.enabled }
            .map { it.path }

        // Launch coroutine to check permissions
        activeSearchJob = scope.launch {
            try {
                // Get permission requirements for all enabled paths
                val requirementsList = enabledPaths.map { path ->
                    pathPermissionCheck.monitor(path).first()
                }

                // Aggregate requirements
                val setupRequirements = WorkspaceRequirements(
                    combos = requirementsList.flatMap { it.combos }.distinct().toSet(),
                    complete = requirementsList.flatMap { it.complete }.distinct().toSet(),
                )

                // Update state with permission requirements
                _state.update { it.copy(setupRequirements = setupRequirements) }

                // Check if setup is needed
                if (setupRequirements.needsSetup) {
                    log(tag, WARN) { "Cannot start search: Setup required - $setupRequirements" }
                    _state.update {
                        it.copy(
                            searchStatus = State.SearchStatus.ERROR,
                            error = IllegalStateException("Insufficient permissions for search targets"),
                        )
                    }
                    return@launch
                }

                // Permission check passed, proceed with search
                performSearch(command)
            } catch (e: CancellationException) {
                log(tag, INFO) { "Permission check cancelled" }
                throw e
            } catch (e: Exception) {
                log(tag, ERROR) { "Permission check failed: ${e.asLog()}" }
                _state.update {
                    it.copy(
                        searchStatus = State.SearchStatus.ERROR,
                        error = e,
                    )
                }
            }
        }
    }

    private suspend fun performSearch(command: SearcherCommand.Search) {
        // Build search query
        val searchQuery = SearchQuery(
            query = command.query,
            targets = command.targets,
            filter = command.filter,
            options = command.options,
        )

        // Set initial progress with first target
        val initialProgress = (command.targets.firstOrNull() as? SearchTarget.Path)?.let { firstTarget ->
            State.SearchProgress(
                currentPath = firstTarget.path,
                itemsScanned = 0,
                resultsFound = 0,
            )
        }

        // Clear previous results and enter SEARCHING state
        _state.update {
            it.copy(
                currentSearchQuery = searchQuery,
                searchStatus = State.SearchStatus.SEARCHING,
                results = emptyList(),
                progress = initialProgress,
                error = null,
            )
        }

        // Execute search (already in coroutine context from processSearchRequest)
        try {
            val results = mutableListOf<SearchItem>()
            searchEngine.search(
                searchQuery = searchQuery,
                onProgress = { engineProgress ->
                    _state.update { state ->
                        state.copy(
                            progress = State.SearchProgress(
                                currentPath = engineProgress.currentPath,
                                itemsScanned = engineProgress.itemsScanned,
                                resultsFound = engineProgress.resultsFound,
                            )
                        )
                    }
                }
            ).collect { result ->
                results.add(result)
                _state.update { state ->
                    state.copy(results = state.results + result)
                }
            }

            log(tag, INFO) { "Search completed: ${results.size} results" }
            _state.update { it.copy(searchStatus = State.SearchStatus.COMPLETED) }
        } catch (e: CancellationException) {
            log(tag, INFO) { "Search cancelled" }
            _state.update { it.copy(searchStatus = State.SearchStatus.CANCELLED) }
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "Search failed: ${e.asLog()}" }
            _state.update {
                it.copy(
                    searchStatus = State.SearchStatus.ERROR,
                    error = e,
                )
            }
        }
    }

    fun execute(command: SearcherCommand) {
        log(tag) { "execute(): $command" }
        when (command) {
            is SearcherCommand.Search -> {
                search(command)
            }
            is SearcherCommand.Cancel -> {
                log(tag, INFO) { "Cancelling active search" }
                // Cancel the active search job
                // The job's catch block will update state to CANCELLED
                activeSearchJob?.cancel()
            }
            is SearcherCommand.Clear -> {
                log(tag, INFO) { "Clearing search state" }
                // Cancel any active search
                activeSearchJob?.cancel()
                // Reset state to initial empty state
                _state.value = State()
            }
            is SearcherCommand.Delete -> {
                scope.launch {
                    val executable = deleteOperationFactory.create(
                        workspaceId = id,
                        command = command,
                    )
                    operationsManager.submit(executable)
                }
            }

            // Target management
            is SearcherCommand.AddDefaultPaths -> addDefaultPaths()
        }
    }

    fun updateTargets(transform: (List<SearchTarget>) -> List<SearchTarget>) {
        val newTargets = transform(_state.value.searchTargets)
        log(tag, INFO) { "Updating search targets: ${newTargets.size} targets" }
        _state.update { it.copy(searchTargets = newTargets) }
        scope.launch {
            searcherSettings.defaultSearchTargets.value(newTargets)
        }
    }

    fun addDefaultPaths() {
        log(tag, INFO) { "Adding default search paths" }
        val defaultPaths = getDefaultSearchPaths()
        updateTargets { defaultPaths }
    }

    private fun getDefaultSearchPaths(): List<SearchTarget> {
        log(tag, INFO) { "Getting default search paths (all public storage volumes)" }

        // Get all mounted storage volumes
        val volumes = storageManager2.storageVolumes
            .filter { it.isMounted }
            .mapNotNull { volume ->
                volume.directory?.let { LocalPath.build(it) }
                    ?: volume.path?.let { LocalPath.build(it) }
            }

        log(tag, INFO) { "Found ${volumes.size} public storage volumes: ${volumes.map { it.path }}" }

        if (volumes.isEmpty()) {
            log(tag, WARN) { "No mounted storage volumes found, falling back to external storage" }
            val fallbackPath = LocalPath.build(Environment.getExternalStorageDirectory())
            return listOf(SearchTarget.Path.from(fallbackPath))
        }

        // Convert to SearchTargets
        return volumes.map { SearchTarget.Path.from(it) }
    }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        scope.cancel()
    }

    @Parcelize
    data class Arguments(
        val startTargets: List<SearchTarget>? = null,
    ) : Workspace.Arguments {
        override val type: Workspace.Type
            get() = Workspace.Type.SEARCHER
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id, arguments: Arguments?): SearcherWorkspace
    }
}