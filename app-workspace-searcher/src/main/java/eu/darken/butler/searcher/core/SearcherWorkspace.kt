package eu.darken.butler.searcher.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.issue.Issue
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.arguments.SearcherArguments
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.searcher.core.operations.DeleteOperation
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import eu.darken.butler.workspace.core.operations.withStateUpdates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement


class SearcherWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted val creationArguments: SearcherArguments,
    dispatcherProvider: DispatcherProvider,
    private val issueHandler: IssueHandler,
    private val operationsManager: OperationsManager,
    private val deleteOperationFactory: DeleteOperation.Factory,
    searchEngineFactory: SearchEngine.Factory,
) : Workspace<SearcherArguments> {

    private val tag = logTag("Searcher", "Workspace", id.shortTag)
    private val scope = CoroutineScope(
        dispatcherProvider.IO +
            CoroutineName(tag) +
            CoroutineExceptionHandler { _, throwable ->
                log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
                _searchState.update { state ->
                    state.copy(
                        searchStatus = State.SearchStatus.ERROR,
                        error = throwable as? Exception ?: Exception("Workspace error", throwable),
                    )
                }
            }
    )

    private val searchEngine = searchEngineFactory.create(id, scope)

    override val type: Workspace.Type = Workspace.Type.SEARCHER

    override suspend fun createArguments(): SearcherArguments {
        val currentState = _searchState.value
        val targets = searchEngine.targetState.value
        return SearcherArguments.Default(
            startTargets = targets.ifEmpty { null },
            filenameQuery = currentState.currentSearchQuery?.filenameQuery,
            contentQuery = currentState.currentSearchQuery?.contentQuery,
            startSearch = false,
        )
    }

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
        val searchTargets: List<SearchTarget> = emptyList(), // From engine
        val setupRequirements: PathRequirements = PathRequirements(), // From engine
        val targetProgress: List<SearchEngine.SearchTargetProgress> = emptyList(), // From engine
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

    private val _searchState = MutableStateFlow(State())

    val state: Flow<State> = combine(
        _searchState,
        searchEngine.targetState,
        searchEngine.setupRequirements,
        searchEngine.targetProgressState,
    ) { searchState, targets, requirements, targetProgress ->
        searchState.copy(
            searchTargets = targets,
            setupRequirements = requirements,
            targetProgress = targetProgress,
        )
    }

    private var activeSearchJob: Job? = null

    fun search(command: SearcherCommand.Search) {
        log(tag) { "search(): $command" }

        // Cancel any active search
        activeSearchJob?.cancel()

        // Launch new search
        activeSearchJob = scope.launch {
            processSearchRequest(command)
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

        // Initialize search targets and query from arguments if provided
        scope.launch {
            val args = creationArguments as? SearcherArguments.Default
            if (args?.startTargets != null) {
                log(tag, INFO) { "Using targets from arguments: ${args.startTargets}" }
                searchEngine.updateTargets { args.startTargets!! }
            }

            // Initialize search query from arguments (for restore persistence)
            if (args?.filenameQuery?.isNotEmpty == true || args?.contentQuery?.isNotEmpty == true) {
                log(
                    tag,
                    INFO
                ) { "Restoring query from arguments: filename=${args.filenameQuery?.pattern}, content=${args.contentQuery?.pattern}" }
                _searchState.update { state ->
                    state.copy(
                        currentSearchQuery = SearchQuery(
                            filenameQuery = args.filenameQuery ?: FilenameQuery(),
                            contentQuery = args.contentQuery ?: ContentQuery(),
                            targets = args.startTargets ?: emptyList(),
                        )
                    )
                }
            }
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
    }

    private suspend fun processSearchRequest(command: SearcherCommand.Search) {
        log(tag) { "processSearchRequest(): filename=${command.filenameQuery.pattern}, content=${command.contentQuery.pattern}" }

        // Set initial progress with first target
        val initialProgress = (command.targets.firstOrNull() as? SearchTarget.Path)?.let { firstTarget ->
            State.SearchProgress(
                currentPath = firstTarget.path,
                itemsScanned = 0,
                resultsFound = 0,
            )
        }

        // Build search query for display
        val searchQuery = SearchQuery(
            filenameQuery = command.filenameQuery,
            contentQuery = command.contentQuery,
            targets = command.targets,
            filter = command.filter,
            options = command.options,
        )

        // Clear previous results and enter SEARCHING state
        _searchState.update {
            it.copy(
                currentSearchQuery = searchQuery,
                searchStatus = State.SearchStatus.SEARCHING,
                results = emptyList(),
                progress = initialProgress,
                error = null,
            )
        }

        // Update workspace info with current search (triggers session save + shows in tab)
        val subtitleText = buildString {
            append(command.filenameQuery.pattern)
            if (command.contentQuery.isNotEmpty) {
                append(" | ")
                append(command.contentQuery.pattern)
            }
        }
        info.value = info.value.copy(subtitle = subtitleText.toCaString())

        // Delegate to engine
        when (val result = searchEngine.search(command, onProgress = { engineProgress ->
            _searchState.update { state ->
                state.copy(
                    progress = State.SearchProgress(
                        currentPath = engineProgress.currentPath,
                        itemsScanned = engineProgress.itemsScanned,
                        resultsFound = engineProgress.resultsFound,
                    )
                )
            }
        })) {
            is SearchEngine.Result.InvalidQuery -> {
                log(tag, WARN) { "Search failed: Invalid query" }
                _searchState.update {
                    it.copy(
                        searchStatus = State.SearchStatus.ERROR,
                        error = IllegalArgumentException("Invalid query"),
                    )
                }
            }

            is SearchEngine.Result.NoTargets -> {
                log(tag, ERROR) { "Search failed: No targets" }
                _searchState.update {
                    it.copy(
                        searchStatus = State.SearchStatus.ERROR,
                        error = IllegalArgumentException("No search targets specified"),
                    )
                }
            }

            is SearchEngine.Result.PermissionsRequired -> {
                log(tag, WARN) { "Search failed: Permissions required - ${result.requirements}" }
                _searchState.update {
                    it.copy(
                        searchStatus = State.SearchStatus.ERROR,
                        error = IllegalStateException("Insufficient permissions for search targets"),
                    )
                }
            }

            is SearchEngine.Result.Error -> {
                log(tag, ERROR) { "Search failed: ${result.exception.asLog()}" }
                _searchState.update {
                    it.copy(
                        searchStatus = State.SearchStatus.ERROR,
                        error = result.exception,
                    )
                }
            }

            is SearchEngine.Result.Success -> {
                try {
                    val results = mutableListOf<SearchItem>()
                    result.results.collect { item ->
                        results.add(item)
                        _searchState.update { state ->
                            state.copy(results = state.results + item)
                        }
                    }

                    // Check if all targets failed with errors
                    val targetProgress = searchEngine.targetProgressState.value
                    val allTargetsFailed = targetProgress.isNotEmpty() &&
                        targetProgress.all { it.status == SearchEngine.SearchTargetProgress.Status.ERROR }

                    if (allTargetsFailed && results.isEmpty()) {
                        // All targets failed - show error status with first exception
                        val firstError = targetProgress.firstNotNullOfOrNull { it.exception }
                            ?.let { it as? Exception }
                            ?: IllegalStateException("All search targets failed")

                        log(tag, ERROR) { "Search failed: All ${targetProgress.size} target(s) failed" }
                        _searchState.update {
                            it.copy(
                                searchStatus = State.SearchStatus.ERROR,
                                error = firstError as? Exception,
                            )
                        }
                    } else {
                        log(tag, INFO) { "Search completed: ${results.size} results" }
                        _searchState.update { it.copy(searchStatus = State.SearchStatus.COMPLETED) }
                    }
                } catch (e: CancellationException) {
                    log(tag, INFO) { "Search cancelled" }
                    _searchState.update { it.copy(searchStatus = State.SearchStatus.CANCELLED) }
                    throw e
                } catch (e: Exception) {
                    log(tag, ERROR) { "Search result collection failed: ${e.asLog()}" }
                    _searchState.update {
                        it.copy(
                            searchStatus = State.SearchStatus.ERROR,
                            error = e,
                        )
                    }
                }
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
                _searchState.value = State()
                // Clear target progress from engine
                searchEngine.clearTargetProgress()
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
            is SearcherCommand.AddDefaultPaths -> searchEngine.addDefaultPaths()
        }
    }

    fun updateTargets(transform: (List<SearchTarget>) -> List<SearchTarget>) {
        log(tag, INFO) { "updateTargets() - delegating to engine" }
        searchEngine.updateTargets(transform)
    }

    fun resolveConflict(operationId: Operation.Id, resolution: PathActionIssue.Resolution) {
        log(tag, INFO) { "Resolving conflict for operation $operationId: $resolution" }
        scope.launch {
            issueHandler.resolveIssue(operationId, resolution)
        }
    }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        scope.cancel()
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<SearcherArguments> {

        override fun create(id: Workspace.Id, arguments: SearcherArguments): SearcherWorkspace

        override fun serialize(json: Json, arguments: SearcherArguments): JsonElement {
            return json.encodeToJsonElement<SearcherArguments>(arguments)
        }

        override fun deserialize(json: Json, element: JsonElement): SearcherArguments {
            return json.decodeFromJsonElement<SearcherArguments>(element)
        }
    }
}