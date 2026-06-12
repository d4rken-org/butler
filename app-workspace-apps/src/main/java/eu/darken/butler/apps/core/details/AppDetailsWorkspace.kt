package eu.darken.butler.apps.core.details

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.apps.core.arguments.AppDetailsArguments
import eu.darken.butler.apps.core.arguments.DetailTab
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.pkgs
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceRemote
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

class AppDetailsWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: AppDetailsArguments,
    dispatcherProvider: DispatcherProvider,
    private val pkgRepo: PkgRepo,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
    private val workspaceRemote: WorkspaceRemote,
) : Workspace<AppDetailsArguments> {

    private val tag = logTag("AppDetails", "Workspace", id.shortTag)
    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    private val args = creationArguments
    override val type: Workspace.Type = Workspace.Type.APP_DETAILS

    override suspend fun createArguments(): AppDetailsArguments {
        return args
    }

    private val selectedTabFlow = MutableStateFlow(args.initialTab)
    private var wasAppSeen = false

    // Fetch app info from package manager
    private val appInfoFlow: Flow<AppInfo?> = pkgRepo.pkgs().map { pkgs ->
        val pkg = pkgs.firstOrNull { it.id.name == args.packageName } ?: return@map null

        AppInfo(
            install = pkg,
        )
    }

    data class State(
        val app: AppInfo? = null,
        val selectedTab: DetailTab = DetailTab.OVERVIEW,
        val availablePaths: List<AppPath> = emptyList(),
        val isLoading: Boolean = true,
        val callerWorkspaceId: Workspace.Id? = null,
        val hasRoot: Boolean = false,
        val hasAdb: Boolean = false,
    ) {
        val canEnableDisable: Boolean get() = hasRoot || hasAdb
        val canForceStop: Boolean get() = hasRoot || hasAdb
        val canClearCache: Boolean get() = hasRoot
        val canClearData: Boolean get() = hasRoot || hasAdb
    }

    val state: Flow<State> = eu.darken.butler.common.flow.combine(
        appInfoFlow,
        selectedTabFlow,
        rootManager.useRoot,
        adbManager.useAdb,
    ) { app, selectedTab, hasRoot, hasAdb ->
        val paths = app?.let { buildAppPaths(it) } ?: emptyList()
        State(
            selectedTab = selectedTab,
            app = app,
            availablePaths = paths,
            isLoading = app == null,
            callerWorkspaceId = args.callerWorkspaceId,
            hasRoot = hasRoot,
            hasAdb = hasAdb,
        )
    }

    override val info: Flow<Workspace.Info> = combine(
        appInfoFlow,
        selectedTabFlow,
    ) { app, _ ->
        Workspace.Info(
            id = id,
            type = type,
            title = app?.label ?: args.packageName.toCaString(),
            subtitle = app?.packageName?.toCaString(),
            lifecycleState = Workspace.LifecycleState.Ready,
            operationCount = 0,
            attentionCount = 0,
            callerWorkspaceId = args.callerWorkspaceId,
            modalPresentation = args.modalPresentation,
        )
    }

    private fun buildAppPaths(app: AppInfo): List<AppPath> = buildList {
        // Internal data directory (/data/data/package.name)
        val internalDataPath = LocalPath.build("/data/data/${app.packageName}")
        add(
            AppPath(
                path = internalDataPath,
                label = R.string.apps_path_internal_data_label.toCaString(),
            )
        )

        // External storage directory (/storage/emulated/0/Android/data/package.name)
        val externalDataPath = LocalPath.build("/storage/emulated/0/Android/data/${app.packageName}")
        add(
            AppPath(
                path = externalDataPath,
                label = R.string.apps_path_external_data_label.toCaString(),
            )
        )
    }

    fun updateSelectedTab(tab: DetailTab) {
        log(tag) { "Tab selected: $tab" }
        selectedTabFlow.value = tab
    }

    override suspend fun release() {
        log(tag, INFO) { "Releasing AppDetailsWorkspace: $id" }
        scope.cancel()
    }

    init {
        log(tag, INFO) { "AppDetailsWorkspace initialized: $id, package=${args.packageName}" }

        // Auto-close when the package is removed (e.g. after uninstall)
        // Only close after app was seen at least once (to avoid closing during initial load)
        appInfoFlow
            .onEach { appInfo ->
                if (appInfo != null) {
                    wasAppSeen = true
                } else if (wasAppSeen) {
                    log(tag, INFO) { "Package ${args.packageName} removed, auto-closing workspace" }
                    workspaceRemote.execute(WorkspaceAction.Close(id))
                }
            }
            .launchIn(scope)
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<AppDetailsArguments> {
        override fun create(id: Workspace.Id, arguments: AppDetailsArguments): AppDetailsWorkspace

        override val argumentsSerializer: KSerializer<AppDetailsArguments> get() = serializer()
    }
}

