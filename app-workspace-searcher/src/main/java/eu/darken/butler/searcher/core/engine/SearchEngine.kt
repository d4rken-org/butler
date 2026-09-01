package eu.darken.butler.searcher.core.engine

import android.os.Environment
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.filterDistinctRoots
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.engine.backend.SearchBackend
import eu.darken.butler.searcher.core.engine.backend.UnsupportedFilterException
import eu.darken.butler.searcher.core.engine.backend.UnsupportedTargetException
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val workspaceScope: CoroutineScope,
    private val backends: Set<@JvmSuppressWildcards SearchBackend>,
    private val dispatcherProvider: DispatcherProvider,
    private val storageManager2: StorageManager2,
    private val searcherSettings: SearcherSettings,
    private val pathPermissionCheck: PathPermissionCheck,
) {

    private val tag = logTag("Searcher", "Workspace", workspaceId.shortTag, "Engine")
    private val scope = workspaceScope

    private val _targetState = MutableStateFlow<List<SearchTarget>>(emptyList())
    val targetState: StateFlow<List<SearchTarget>> = _targetState.asStateFlow()

    private val _setupRequirements = MutableStateFlow(PathRequirements())
    val setupRequirements: StateFlow<PathRequirements> = _setupRequirements.asStateFlow()

    private val _targetProgressState = MutableStateFlow<List<SearchTargetProgress>>(emptyList())
    val targetProgressState: StateFlow<List<SearchTargetProgress>> = _targetProgressState.asStateFlow()

    /**
     * Setup requirements that would unlock the items the current scan could not read
     * (e.g. Android/data without root/Shizuku enabled). Complements [setupRequirements], which
     * gates on the target roots before a search starts: a target can pass that pre-flight check
     * yet still hit protected subtrees mid-walk. For a protected local path that needs setup
     * escalation, [PathPermissionCheck] offers root without checking for a known root manager
     * package, while Shizuku is only offered when its app is installed. Derived from
     * [targetProgressState], so it resets with it on every new search.
     */
    val accessErrorRequirements: StateFlow<PathRequirements> = _targetProgressState
        .map { progress -> progress.flatMap { it.accessErrorPaths }.filterDistinctRoots() }
        .distinctUntilChanged()
        .flatMapLatest { errorRoots ->
            if (errorRoots.isEmpty()) flowOf(PathRequirements()) else pathPermissionCheck.monitor(errorRoots)
        }
        .stateIn(scope, SharingStarted.Eagerly, PathRequirements())

    init {
        log(tag, INFO) { "Initialized with ${backends.size} backend(s)" }
        scope.launch {
            val savedTargets = searcherSettings.searchDefaultTargets.value()
            if (savedTargets != null) {
                log(tag, INFO) { "Loaded ${savedTargets.size} targets from settings" }
                _targetState.value = savedTargets.normalized()
            } else {
                log(tag, INFO) { "No saved targets, using defaults" }
                _targetState.value = getDefaultSearchPaths()
            }
        }

        // Reactively monitor permission requirements for enabled targets.
        // Gating is per-target (any target that needs setup blocks the search); the published
        // aggregate is a display-only union for the setup card.
        _targetState
            .flatMapLatest { targets ->
                val enabledTargets = targets.filter { it.enabled }
                if (enabledTargets.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        enabledTargets.map { target ->
                            backendFor(target)?.monitorRequirements(target) ?: flowOf(PathRequirements())
                        }
                    ) { it.toList() }
                }
            }
            .distinctUntilChanged()
            .onEach { requirementsList ->
                val aggregate = aggregateForDisplay(requirementsList)
                log(tag, INFO) { "Permission requirements updated: $aggregate" }
                _setupRequirements.value = aggregate
            }
            .launchIn(scope)
    }

    data class SearchProgress(
        val currentPath: APath<*>?,
        val itemsScanned: Int,
        val resultsFound: Int
    )

    data class SearchTargetProgress(
        val target: SearchTarget,
        val itemsScanned: Int,
        val resultsFound: Int,
        val status: Status,
        val exception: Throwable? = null,
        val errorCount: Int = 0,
        val accessErrorCount: Int = 0,
        val accessErrorPaths: List<APath<*>> = emptyList(),
    ) {
        enum class Status {
            SEARCHING, COMPLETED, ERROR, CANCELLED
        }
    }

    fun updateTargets(transform: (List<SearchTarget>) -> List<SearchTarget>) {
        val newTargets = transform(_targetState.value).normalized()
        log(tag, INFO) { "Updating search targets: ${newTargets.size} targets" }
        _targetState.value = newTargets
        scope.launch {
            searcherSettings.searchDefaultTargets.value(newTargets)
        }
    }

    // Persisted target lists can carry identity-duplicates (e.g. hand-edited settings or old
    // versions); progress tracking and dedup rely on identity being unique per scan.
    private fun List<SearchTarget>.normalized(): List<SearchTarget> = distinctBy { it.identity }

    fun addDefaultPaths() {
        log(tag, INFO) { "Adding default search paths" }
        val defaultPaths = getDefaultSearchPaths()
        _targetState.value = defaultPaths
        scope.launch {
            searcherSettings.searchDefaultTargets.value(defaultPaths)
        }
    }

    fun clearTargetProgress() {
        log(tag, INFO) { "Clearing target progress state" }
        _targetProgressState.value = emptyList()
    }

    private fun backendFor(target: SearchTarget): SearchBackend? {
        val candidates = backends.filter { it.canHandle(target) }
        if (candidates.isEmpty()) return null
        val topPriority = candidates.maxOf { it.priority }
        val top = candidates.filter { it.priority == topPriority }
        if (top.size > 1) {
            log(tag, ERROR) {
                "Ambiguous backend registration for $target: ${top.map { it::class.simpleName }} " +
                    "share priority $topPriority — picking deterministically, fix the registrations!"
            }
        }
        return top.minByOrNull { it::class.java.name }
    }

    private fun aggregateForDisplay(requirementsList: List<PathRequirements>) = PathRequirements(
        combos = requirementsList.flatMap { it.combos }.distinct().toSet(),
        complete = requirementsList.flatMap { it.complete }.distinct().toSet(),
    )

    private fun getDefaultSearchPaths(): List<SearchTarget> {
        log(tag, INFO) { "Getting default search paths (all public storage volumes)" }

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

        return volumes.map { SearchTarget.Path.from(it) }
    }

    /**
     * Starts a search and streams results until the targets are exhausted or the returned flow
     * is cancelled. The engine does NOT enforce [SearchQuery.Options.maxResults] — the caller
     * owns the result cap (see SearcherWorkspace), so stopping at the cap is a normal
     * completion rather than a cancellation.
     */
    suspend fun search(
        command: SearcherCommand.Search,
        onProgress: ((SearchProgress) -> Unit)? = null
    ): Result {
        log(
            tag,
            INFO
        ) { "search(): filename=${command.filenameQuery.pattern}, content=${command.contentQuery.pattern}" }

        // Validate query - at least one pattern OR active filters required
        val hasPattern = command.filenameQuery.isNotEmpty || command.contentQuery.isNotEmpty
        val hasFilters = command.filter.hasConditions()
        if (!hasPattern && !hasFilters) {
            log(tag, WARN) { "Skipping search - no patterns and no filters" }
            return Result.InvalidQuery()
        }

        // Patterns must compile - checked once here so an invalid regex surfaces as an error
        // instead of failing per item and reading as "no results"
        if (command.filenameQuery.isNotEmpty) {
            PatternMatcher.validate(command.filenameQuery.pattern, command.filenameQuery.patternOptions)?.let {
                log(tag, WARN) { "Invalid filename pattern: $it" }
                return Result.InvalidQuery(it)
            }
        }
        if (command.contentQuery.isNotEmpty) {
            PatternMatcher.validate(command.contentQuery.pattern, command.contentQuery.patternOptions)?.let {
                log(tag, WARN) { "Invalid content pattern: $it" }
                return Result.InvalidQuery(it)
            }
        }

        // Validate targets
        val enabledTargets = command.targets.filter { it.enabled }
        if (enabledTargets.isEmpty()) {
            log(tag, ERROR) { "Cannot start search: No enabled search targets" }
            return Result.NoTargets
        }

        return try {
            // Per-target permission gating: ANY target that needs setup blocks the search —
            // a union would let one satisfied target mask another target's requirements.
            val requirementsByTarget = enabledTargets.mapNotNull { target ->
                val backend = backendFor(target) ?: return@mapNotNull null
                target to backend.monitorRequirements(target).first()
            }
            val aggregate = aggregateForDisplay(requirementsByTarget.map { it.second })
            _setupRequirements.value = aggregate

            if (requirementsByTarget.any { it.second.needsSetup }) {
                log(tag, WARN) { "Cannot start search: Setup required - $aggregate" }
                return Result.PermissionsRequired(aggregate)
            }

            val searchQuery = SearchQuery(
                filenameQuery = command.filenameQuery,
                contentQuery = command.contentQuery,
                targets = command.targets,
                filter = command.filter,
                options = command.options,
            )

            Result.Success(executeSearch(searchQuery, onProgress))
        } catch (e: CancellationException) {
            log(tag, INFO) { "Search cancelled" }
            throw e
        } catch (e: Exception) {
            log(tag, ERROR) { "Search failed: ${e.asLog()}" }
            Result.Error(e)
        }
    }

    sealed interface Result {
        data class InvalidQuery(val reason: String? = null) : Result
        data object NoTargets : Result
        data class PermissionsRequired(val requirements: PathRequirements) : Result
        data class Success(val results: Flow<SearchBackend.BackendResult>) : Result
        data class Error(val exception: Exception) : Result
    }

    private suspend fun executeSearch(
        searchQuery: SearchQuery,
        onProgress: ((SearchProgress) -> Unit)? = null
    ): Flow<SearchBackend.BackendResult> = channelFlow {
        val enabledTargets = searchQuery.targets.filter { it.enabled }.distinctBy { it.identity }

        log(
            tag,
            INFO
        ) { "Starting concurrent search (filename: ${searchQuery.filenameQuery.pattern}, content: ${searchQuery.contentQuery.pattern}) across ${enabledTargets.size} enabled target(s)" }

        // Initialize target progress states
        _targetProgressState.value = enabledTargets.map { target ->
            SearchTargetProgress(
                target = target,
                itemsScanned = 0,
                resultsFound = 0,
                status = SearchTargetProgress.Status.SEARCHING,
            )
        }

        fun updateProgress(target: SearchTarget, transform: (SearchTargetProgress) -> SearchTargetProgress) {
            _targetProgressState.update { current ->
                current.map { if (it.target == target) transform(it) else it }
            }
        }

        val progressAggregator = ProgressAggregator()
        val includeBinaries = searcherSettings.contentSearchBinaries.value()

        // Launch concurrent scanner for each target
        enabledTargets.forEach { target ->
            launch {
                try {
                    val backend = backendFor(target) ?: throw UnsupportedTargetException(target)
                    searchQuery.filter.conditions.firstOrNull { !backend.supports(it) }?.let {
                        throw UnsupportedFilterException(it)
                    }

                    val session = SearchBackend.ScanSession(
                        workspaceId = workspaceId,
                        target = target,
                        query = searchQuery,
                        includeBinaries = includeBinaries,
                        onProgress = { scanProgress ->
                            progressAggregator.update(target, scanProgress)

                            updateProgress(target) {
                                it.copy(
                                    itemsScanned = scanProgress.itemsScanned,
                                    resultsFound = scanProgress.resultsFound,
                                    errorCount = scanProgress.errorCount,
                                    accessErrorCount = scanProgress.accessErrorCount,
                                    accessErrorPaths = scanProgress.accessErrorPaths,
                                )
                            }

                            // Report aggregate progress every 100 items
                            if (scanProgress.itemsScanned % 100 == 0) {
                                val aggregate = progressAggregator.createSnapshot()
                                onProgress?.invoke(
                                    SearchProgress(
                                        currentPath = aggregate.currentPath ?: scanProgress.currentPath,
                                        itemsScanned = aggregate.totalScanned,
                                        resultsFound = aggregate.totalFound,
                                    )
                                )
                            }
                        }
                    )

                    backend.scan(session).collect { result ->
                        send(result) // Send to channelFlow
                    }

                    log(tag, INFO) { "Completed scan for target: ${target.displayText}" }
                    updateProgress(target) { it.copy(status = SearchTargetProgress.Status.COMPLETED) }
                } catch (e: CancellationException) {
                    log(tag, INFO) { "Scanner cancelled for ${target.displayText}" }
                    updateProgress(target) { it.copy(status = SearchTargetProgress.Status.CANCELLED) }
                    throw e
                } catch (e: Exception) {
                    log(tag, WARN) { "Failed to scan ${target.displayText}: ${e.message}" }
                    updateProgress(target) {
                        it.copy(
                            status = SearchTargetProgress.Status.ERROR,
                            exception = e,
                        )
                    }
                    // Continue with other targets
                }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, workspaceScope: CoroutineScope): SearchEngine
    }
}
