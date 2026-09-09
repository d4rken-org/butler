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
import eu.darken.butler.apps.core.operations.PackageActionOperation
import eu.darken.butler.apps.core.operations.PackageCommand
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
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.workspace.contracts.apps.AppsArguments
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.toOperationCounts
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import eu.darken.butler.workspace.core.stateInWorkspace
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
    private val tabViewStore: AppsTabViewStore,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
    private val operationsManager: OperationsManager,
    private val operationFactory: PackageActionOperation.Factory,
) : Workspace<AppsArguments> {

    private val tag = logTag("Apps", "Workspace", id.shortTag)

    private val scope = CoroutineScope(
        dispatcherProvider.IO + CoroutineName(tag) + CoroutineExceptionHandler { _, throwable ->
            log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
            _state.value = State.Error(throwable)
        }
    )

    private val appsEngine = appsEngineFactory.create(id, scope)


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

    /** Filter, sorting and view style [createArguments] reports; none of it reaches the info. */
    override val restorableStateFingerprint: Any?
        get() = (_state.value as? State.Ready)?.let {
            listOf(it.filterConfig, it.sortSettings, it.viewStyle)
        }

    /**
     * What this workspace's own operations add up to, recomputed on every state change.
     *
     * A snapshot rather than the operation list itself: [withOnlyStateChanges] re-emits the SAME
     * list instance when an operation changes state, so a StateFlow of that list would conflate
     * every transition away and freeze the counts.
     */
    private data class OwnOps(
        val unfinished: Int,
        val active: Int,
        val attention: Int,
    )

    private val ownOps: StateFlow<OwnOps> = operationsManager.operationsForWorkspace(id)
        .withOnlyStateChanges()
        .map { operations ->
            val counts = operations.toOperationCounts()
            OwnOps(unfinished = counts.unfinished, active = counts.active, attention = counts.attention)
        }
        .stateIn(scope, SharingStarted.Eagerly, OwnOps(0, 0, 0))

    /**
     * No single-flight barrier: a batch is one operation, and the action bar is driven by the
     * selection, which this clears right after submitting.
     */
    suspend fun submit(command: PackageCommand): ManagedOperation {
        log(tag) { "submit($command)" }
        val managed = operationsManager.submitManaged(
            operationFactory.create(
                actionOrigin = Operation.Metadata.Origin.Apps(id),
                command = command,
            )
        )
        appsEngine.clearSelection()
        return managed
    }

    override val info: StateFlow<Workspace.Info> = combine(
        _state,
        ownOps,
    ) { state, ownOps ->
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
            operationCount = ownOps.unfinished,
            activeCount = ownOps.active,
            attentionCount = ownOps.attention,
            isPausable = ownOps.unfinished == 0,
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
                // The tab's own slot wins: a resumed tab looks the way it was left, whatever the
                // held arguments or the current global default say.
                val viewStyle = tabViewStore.currentViewStyle(id)
                    ?: args?.viewStyle
                    ?: appsSettings.defaultViewStyle.value()

                log(tag) { "Loaded settings: filterConfig=$filterConfig, sortSettings=$sortSettings, viewStyle=$viewStyle" }

                appsEngine.updateFilterConfig(filterConfig)
                appsEngine.updateSortSettings(sortSettings)
                // Materializes the style at tab creation, so a later change of the global default
                // cannot retroactively restyle a tab the user already has open.
                tabViewStore.ensureViewStyle(id, viewStyle)

                // Transition to Ready state
                _state.value = State.Ready(
                    filterConfig = filterConfig,
                    sortSettings = sortSettings,
                    viewStyle = viewStyle,
                    isLoading = true,
                )

                // Monitor engine state and elevated access availability
                combine(
                    appsEngine.state,
                    rootManager.useRoot,
                    adbManager.useAdb,
                    // Derived from the tab's slot rather than mirroring it, so a write another tab's sheet
                    // made to this tab's slot reaches this workspace.
                    tabViewStore.observeViewStyle(id),
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
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to initialize: ${e.asLog()}" }
                Bugs.report(e)
                _state.value = State.Error(e)
            }
        }
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
