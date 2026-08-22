package eu.darken.butler.apps.core

import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.core.engine.AppsEngine
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.pkgs.pkgops.PkgOpsException
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.workspace.contracts.apps.AppsArguments
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.stateInWorkspace
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

class AppsWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: AppsArguments,
    dispatcherProvider: DispatcherProvider,
    appsEngineFactory: AppsEngine.Factory,
    private val appsSettings: AppsSettings,
    private val appSizeCache: AppSizeCache,
    private val pkgOps: PkgOps,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
) : Workspace<AppsArguments> {

    private val tag = logTag("Apps", "Workspace", id.shortTag)

    private val scope = CoroutineScope(
        dispatcherProvider.IO + CoroutineName(tag) + CoroutineExceptionHandler { _, throwable ->
            log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
            _state.value = State.Error(throwable)
        }
    )

    private val appsEngine = appsEngineFactory.create(id, scope)

    private val _viewStyle = MutableStateFlow<AppsViewStyle?>(null)

    override val type: Workspace.Type = Workspace.Type.APPS

    private val _state = MutableStateFlow<State>(State.Initializing)
    val state: Flow<State> = _state.asStateFlow()

    sealed interface State {
        data object Initializing : State

        data class Ready(
            val apps: List<AppItem> = emptyList(),
            val filteredApps: List<AppItem> = emptyList(),
            val filterConfig: TagFilterConfig = TagFilterConfig(),
            val sortSettings: SortSettings = SortSettings(),
            val searchQuery: String = "",
            val viewStyle: AppsViewStyle = AppsViewStyle.default(),
            val selectedAppIds: Set<InstallId> = emptySet(),
            val hasRoot: Boolean = false,
            val hasAdb: Boolean = false,
            val isLoading: Boolean = false,
            val isRefreshing: Boolean = false,
            val isResolvingSizes: Boolean = false,
            val error: Throwable? = null,
        ) : State {
            // Selection state counts only visible apps, so it always matches what actions operate on.
            val selectedApps: List<AppItem> get() = filteredApps.filter { it.pkg.installId in selectedAppIds }
            val isMultiSelectMode: Boolean get() = selectedApps.isNotEmpty()
            val selectionCount: Int get() = selectedApps.size

            val canEnableDisable: Boolean get() = hasRoot || hasAdb
            val canClearData: Boolean get() = hasRoot || hasAdb
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

    override suspend fun createArguments(): AppsArguments {
        val currentState = _state.value as? State.Ready
        return AppsArguments.Default(
            filterConfig = currentState?.filterConfig ?: TagFilterConfig(),
            sortSettings = currentState?.sortSettings ?: SortSettings(),
            viewStyle = currentState?.viewStyle ?: AppsViewStyle.default(),
        )
    }

    /**
     * Number of package operations (enable/disable/uninstall/clear) currently running. Package
     * operations don't go through OperationsManager, so this is the only signal that keeps a pause
     * from releasing the workspace mid-operation.
     */
    private val pkgOpsInFlight = MutableStateFlow(0)

    private suspend fun <T> trackPkgOp(block: suspend () -> T): T {
        pkgOpsInFlight.update { it + 1 }
        try {
            return block()
        } finally {
            pkgOpsInFlight.update { it - 1 }
        }
    }

    override val info: StateFlow<Workspace.Info> = combine(
        _state,
        pkgOpsInFlight,
    ) { state, opsInFlight ->
        Workspace.Info(
            id = id,
            type = type,
            title = when {
                Bugs.isDebug -> "Apps ${id.shortTag}".toCaString()
                else -> R.string.apps_title.toCaString()
            },
            subtitle = R.string.apps_subtitle.toCaString(),
            lifecycleState = when (state) {
                is State.Initializing -> Workspace.LifecycleState.Initializing
                is State.Error -> Workspace.LifecycleState.Error(state.error)
                is State.Ready -> Workspace.LifecycleState.Ready
            },
            operationCount = 0,
            attentionCount = 0,
            isPausable = opsInFlight == 0,
            callerWorkspaceId = null,
        )
    }.stateInWorkspace(
        scope = scope,
        initial = initialInfo(
            title = R.string.apps_title.toCaString(),
            arguments = creationArguments,
        ),
    )

    init {
        log(tag, INFO) { "AppsWorkspace initialized: $id" }

        // Load initial settings and transition to Ready state
        scope.launch {
            try {
                val args = creationArguments as? AppsArguments.Default

                val filterConfig = args?.filterConfig ?: appsSettings.defaultFilterConfig.value()
                val sortSettings = args?.sortSettings ?: appsSettings.defaultSortSettings.value()
                val viewStyle = args?.viewStyle ?: appsSettings.defaultViewStyle.value()

                log(tag) { "Loaded settings: filterConfig=$filterConfig, sortSettings=$sortSettings, viewStyle=$viewStyle" }

                appsEngine.updateFilterConfig(filterConfig)
                appsEngine.updateSortSettings(sortSettings)
                _viewStyle.value = viewStyle

                // Transition to Ready state
                _state.value = State.Ready(
                    filterConfig = filterConfig,
                    sortSettings = sortSettings,
                    viewStyle = viewStyle,
                    isLoading = true,
                )
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to initialize: ${e.asLog()}" }
                Bugs.report(e)
                _state.value = State.Error(e)
            }
        }

        // Monitor engine state and elevated access availability
        combine(
            appsEngine.state,
            rootManager.useRoot,
            adbManager.useAdb,
            _viewStyle,
        ) { engineState, hasRoot, hasAdb, viewStyle ->
            updateReady {
                copy(
                    apps = engineState.apps,
                    filteredApps = engineState.filteredApps,
                    filterConfig = engineState.filterConfig,
                    sortSettings = engineState.sortSettings,
                    searchQuery = engineState.searchQuery,
                    viewStyle = viewStyle ?: this.viewStyle,
                    selectedAppIds = engineState.selectedAppIds,
                    hasRoot = hasRoot,
                    hasAdb = hasAdb,
                    isLoading = engineState.isLoading,
                    isRefreshing = engineState.isRefreshing,
                    isResolvingSizes = engineState.isResolvingSizes,
                    error = engineState.error,
                )
            }
        }.launchIn(scope)
    }

    // Delegate methods for engine operations
    suspend fun updateFilterConfig(config: TagFilterConfig) {
        appsEngine.updateFilterConfig(config)
    }

    suspend fun updateSortSettings(settings: SortSettings) {
        appsEngine.updateSortSettings(settings)
    }

    suspend fun updateSearchQuery(query: String) {
        appsEngine.updateSearchQuery(query)
    }

    fun updateViewStyle(style: AppsViewStyle) {
        _viewStyle.value = style
    }

    suspend fun selectApp(installId: InstallId, selected: Boolean) {
        appsEngine.selectApp(installId, selected)
    }

    suspend fun toggleSelection(installId: InstallId) {
        appsEngine.toggleSelection(installId)
    }

    suspend fun clearSelection() {
        appsEngine.clearSelection()
    }

    suspend fun selectAll() {
        appsEngine.selectAll()
    }

    suspend fun selectApps(installIds: Set<InstallId>) {
        appsEngine.selectApps(installIds)
    }

    suspend fun setSelection(installIds: Set<InstallId>) {
        appsEngine.setSelection(installIds)
    }

    suspend fun refresh() {
        appsEngine.refresh(showIndicator = true)
    }

    suspend fun enableApps(apps: List<AppItem>) = trackPkgOp {
        log(tag) { "Enabling ${apps.size} apps" }
        val failures = mutableListOf<Pair<AppItem, Exception>>()
        try {
            apps.forEach { app ->
                try {
                    pkgOps.changePackageState(app.id, enabled = true)
                } catch (e: Exception) {
                    log(tag, WARN) { "Failed to enable ${app.packageName}: $e" }
                    failures.add(app to e)
                }
            }
            if (failures.isNotEmpty()) {
                throw PkgOpsException("Failed to enable ${failures.size}/${apps.size} apps", failures.first().second)
            }
        } finally {
            appsEngine.refresh()
            appsEngine.clearSelection()
        }
    }

    suspend fun disableApps(apps: List<AppItem>) = trackPkgOp {
        log(tag) { "Disabling ${apps.size} apps" }
        val failures = mutableListOf<Pair<AppItem, Exception>>()
        try {
            apps.forEach { app ->
                try {
                    pkgOps.changePackageState(app.id, enabled = false)
                } catch (e: Exception) {
                    log(tag, WARN) { "Failed to disable ${app.packageName}: $e" }
                    failures.add(app to e)
                }
            }
            if (failures.isNotEmpty()) {
                throw PkgOpsException("Failed to disable ${failures.size}/${apps.size} apps", failures.first().second)
            }
        } finally {
            appsEngine.refresh()
            appsEngine.clearSelection()
        }
    }

    suspend fun uninstallApps(apps: List<AppItem>) = trackPkgOp {
        log(tag) { "Uninstalling ${apps.size} apps" }
        val failures = mutableListOf<Pair<AppItem, Exception>>()
        try {
            apps.forEach { app ->
                try {
                    pkgOps.uninstall(app.pkg.installId)
                } catch (e: Exception) {
                    log(tag, WARN) { "Failed to uninstall ${app.packageName}: $e" }
                    failures.add(app to e)
                }
            }
            if (failures.isNotEmpty()) {
                throw PkgOpsException("Failed to uninstall ${failures.size}/${apps.size} apps", failures.first().second)
            }
        } finally {
            appsEngine.refresh()
            appsEngine.clearSelection()
        }
    }

    suspend fun clearDataApps(apps: List<AppItem>) = trackPkgOp {
        log(tag) { "Clearing data for ${apps.size} apps" }
        val failures = mutableListOf<Pair<AppItem, Exception>>()
        try {
            apps.forEach { app ->
                try {
                    pkgOps.clearData(app.pkg.installId)
                } catch (e: Exception) {
                    log(tag, WARN) { "Failed to clear data for ${app.packageName}: $e" }
                    failures.add(app to e)
                }
            }
            if (failures.isNotEmpty()) {
                throw PkgOpsException("Failed to clear data for ${failures.size}/${apps.size} apps", failures.first().second)
            }
        } finally {
            appSizeCache.invalidate(apps.map { it.pkg.installId })
            appsEngine.refresh()
            appsEngine.clearSelection()
        }
    }

    override suspend fun release() {
        log(tag, INFO) { "Releasing AppsWorkspace: $id" }
        scope.cancel()
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<AppsArguments> {
        override fun create(id: Workspace.Id, arguments: AppsArguments): AppsWorkspace

        override val argumentsSerializer: KSerializer<AppsArguments> get() = serializer()
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.APPS)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}
