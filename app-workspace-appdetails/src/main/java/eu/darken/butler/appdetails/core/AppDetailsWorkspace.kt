package eu.darken.butler.appdetails.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.appdetails.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.features.InstallDetails
import eu.darken.butler.common.pkgs.pkgs
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

class AppDetailsWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val arguments: Workspace.Arguments?,
    dispatcherProvider: DispatcherProvider,
    private val pkgRepo: PkgRepo,
) : Workspace {

    private val tag = logTag("AppDetails", "Workspace", id.shortTag)
    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    private val args = arguments as AppDetailsArguments
    override val type: Workspace.Type = Workspace.Type.APP_DETAILS

    private val selectedTabFlow = MutableStateFlow(args.initialTab)

    // Fetch app info from package manager
    private val appInfoFlow: Flow<AppInfo?> = pkgRepo.pkgs().map { pkgs ->
        val pkg = pkgs.firstOrNull { it.id.name == args.packageName } ?: return@map null
        val installDetails = pkg as? InstallDetails

        AppInfo(
            packageName = pkg.id.name,
            label = pkg.label ?: pkg.id.name.toCaString(),
            icon = pkg.icon,
            versionName = pkg.packageInfo.versionName,
            versionCode = pkg.packageInfo.longVersionCode,
            appSize = null, // Size would need separate calculation
            isSystemApp = installDetails?.isSystemApp == true,
            isEnabled = installDetails?.isEnabled ?: true,
            pkgId = pkg.id,
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
            app = app,
            selectedTab = selectedTab,
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
                label = R.string.appdetails_path_internal_data_label.toCaString(),
            )
        )

        // External storage directory (/storage/emulated/0/Android/data/package.name)
        val externalDataPath = LocalPath.build("/storage/emulated/0/Android/data/${app.packageName}")
        add(
            AppPath(
                path = externalDataPath,
                label = R.string.appdetails_path_external_data_label.toCaString(),
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
    interface Factory {
        fun create(id: Workspace.Id, arguments: Workspace.Arguments?): AppDetailsWorkspace
    }
}

/**
 * Arguments for launching an App Details workspace.
 * Implements ArgumentsWithCaller to support modal rendering when callerWorkspaceId is set.
 *
 * This is a detail/informational workspace (not a picker), so it defaults to PANE_LOCAL
 * presentation mode, allowing it to render as an overlay within the parent's pane on tablets
 * while appearing as a full-screen modal on phones.
 *
 * @param packageName The package name of the app to display details for
 * @param initialTab The tab to show initially (defaults to OVERVIEW)
 * @param callerWorkspaceId If set, this workspace will render as a modal
 */
@Parcelize
data class AppDetailsArguments(
    val packageName: String,
    val initialTab: DetailTab = DetailTab.OVERVIEW,
    override val callerWorkspaceId: Workspace.Id? = null,
) : Workspace.ArgumentsWithCaller {
    @IgnoredOnParcel
    override val type: Workspace.Type = Workspace.Type.APP_DETAILS
}

/**
 * Available tabs in the App Details workspace
 */
enum class DetailTab {
    /**
     * Overview tab showing basic app info, storage locations, and quick actions
     */
    OVERVIEW,

    /**
     * Package info tab showing APK details, manifest, components, signing info
     */
    PACKAGE_INFO,
}
