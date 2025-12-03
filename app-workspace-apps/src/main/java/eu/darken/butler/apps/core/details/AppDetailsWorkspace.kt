package eu.darken.butler.apps.core.details

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.apps.core.arguments.AppDetailsArguments
import eu.darken.butler.apps.core.arguments.DetailTab
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.pkgs
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceFactory
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

class AppDetailsWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: AppDetailsArguments,
    dispatcherProvider: DispatcherProvider,
    private val pkgRepo: PkgRepo,
) : Workspace<AppDetailsArguments> {

    private val tag = logTag("AppDetails", "Workspace", id.shortTag)
    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    private val args = creationArguments
    override val type: Workspace.Type = Workspace.Type.APP_DETAILS

    override suspend fun createArguments(): AppDetailsArguments {
        return args
    }

    private val selectedTabFlow = MutableStateFlow(args.initialTab)

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
    )

    val state: Flow<State> = combine(
        appInfoFlow,
        selectedTabFlow,
    ) { app, selectedTab ->
        val paths = app?.let { buildAppPaths(it) } ?: emptyList()
        State(
            selectedTab = selectedTab,
            app = app,
            availablePaths = paths,
            isLoading = app == null,
            callerWorkspaceId = args.callerWorkspaceId,
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
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<AppDetailsArguments> {
        override fun create(id: Workspace.Id, arguments: AppDetailsArguments): AppDetailsWorkspace

        override fun serialize(json: Json, arguments: AppDetailsArguments): JsonElement {
            return json.encodeToJsonElement(arguments)
        }

        override fun deserialize(json: Json, element: JsonElement): AppDetailsArguments {
            return json.decodeFromJsonElement<AppDetailsArguments>(element)
        }
    }
}

