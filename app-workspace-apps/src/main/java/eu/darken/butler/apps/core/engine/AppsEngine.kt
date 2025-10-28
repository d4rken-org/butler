package eu.darken.butler.apps.core.engine

import android.content.Context
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.pkgs
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

class AppsEngine @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted private val workspaceScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val pkgRepo: PkgRepo,
    private val dispatcherProvider: DispatcherProvider,
) {

    private val tag = logTag("Apps", "Workspace", workspaceId.shortTag, "Engine")

    private val _state = MutableStateFlow(AppsState(isLoading = true))
    val state: StateFlow<AppsState> = _state

    init {
        log(tag, INFO) { "AppsEngine initialized for workspace ${workspaceId.shortTag}" }

        combine(
            pkgRepo.pkgs(),
            _state.map { it.filterConfig },
            _state.map { it.sortSettings },
            _state.map { it.searchQuery },
        ) { packages, filterConfig, sortSettings, searchQuery ->
            log(tag, DEBUG) { "Processing ${packages.size} packages" }

            val appItems = packages.map { pkg ->
                try {
                    AppItem.from(pkg, appSize = null)
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
                selectedAppIds = _state.value.selectedAppIds,
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
            .onEach { newState ->
                log(tag) { "State updated: ${newState.filteredApps.size}/${newState.apps.size} apps visible" }
                _state.value = newState
            }
            .launchIn(workspaceScope)
    }

    suspend fun updateFilterConfig(config: AppsState.FilterConfig) = withContext(dispatcherProvider.Default) {
        log(tag) { "Updating filter config: $config" }
        _state.value = _state.value.copy(filterConfig = config)
    }

    suspend fun updateSortSettings(sortSettings: SortSettings) = withContext(dispatcherProvider.Default) {
        log(tag) { "Updating sort settings: $sortSettings" }
        _state.value = _state.value.copy(sortSettings = sortSettings)
    }

    suspend fun updateSearchQuery(query: String) = withContext(dispatcherProvider.Default) {
        log(tag) { "Updating search query: $query" }
        _state.value = _state.value.copy(searchQuery = query)
    }

    suspend fun selectApp(packageName: String, selected: Boolean) = withContext(dispatcherProvider.Default) {
        val newSelection = if (selected) {
            _state.value.selectedAppIds + packageName
        } else {
            _state.value.selectedAppIds - packageName
        }
        log(tag) { "App selection updated: ${newSelection.size} selected" }
        _state.value = _state.value.copy(selectedAppIds = newSelection)
    }

    suspend fun clearSelection() = withContext(dispatcherProvider.Default) {
        log(tag) { "Clearing selection" }
        _state.value = _state.value.copy(selectedAppIds = emptySet())
    }

    suspend fun selectAll() = withContext(dispatcherProvider.Default) {
        val allIds = _state.value.filteredApps.map { it.packageName }.toSet()
        log(tag) { "Selecting all ${allIds.size} visible apps" }
        _state.value = _state.value.copy(selectedAppIds = allIds)
    }

    suspend fun refresh() = withContext(dispatcherProvider.IO) {
        log(tag) { "Manually refreshing package data" }
        pkgRepo.refresh()
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id, workspaceScope: CoroutineScope): AppsEngine
    }
}
