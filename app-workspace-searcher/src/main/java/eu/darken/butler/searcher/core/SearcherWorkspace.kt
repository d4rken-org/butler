package eu.darken.butler.searcher.core

import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.extensions.isAncestorOfOrSelf
import eu.darken.butler.common.flow.chunked
import eu.darken.butler.common.flow.combine
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
import eu.darken.butler.workspace.core.WorkspaceDisplay
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.preview.FolderPreviewResolver
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine as kotlinCombine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.util.concurrent.atomic.AtomicLong


class SearcherWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted val creationArguments: SearcherArguments,
    dispatcherProvider: DispatcherProvider,
    private val issueHandler: IssueHandler,
    private val operationsManager: OperationsManager,
    private val deleteOperationFactory: DeleteOperation.Factory,
    searchEngineFactory: SearchEngine.Factory,
    private val fileSystemHinter: FileSystemHinter,
    private val folderPreviewResolver: FolderPreviewResolver,
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
            followSymlinks = currentState.currentSearchQuery?.options?.followSymlinks ?: false,
        )
    }

    /**
     * The query, filter and targets [createArguments] reports. The tab's info republishes only the
     * identity fields, so a filter or symlink-mode change is invisible there.
     */
    override val restorableStateFingerprint: Any?
        get() = listOf(_searchState.value.currentSearchQuery, searchEngine.targetState.value)

    // Same derivation the factory hands the paused stand-in, so both name this tab identically
    private val seedDisplay = deriveSearcherDisplay(creationArguments)

    override val info: MutableStateFlow<Workspace.Info> = MutableStateFlow(
        initialInfo(
            title = seedDisplay?.title ?: type.label,
            subtitle = seedDisplay?.subtitle,
            arguments = creationArguments,
        ).copy(lifecycleState = Workspace.LifecycleState.Ready)
    )

    /**
     * Everything the tab identity is derived from. Blank queries normalize to null so the state
     * built from arguments compares equal to the arguments themselves.
     */
    private data class IdentitySource(
        val filenameQuery: FilenameQuery?,
        val contentQuery: ContentQuery?,
        val targets: List<SearchTarget>,
    ) {
        companion object {
            fun of(
                filenameQuery: FilenameQuery?,
                contentQuery: ContentQuery?,
                targets: List<SearchTarget>,
            ) = IdentitySource(
                filenameQuery = filenameQuery?.takeIf { it.isNotEmpty },
                contentQuery = contentQuery?.takeIf { it.isNotEmpty },
                targets = targets,
            )
        }
    }

    /** Flipped once the arguments have been applied to the query and target state. */
    private val identityInitialized = MutableStateFlow(false)

    /** What [info] currently describes; publishing is skipped while this still holds. */
    @Volatile
    private var publishedIdentity: IdentitySource = (creationArguments as? SearcherArguments.Default).let {
        IdentitySource.of(it?.filenameQuery, it?.contentQuery, it?.startTargets.orEmpty())
    }

    /**
     * Republishes the tab identity through the same derivation the paused stand-in uses, so the
     * two can never disagree. Skipped by VALUE when nothing identifying changed - never by emission
     * position, which would silently drop targets that arrived before the observer subscribed.
     */
    private fun publishIdentity(source: IdentitySource) {
        if (source == publishedIdentity) return
        publishedIdentity = source
        val display = searcherDisplay(
            filenameQuery = source.filenameQuery,
            contentQuery = source.contentQuery,
            targets = source.targets,
        )
        // update(), not value =: the operation-count and pausability collectors write the same
        // flow concurrently, and a copy() off a stale snapshot would revert their field.
        info.update {
            it.copy(
                title = display?.title ?: type.label,
                subtitle = display?.subtitle,
            )
        }
        log(tag, VERBOSE) { "Republished identity: $display" }
    }

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
        val accessErrorRequirements: PathRequirements = PathRequirements(), // From engine
    ) {
        /**
         * True when the result set may be incomplete: some target failed outright, hit inaccessible
         * items (permission denied etc.), or had a degraded/partial read. Derived from target
         * progress (not separately stored). Cap truncation is NOT partiality; it is reported via
         * [limitReached].
         */
        val partialResults: Boolean
            get() = targetProgress.any {
                it.errorCount > 0 ||
                    it.accessErrorCount > 0 ||
                    it.status == SearchEngine.SearchTargetProgress.Status.ERROR
            } && searchStatus != SearchStatus.ERROR

        enum class SearchStatus {
            IDLE, SEARCHING, COMPLETED, ERROR, CANCELLED
        }
    }

    private val _searchState = MutableStateFlow(State())

    /**
     * Paths removed by file operations (any workspace) while a search is running. Batches of an
     * in-flight search may still append items below these paths, so display filters against them;
     * once the search reaches a terminal state they are folded into the results and cleared.
     * Kept as a minimal ancestor antichain, scoped to the current search targets.
     */
    private val removedPaths = MutableStateFlow<Set<APath<*>>>(emptySet())

    /**
     * Increases for every new search (and clear). All state writes belonging to a search run are
     * guarded by the generation they started with, so a cancelled run can't write e.g. a stale
     * CANCELLED status into the run that replaced it.
     */
    private val searchGeneration = AtomicLong(0L)

    private fun updateGuarded(generation: Long, transform: (State) -> State) {
        _searchState.update { state ->
            if (searchGeneration.get() == generation) transform(state) else state
        }
    }

    @Volatile
    private var visibleResultsCache: Triple<List<SearchItem>, Set<APath<*>>, List<SearchItem>>? = null

    private fun visibleResults(raw: List<SearchItem>, removed: Set<APath<*>>): List<SearchItem> {
        // Identity of the raw list is preserved when nothing is filtered, so downstream
        // identity-keyed caches (sorting/dedup in the ViewModel) stay effective.
        if (removed.isEmpty()) return raw
        visibleResultsCache?.let { (cachedRaw, cachedRemoved, cachedValue) ->
            if (cachedRaw === raw && cachedRemoved === removed) return cachedValue
        }
        val computed = raw.pruning(removed)
        visibleResultsCache = Triple(raw, removed, computed)
        return computed
    }

    val state: Flow<State> = combine(
        _searchState,
        searchEngine.targetState,
        searchEngine.setupRequirements,
        searchEngine.targetProgressState,
        searchEngine.accessErrorRequirements,
        removedPaths,
    ) { searchState, targets, requirements, targetProgress, accessErrorRequirements, removed ->
        searchState.copy(
            results = visibleResults(searchState.results, removed),
            searchTargets = targets,
            setupRequirements = requirements,
            targetProgress = targetProgress,
            accessErrorRequirements = accessErrorRequirements,
        )
    }

    private var activeSearchJob: Job? = null

    fun search(command: SearcherCommand.Search) {
        log(tag) { "search(): $command" }

        val generation = searchGeneration.incrementAndGet()

        // Serialize runs: the previous job is fully terminated before the new run touches any
        // shared state (engine progress, info subtitle), so a late cancellation of the old run
        // can't leak into the new one.
        val previousJob = activeSearchJob
        activeSearchJob = scope.launch {
            previousJob?.cancelAndJoin()
            processSearchRequest(command, generation)
        }
    }

    private fun onFileSystemEvent(event: FileSystemEvent) {
        if (event !is FileSystemEvent.Removed) return

        // Scope against the query that produced the displayed results, not the editable engine
        // targets — editing targets without re-searching must not stop pruning of old results.
        val queryTargets = _searchState.value.currentSearchQuery?.targets?.filter { it.enabled }
            ?: emptyList()
        val pathTargets = queryTargets.filterIsInstance<SearchTarget.Path>()
        // MediaStore results can live anywhere on external storage, so with an active media
        // target ANY removed local path is relevant — ancestry against a target root can't scope
        // an index-backed collection.
        val hasMediaTargets = queryTargets.any { it is SearchTarget.MediaStore }
        if (pathTargets.isEmpty() && !hasMediaTargets) return

        // Alias-normalized comparison: MediaStore reports /storage/emulated/0/... while a target
        // or removal event may use an alias spelling like /sdcard/...
        val relevant = event.paths.map { ResultPathKeys.comparable(it.lookedUp) }.filter { removed ->
            (hasMediaTargets && removed is LocalPath) || pathTargets.any { target ->
                val targetPath = ResultPathKeys.comparable(target.path)
                targetPath.isAncestorOfOrSelf(removed) || removed.isAncestorOfOrSelf(targetPath)
            }
        }
        if (relevant.isEmpty()) return
        log(tag) { "onFileSystemEvent(): pruning ${relevant.size} removed paths from results" }

        if (_searchState.value.searchStatus == State.SearchStatus.SEARCHING) {
            // Batches may still append items below these paths; filter at display time
            removedPaths.update { it.plusMinimal(relevant) }
        } else {
            // No batches in flight; prune directly. A concurrently starting search resets
            // results anyway, making this either a no-op or harmless.
            _searchState.update { state ->
                state.copy(results = state.results.pruning(relevant))
            }
        }
    }

    // Entries in `removed` are already alias-normalized (see onFileSystemEvent); item paths are
    // normalized here so both sides of the ancestry check compare in the same spelling.
    private fun List<SearchItem>.pruning(removed: Collection<APath<*>>): List<SearchItem> =
        filterNot { item ->
            val comparable = ResultPathKeys.comparable(item.path)
            removed.any { it.isAncestorOfOrSelf(comparable) }
        }

    private fun Set<APath<*>>.plusMinimal(new: Collection<APath<*>>): Set<APath<*>> {
        val result = toMutableSet()
        new.forEach { candidate ->
            if (result.any { it.isAncestorOfOrSelf(candidate) }) return@forEach
            result.removeAll { candidate.isAncestorOfOrSelf(it) }
            result.add(candidate)
        }
        return result
    }

    init {
        log(tag, INFO) { "Initialized" }

        // Initialize search state from arguments
        scope.launch {
            val args = creationArguments as? SearcherArguments.Default

            // Build query from args (no settings defaults - start fresh)
            val filenameQuery = args?.filenameQuery ?: FilenameQuery()
            val contentQuery = args?.contentQuery ?: ContentQuery()
            val filter = args?.filter ?: SearchFilter()

            log(
                tag,
                INFO
            ) { "Initializing query state: filename=${filenameQuery.pattern}, content=${contentQuery.pattern}, filter=$filter" }

            // Always initialize currentSearchQuery with complete data. Before the targets, so the
            // target change republishes the identity with this query already in place.
            _searchState.update { state ->
                state.copy(
                    currentSearchQuery = SearchQuery(
                        filenameQuery = filenameQuery,
                        contentQuery = contentQuery,
                        targets = args?.startTargets ?: emptyList(),
                        filter = filter,
                        options = SearchQuery.Options(followSymlinks = args?.followSymlinks ?: false),
                    )
                )
            }

            // Initialize targets from args
            if (args?.startTargets != null) {
                log(tag, INFO) { "Using targets from arguments: ${args.startTargets}" }
                searchEngine.updateTargets { args.startTargets!! }
            }

            // Only now does the state describe this tab; before that a publish would drop the
            // argument-derived identity the seed already shows
            identityInitialized.value = true
        }

        // The single identity publisher: every query change (search, clear) and every target
        // change (edited, defaults added, engine-loaded) republishes through one derivation.
        // Gated instead of position-skipped: targets can already be loaded when this subscribes,
        // and dropping that value would leave the live tab without the targets its own
        // createArguments() persists.
        identityInitialized
            .filter { it }
            .flatMapLatest {
                kotlinCombine(
                    _searchState.map { state -> state.currentSearchQuery }.distinctUntilChanged(),
                    searchEngine.targetState,
                ) { query, targets -> IdentitySource.of(query?.filenameQuery, query?.contentQuery, targets) }
            }
            .onEach { publishIdentity(it) }
            .launchIn(scope)

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

                // update(), not value =: the pausability and subtitle collectors write the same
                // flow concurrently, and a copy() off a stale snapshot would revert their field.
                info.update {
                    it.copy(
                        operationCount = operationCount,
                        attentionCount = attentionCount,
                    )
                }
                log(tag, VERBOSE) { "Updated operation counts: active=$operationCount, attention=$attentionCount" }
            }
            .launchIn(scope)

        // A running search or a populated result set cannot survive a pause: createArguments()
        // always persists startSearch=false and never carries results.
        _searchState
            .map { it.searchStatus == State.SearchStatus.SEARCHING || it.results.isNotEmpty() }
            .distinctUntilChanged()
            .onEach { busy -> info.update { it.copy(isPausable = !busy) } }
            .launchIn(scope)

        // Live-prune results when files are removed by operations from any workspace
        fileSystemHinter.events
            .onEach { onFileSystemEvent(it) }
            .launchIn(scope)
    }

    private suspend fun processSearchRequest(command: SearcherCommand.Search, generation: Long) {
        log(tag) { "processSearchRequest(): filename=${command.filenameQuery.pattern}, content=${command.contentQuery.pattern}" }

        // Wipe the previous run's per-target progress up front. A rejected request (invalid
        // query, no targets, permissions) never reaches the engine's own progress init, and must
        // not keep showing the prior search's counts, access errors, or setup suggestion.
        searchEngine.clearTargetProgress()

        // Set initial progress; the display path is optional (index-backed targets have none)
        val initialProgress = SearchEngine.SearchProgress(
            currentPath = command.targets.firstOrNull { it.enabled }
                ?.let { it as? SearchTarget.Path }?.path,
            itemsScanned = 0,
            resultsFound = 0,
        )

        // Build search query for display
        val searchQuery = SearchQuery(
            filenameQuery = command.filenameQuery,
            contentQuery = command.contentQuery,
            targets = command.targets,
            filter = command.filter,
            options = command.options,
        )

        // Clear previous results and enter SEARCHING state; results reset before tombstones so
        // no frame can re-show previously pruned items. Only the tombstones captured BEFORE the
        // reset are dropped — one added concurrently belongs to this run and must survive.
        val staleTombstones = removedPaths.value
        updateGuarded(generation) {
            it.copy(
                currentSearchQuery = searchQuery,
                searchStatus = State.SearchStatus.SEARCHING,
                results = emptyList(),
                limitReached = false,
                progress = initialProgress,
                error = null,
            )
        }
        removedPaths.update { it - staleTombstones }

        // Delegate to engine
        try {
        when (val result = searchEngine.search(command, onProgress = { engineProgress ->
            updateGuarded(generation) { state ->
                state.copy(progress = engineProgress)
            }
        })) {
            is SearchEngine.Result.InvalidQuery -> {
                log(tag, WARN) { "Search failed: Invalid query (${result.reason})" }
                updateGuarded(generation) {
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
                updateGuarded(generation) {
                    it.copy(
                        searchStatus = State.SearchStatus.ERROR,
                        error = IllegalArgumentException("No search targets specified"),
                    )
                }
            }

            is SearchEngine.Result.PermissionsRequired -> {
                log(tag, WARN) { "Search failed: Permissions required - ${result.requirements}" }
                updateGuarded(generation) {
                    it.copy(
                        searchStatus = State.SearchStatus.ERROR,
                        error = IllegalStateException("Insufficient permissions for search targets"),
                    )
                }
            }

            is SearchEngine.Result.Error -> {
                log(tag, ERROR) { "Search failed: ${result.exception.asLog()}" }
                updateGuarded(generation) {
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
                    // once, and duplicates must not consume the result budget. Duplicates from a
                    // higher-ranked source REPLACE the kept item (fresh filesystem metadata wins
                    // over a possibly stale index row) — best-effort once the cap fires.
                    // All accumulator access happens downstream of chunked() (its upstream runs
                    // in a producer coroutine) so accumulation stays single-threaded. Trade-off:
                    // the cap is observed per batch, so scanners may run up to one batch interval
                    // past the limit before cancellation — bounded and accepted.
                    val maxResults = command.options.maxResults?.takeIf { it > 0 }
                    val accumulator = ResultAccumulator()

                    result.results
                        .chunked(SearchConfig.RESULT_BATCH_SIZE, SearchConfig.RESULT_BATCH_INTERVAL)
                        .transformWhile { batch ->
                            val changed = mutableListOf<SearchItem>()
                            for (backendResult in batch) {
                                // Sentinel reached: drop the batch remainder, matching take(n)
                                if (maxResults != null && accumulator.uniqueCount > maxResults) break
                                if (accumulator.add(backendResult) != ResultAccumulator.Outcome.Ignored) {
                                    changed += backendResult.item
                                }
                            }
                            if (changed.isNotEmpty()) emit(changed)
                            maxResults == null || accumulator.uniqueCount <= maxResults
                        }
                        .collect { changed ->
                            // Directory results resolve fresh collages for this search
                            val dirs = changed.filterIsInstance<SearchItem.Directory>().map { it.path }
                            folderPreviewResolver.invalidateDirs(dirs)

                            updateGuarded(generation) { state ->
                                // The truncation sentinel item is never displayed
                                state.copy(results = accumulator.snapshot(maxResults))
                            }
                        }

                    val limitReached = maxResults != null && accumulator.uniqueCount > maxResults
                    if (limitReached) {
                        accumulator.removeLast()
                    }
                    val results = accumulator.snapshot()

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
                        updateGuarded(generation) {
                            it.copy(
                                searchStatus = State.SearchStatus.ERROR,
                                error = firstError,
                            )
                        }
                    } else {
                        log(tag, INFO) { "Search completed: ${results.size} results, limitReached=$limitReached" }
                        // Fold accumulated removals into the final list, then drop exactly the
                        // folded tombstones — one added concurrently must keep filtering.
                        val foldedTombstones = removedPaths.value
                        val finalResults = results.pruning(foldedTombstones)
                        updateGuarded(generation) {
                            it.copy(
                                searchStatus = State.SearchStatus.COMPLETED,
                                results = finalResults,
                                limitReached = limitReached,
                            )
                        }
                        if (searchGeneration.get() == generation) {
                            removedPaths.update { it - foldedTombstones }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    log(tag, ERROR) { "Search result collection failed: ${e.asLog()}" }
                    updateGuarded(generation) {
                        it.copy(
                            searchStatus = State.SearchStatus.ERROR,
                            error = e,
                        )
                    }
                }
            }
        }
        } catch (e: CancellationException) {
            log(tag, INFO) { "Search cancelled" }
            updateGuarded(generation) { it.copy(searchStatus = State.SearchStatus.CANCELLED) }
            throw e
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
                searchGeneration.incrementAndGet()
                // Cancel any active search
                activeSearchJob?.cancel()
                // Reset state to initial empty state; results before tombstones
                _searchState.value = State()
                removedPaths.value = emptySet()
                // Clear target progress from engine
                searchEngine.clearTargetProgress()
                // The identity publisher observes currentSearchQuery, so dropping it here is what
                // takes the stale query out of the tab name
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

        override fun deriveDisplay(arguments: SearcherArguments): WorkspaceDisplay? =
            deriveSearcherDisplay(arguments)
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
