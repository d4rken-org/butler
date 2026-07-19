package eu.darken.butler.searcher.core

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
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.flow.chunked
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.engine.SearchConfig
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.searcher.core.operations.DeleteOperation
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.SearchFilter
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.contracts.searcher.SearcherArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer


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
            filter = currentState.currentSearchQuery?.filter,
            startSearch = false,
        )
    }

    override val info: MutableStateFlow<Workspace.Info> = MutableStateFlow(
        Workspace.Info(
            id = id,
            type = type,
            title = "Searcher ${id.shortTag}".toCaString(),
            lifecycleState = Workspace.LifecycleState.Ready,
        )
    )

    data class State(
        val currentSearchQuery: SearchQuery? = null,
        val searchStatus: SearchStatus = SearchStatus.IDLE,
        val results: List<SearchItem> = emptyList(),
        val limitReached: Boolean = false,
        val progress: SearchEngine.SearchProgress? = null,
        val error: Exception? = null,
        val searchTargets: List<SearchTarget> = emptyList(), // From engine
        val setupRequirements: PathRequirements = PathRequirements(), // From engine
        val targetProgress: List<SearchEngine.SearchTargetProgress> = emptyList(), // From engine
    ) {
        /**
         * True when the result set may be incomplete: some target failed outright or hit
         * per-subtree/per-file errors while others produced results. Derived from target
         * progress (not separately stored) and also true when there are zero results — "nothing
         * found" and "nothing found, but some locations couldn't be searched" must not look
         * alike. Cap truncation is NOT partiality; it is reported via [limitReached].
         */
        val partialResults: Boolean
            get() = targetProgress.any {
                it.errorCount > 0 || it.status == SearchEngine.SearchTargetProgress.Status.ERROR
            } && searchStatus != SearchStatus.ERROR

        enum class SearchStatus {
            IDLE, SEARCHING, COMPLETED, ERROR, CANCELLED
        }
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

    init {
        log(tag, INFO) { "Initialized" }

        // Initialize search state from arguments
        scope.launch {
            val args = creationArguments as? SearcherArguments.Default

            // Initialize targets from args
            if (args?.startTargets != null) {
                log(tag, INFO) { "Using targets from arguments: ${args.startTargets}" }
                searchEngine.updateTargets { args.startTargets!! }
            }

            // Build query from args (no settings defaults - start fresh)
            val filenameQuery = args?.filenameQuery ?: FilenameQuery()
            val contentQuery = args?.contentQuery ?: ContentQuery()
            val filter = args?.filter ?: SearchFilter()

            log(
                tag,
                INFO
            ) { "Initializing query state: filename=${filenameQuery.pattern}, content=${contentQuery.pattern}, filter=$filter" }

            // Always initialize currentSearchQuery with complete data
            _searchState.update { state ->
                state.copy(
                    currentSearchQuery = SearchQuery(
                        filenameQuery = filenameQuery,
                        contentQuery = contentQuery,
                        targets = args?.startTargets ?: emptyList(),
                        filter = filter,
                    )
                )
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
            SearchEngine.SearchProgress(
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
                limitReached = false,
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
                state.copy(progress = engineProgress)
            }
        })) {
            is SearchEngine.Result.InvalidQuery -> {
                log(tag, WARN) { "Search failed: Invalid query (${result.reason})" }
                _searchState.update {
                    it.copy(
                        searchStatus = State.SearchStatus.ERROR,
                        error = IllegalArgumentException(
                            result.reason?.let { reason -> "Invalid query: $reason" } ?: "Invalid query"
                        ),
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
                    // The engine streams unlimited; the cap is enforced here so that stopping at
                    // the limit is a normal completion, not a cancellation. One extra item is
                    // requested to tell "exactly the limit exists" apart from actual truncation.
                    // Dedup runs BEFORE the cap: overlapping search roots surface the same file
                    // once, and duplicates must not consume the result budget.
                    val maxResults = command.options.maxResults?.takeIf { it > 0 }
                    val seenPaths = HashSet<String>()
                    val distinctResults = result.results.filter { seenPaths.add(it.path.path) }
                    val cappedResults = when (maxResults) {
                        null -> distinctResults
                        else -> distinctResults.take(maxResults + 1)
                    }

                    val results = mutableListOf<SearchItem>()
                    cappedResults
                        .chunked(SearchConfig.RESULT_BATCH_SIZE, SearchConfig.RESULT_BATCH_INTERVAL)
                        .collect { batch ->
                            results += batch
                            _searchState.update { state ->
                                // The truncation sentinel item is never displayed
                                state.copy(results = results.take(maxResults ?: results.size))
                            }
                        }

                    val limitReached = maxResults != null && results.size > maxResults
                    if (limitReached) {
                        results.removeAt(results.size - 1)
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
                        log(tag, INFO) { "Search completed: ${results.size} results, limitReached=$limitReached" }
                        _searchState.update {
                            it.copy(
                                searchStatus = State.SearchStatus.COMPLETED,
                                results = results.toList(),
                                limitReached = limitReached,
                            )
                        }
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

        override val argumentsSerializer: KSerializer<SearcherArguments> get() = serializer()
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.SEARCHER)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}
