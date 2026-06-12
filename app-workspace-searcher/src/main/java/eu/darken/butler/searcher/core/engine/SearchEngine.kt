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
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class SearchEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val workspaceScope: CoroutineScope,
    pathScannerFactory: PathScanner.Factory,
    private val dispatcherProvider: DispatcherProvider,
    private val storageManager2: StorageManager2,
    private val searcherSettings: SearcherSettings,
    private val pathPermissionCheck: PathPermissionCheck,
) {

    private val tag = logTag("Searcher", "Workspace", workspaceId.shortTag, "Engine")
    private val pathScanner = pathScannerFactory.create(workspaceId)
    private val scope = workspaceScope

    private val _targetState = MutableStateFlow<List<SearchTarget>>(emptyList())
    val targetState: StateFlow<List<SearchTarget>> = _targetState.asStateFlow()

    private val _setupRequirements = MutableStateFlow(PathRequirements())
    val setupRequirements: StateFlow<PathRequirements> = _setupRequirements.asStateFlow()

    private val _targetProgressState = MutableStateFlow<List<SearchTargetProgress>>(emptyList())
    val targetProgressState: StateFlow<List<SearchTargetProgress>> = _targetProgressState.asStateFlow()

    init {
        log(tag, INFO) { "Initialized" }
        scope.launch {
            val savedTargets = searcherSettings.searchDefaultTargets.value()
            if (savedTargets != null) {
                log(tag, INFO) { "Loaded ${savedTargets.size} targets from settings" }
                _targetState.value = savedTargets
            } else {
                log(tag, INFO) { "No saved targets, using defaults" }
                _targetState.value = getDefaultSearchPaths()
            }
        }

        // Reactively monitor permission requirements for enabled targets
        _targetState
            .flatMapLatest { targets ->
                val enabledPaths = targets
                    .filterIsInstance<SearchTarget.Path>()
                    .filter { it.enabled }
                    .map { it.path }

                if (enabledPaths.isEmpty()) {
                    flowOf(PathRequirements())
                } else {
                    // Combine permission monitors for all enabled paths
                    combine(enabledPaths.map { pathPermissionCheck.monitor(it) }) { requirementsList ->
                        PathRequirements(
                            combos = requirementsList.flatMap { it.combos }.distinct().toSet(),
                            complete = requirementsList.flatMap { it.complete }.distinct().toSet(),
                        )
                    }
                }
            }
            .distinctUntilChanged()
            .onEach { requirements ->
                log(tag, INFO) { "Permission requirements updated: $requirements" }
                _setupRequirements.value = requirements
            }
            .launchIn(scope)
    }


    data class SearchProgress(
        val currentPath: APath<*>,
        val itemsScanned: Int,
        val resultsFound: Int
    )

    data class SearchTargetProgress(
        val target: SearchTarget.Path,
        val itemsScanned: Int,
        val resultsFound: Int,
        val status: Status,
        val exception: Throwable? = null,
    ) {
        enum class Status {
            SEARCHING, COMPLETED, ERROR, CANCELLED
        }
    }

    fun updateTargets(transform: (List<SearchTarget>) -> List<SearchTarget>) {
        val newTargets = transform(_targetState.value)
        log(tag, INFO) { "Updating search targets: ${newTargets.size} targets" }
        _targetState.value = newTargets
        scope.launch {
            searcherSettings.searchDefaultTargets.value(newTargets)
        }
    }

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
            return Result.InvalidQuery
        }

        // Validate targets
        if (command.targets.isEmpty()) {
            log(tag, ERROR) { "Cannot start search: No search targets" }
            return Result.NoTargets
        }

        // Check permissions for enabled paths
        val enabledPaths = command.targets
            .filterIsInstance<SearchTarget.Path>()
            .filter { it.enabled }
            .map { it.path }

        if (enabledPaths.isEmpty()) {
            log(tag, ERROR) { "Cannot start search: No enabled search targets" }
            return Result.NoTargets
        }

        return try {
            // Get permission requirements for all enabled paths
            val requirementsList = enabledPaths.map { path ->
                pathPermissionCheck.monitor(path).first()
            }

            // Aggregate requirements
            val setupRequirements = PathRequirements(
                combos = requirementsList.flatMap { it.combos }.distinct().toSet(),
                complete = requirementsList.flatMap { it.complete }.distinct().toSet(),
            )

            // Update state with permission requirements
            _setupRequirements.value = setupRequirements

            // Check if setup is needed
            if (setupRequirements.needsSetup) {
                log(tag, WARN) { "Cannot start search: Setup required - $setupRequirements" }
                return Result.PermissionsRequired(setupRequirements)
            }

            // Permission check passed, proceed with search
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
        data object InvalidQuery : Result
        data object NoTargets : Result
        data class PermissionsRequired(val requirements: PathRequirements) : Result
        data class Success(val results: Flow<SearchItem>) : Result
        data class Error(val exception: Exception) : Result
    }

    private suspend fun executeSearch(
        searchQuery: SearchQuery,
        onProgress: ((SearchProgress) -> Unit)? = null
    ): Flow<SearchItem> = channelFlow {
        val enabledTargets = searchQuery.targets
            .filterIsInstance<SearchTarget.Path>()
            .filter { it.enabled }

        log(
            tag,
            INFO
        ) { "Starting concurrent search (filename: ${searchQuery.filenameQuery.pattern}, content: ${searchQuery.contentQuery.pattern}) across ${enabledTargets.size} enabled path(s)" }

        // Initialize target progress states
        val initialProgress = enabledTargets.map { target ->
            SearchTargetProgress(
                target = target,
                itemsScanned = 0,
                resultsFound = 0,
                status = SearchTargetProgress.Status.SEARCHING,
            )
        }
        _targetProgressState.value = initialProgress

        val progressAggregator = ProgressAggregator()
        val foundCounter = AtomicInteger(0)
        val maxResults = searchQuery.options.maxResults
        val includeBinaries = searcherSettings.contentSearchBinaries.value()

        // Launch concurrent scanner for each path
        enabledTargets.forEach { pathTarget ->
            launch {
                try {
                    pathScanner.scan(
                        path = pathTarget.path,
                        query = searchQuery,
                        includeBinaries = includeBinaries,
                        onProgress = { pathProgress ->
                            progressAggregator.update(pathTarget.path, pathProgress)

                            // Update target progress state
                            _targetProgressState.value = _targetProgressState.value.map { targetProgress ->
                                if (targetProgress.target.path == pathTarget.path) {
                                    targetProgress.copy(
                                        itemsScanned = pathProgress.itemsScanned,
                                        resultsFound = pathProgress.resultsFound,
                                    )
                                } else {
                                    targetProgress
                                }
                            }

                            // Report aggregate progress every 100 items
                            if (pathProgress.itemsScanned % 100 == 0) {
                                val aggregate = progressAggregator.createSnapshot()
                                onProgress?.invoke(
                                    SearchProgress(
                                        currentPath = aggregate.currentPath ?: pathTarget.path,
                                        itemsScanned = aggregate.totalScanned,
                                        resultsFound = aggregate.totalFound,
                                    )
                                )
                            }
                        }
                    ).collect { result ->
                        // Check max results across all scanners
                        val found = foundCounter.incrementAndGet()
                        if (maxResults != null && found > maxResults) {
                            log(tag, INFO) { "Max results reached ($found), skipping item" }
                            cancel("Max results reached")
                            return@collect // Don't send this result
                        }
                        send(result) // Send to channelFlow
                    }

                    log(tag, INFO) { "Completed scan for path: ${pathTarget.path}" }

                    // Mark as completed
                    _targetProgressState.value = _targetProgressState.value.map { targetProgress ->
                        if (targetProgress.target.path == pathTarget.path) {
                            targetProgress.copy(status = SearchTargetProgress.Status.COMPLETED)
                        } else {
                            targetProgress
                        }
                    }
                } catch (e: CancellationException) {
                    log(tag, INFO) { "Scanner cancelled for ${pathTarget.path}" }

                    // Mark as cancelled
                    _targetProgressState.value = _targetProgressState.value.map { targetProgress ->
                        if (targetProgress.target.path == pathTarget.path) {
                            targetProgress.copy(status = SearchTargetProgress.Status.CANCELLED)
                        } else {
                            targetProgress
                        }
                    }
                    throw e
                } catch (e: Exception) {
                    log(tag, WARN) { "Failed to scan ${pathTarget.path}: ${e.message}" }

                    // Mark as error with exception details
                    _targetProgressState.value = _targetProgressState.value.map { targetProgress ->
                        if (targetProgress.target.path == pathTarget.path) {
                            targetProgress.copy(
                                status = SearchTargetProgress.Status.ERROR,
                                exception = e,
                            )
                        } else {
                            targetProgress
                        }
                    }
                    // Continue with other paths
                }
            }
        }
    }.flowOn(dispatcherProvider.IO)

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, workspaceScope: CoroutineScope): SearchEngine
    }
}