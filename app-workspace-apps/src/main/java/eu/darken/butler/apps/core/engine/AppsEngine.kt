package eu.darken.butler.apps.core.engine

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.apps.core.AppSizeCache
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.pkgs
import eu.darken.butler.common.user.UserManager2
import eu.darken.butler.common.user.UserProfile2
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class AppsEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val workspaceScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val pkgRepo: PkgRepo,
    private val userManager: UserManager2,
    private val dispatcherProvider: DispatcherProvider,
    private val appSizeCache: AppSizeCache,
) {

    private val tag = logTag("Apps", "Workspace", workspaceId.shortTag, "Engine")

    private val _state = MutableStateFlow(AppsState(isLoading = true))
    val state: StateFlow<AppsState> = _state

    private val _filterConfig = MutableStateFlow(TagFilterConfig())
    private val _sortSettings = MutableStateFlow(SortSettings())
    private val _searchQuery = MutableStateFlow("")
    private val _selectedAppIds = MutableStateFlow<Set<InstallId>>(emptySet())
    private val _isRefreshing = MutableStateFlow(false)
    private val refreshMutex = Mutex()

    init {
        log(tag, INFO) { "AppsEngine initialized for workspace ${workspaceId.shortTag}" }

        // Expensive package processing pipeline. Kept independent of the refresh indicator so a
        // refresh toggle never re-runs mapping/filtering/sorting (and can't miss the visible window).
        val processedState = combine(
            pkgRepo.pkgs(),
            userManager.users,
            _filterConfig,
            _sortSettings,
            _searchQuery,
            _selectedAppIds,
            appSizeCache.snapshot,
        ) { packages, userProfiles, filterConfig, sortSettings, searchQuery, selectedAppIds, sizes ->
            log(tag, DEBUG) { "Processing ${packages.size} packages" }

            // Build a map of user handles to profiles for efficient lookup
            val userProfileMap = userProfiles.associateBy { it.handle }

            val appItems = packages.map { pkg ->
                try {
                    val profile = userProfileMap[pkg.userHandle]
                        ?: UserProfile2(handle = pkg.userHandle)
                    AppItem.from(pkg, userProfile = profile, appSize = sizes.sizes[pkg.installId]?.total)
                } catch (e: Exception) {
                    log(tag, WARN) { "Failed to create AppItem for ${pkg.id.name}: ${e.asLog()}" }
                    null
                }
            }.filterNotNull()

            val filtered = appItems
                .filter { filterConfig.matches(it) }
                .filter { it.matchesSearch(context, searchQuery) }
                .sortedBy(context, sortSettings)

            AppsState(
                apps = appItems,
                filteredApps = filtered,
                filterConfig = filterConfig,
                sortSettings = sortSettings,
                searchQuery = searchQuery,
                selectedAppIds = selectedAppIds,
                isLoading = false,
                error = null,
            )
        }
            .flowOn(dispatcherProvider.IO)
            .onStart {
                log(tag) { "Starting app monitoring" }
                emit(_state.value.copy(isLoading = true))
            }
            .catch { e ->
                log(tag, ERROR) { "App loading failed: ${e.asLog()}" }
                emit(_state.value.copy(isLoading = false, error = e))
            }

        // Cheap overlay of the progress flags. Because these never complete, this keeps clearing the
        // indicator (via refresh()'s finally) even if the pipeline above terminates.
        combine(
            processedState,
            _isRefreshing,
            appSizeCache.isResolving,
        ) { state, isRefreshing, isResolvingSizes ->
            state.copy(isRefreshing = isRefreshing, isResolvingSizes = isResolvingSizes)
        }
            .onEach { newState ->
                log(tag) { "State updated: ${newState.filteredApps.size}/${newState.apps.size} apps visible" }
                _state.value = newState
            }
            .launchIn(workspaceScope)

        // Size resolution is its own cancellable job, not a side effect of the state pipeline. The
        // key carries the cache's revision but not its contents: publishing sizes leaves the
        // revision untouched, so this can't feed back into itself, while an invalidation bumps it
        // and re-triggers measurement of the ids that were just dropped.
        workspaceScope.launch {
            combine(
                _state,
                appSizeCache.isAvailable,
                appSizeCache.snapshot,
            ) { state, isAvailable, snapshot ->
                ResolveTrigger(
                    mode = state.sortSettings.mode,
                    pkgs = state.filteredApps.map { it.pkg },
                    isAvailable = isAvailable,
                    revision = snapshot.revision,
                )
            }
                .distinctUntilChanged { old, new ->
                    old.mode == new.mode &&
                        old.isAvailable == new.isAvailable &&
                        old.revision == new.revision &&
                        old.pkgs.map { it.installId } == new.pkgs.map { it.installId }
                }
                // Cancels the in-flight batch when the user leaves size sorting.
                // Availability is deliberately NOT checked here: resolve() re-derives it on entry,
                // so every trigger is a chance to notice access granted outside Butler. Gating here
                // on the cached flag would latch the feature off for the whole process, because
                // nothing on this path would ever re-read the permission.
                .collectLatest { trigger ->
                    if (trigger.mode != SortSettings.Mode.SIZE) return@collectLatest
                    // VERBOSE: this fires per trigger, including when resolve() will immediately
                    // return for a missing permission. resolve() logs at DEBUG once it really
                    // measures, so that stays the signal for work actually happening.
                    log(tag, VERBOSE) { "Triggering size resolution for ${trigger.pkgs.size} apps" }
                    appSizeCache.resolve(trigger.pkgs)
                }
        }
    }

    suspend fun updateFilterConfig(config: TagFilterConfig) = withContext(dispatcherProvider.Default) {
        log(tag) { "Updating filter config: $config" }
        _filterConfig.value = config
    }

    suspend fun updateSortSettings(sortSettings: SortSettings) = withContext(dispatcherProvider.Default) {
        log(tag) { "Updating sort settings: $sortSettings" }
        _sortSettings.value = sortSettings
    }

    suspend fun updateSearchQuery(query: String) = withContext(dispatcherProvider.Default) {
        log(tag) { "Updating search query: $query" }
        _searchQuery.value = query
    }

    suspend fun selectApp(installId: InstallId, selected: Boolean) = withContext(dispatcherProvider.Default) {
        val newSelection = _selectedAppIds.updateAndGet {
            if (selected) it + installId else it - installId
        }
        log(tag) { "App selection updated: ${newSelection.size} selected" }
    }

    suspend fun clearSelection() = withContext(dispatcherProvider.Default) {
        log(tag) { "Clearing selection" }
        _selectedAppIds.value = emptySet()
    }

    // Additive: selections hidden by the current filter/search survive and reappear once it is cleared.
    suspend fun selectAll() = withContext(dispatcherProvider.Default) {
        val allIds = _state.value.filteredApps.map { it.pkg.installId }.toSet()
        log(tag) { "Selecting all ${allIds.size} visible apps" }
        _selectedAppIds.update { it + allIds }
    }

    suspend fun selectApps(installIds: Set<InstallId>) = withContext(dispatcherProvider.Default) {
        log(tag) { "Selecting ${installIds.size} apps" }
        _selectedAppIds.update { it + installIds }
    }

    suspend fun refresh(showIndicator: Boolean = false) = withContext(dispatcherProvider.IO) {
        if (!showIndicator) {
            log(tag) { "Refreshing package data (silent)" }
            pkgRepo.refresh()
            return@withContext
        }
        // User-initiated refresh: surface a pull-to-refresh indicator and drop overlapping pulls.
        if (!refreshMutex.tryLock()) {
            log(tag) { "Refresh already in progress, skipping" }
            return@withContext
        }
        try {
            log(tag) { "Manually refreshing package data" }
            _isRefreshing.value = true
            pkgRepo.refresh()
        } finally {
            _isRefreshing.value = false
            refreshMutex.unlock()
        }
    }

    private data class ResolveTrigger(
        val mode: SortSettings.Mode,
        val pkgs: List<Installed>,
        val isAvailable: Boolean,
        val revision: Long,
    )

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, workspaceScope: CoroutineScope): AppsEngine
    }
}
