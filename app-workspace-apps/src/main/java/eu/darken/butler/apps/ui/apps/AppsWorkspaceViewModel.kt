package eu.darken.butler.apps.ui.apps

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.net.toUri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.apps.core.AppsSettings
import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.core.engine.standardTags
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogState
import eu.darken.butler.apps.ui.apps.elements.AppsActionBarItem
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.AppStore
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.features.SourceAvailable
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.contracts.apps.AppTag
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.contracts.apps.SortSettings
import eu.darken.butler.workspace.contracts.apps.TagFilterConfig
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@HiltViewModel(assistedFactory = AppsWorkspaceViewModel.Factory::class)
class AppsWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    @ApplicationContext private val context: Context,
    dispatchers: DispatcherProvider,
    workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val appsSettings: AppsSettings,
) : ViewModel4(dispatchers, logTag("Apps", "Workspace", id.shortTag, "Page")) {

    private val workspaceSource: Flow<AppsWorkspace?> =
        workspaceProvider.retrieve(id)
            .map { workspace: Workspace<out Workspace.Arguments>? -> workspace as? AppsWorkspace }

    private suspend fun getWorkspace(): AppsWorkspace = workspaceSource.filterNotNull().first()
    private suspend fun getReadyState(): State.Ready = state.filterIsInstance<State.Ready>().first()

    private val workspaceState: Flow<AppsWorkspace.State?> = workspaceSource.flatMapLatest { ws ->
        ws?.state ?: flowOf(null)
    }

    private val workspaceReadyState: Flow<AppsWorkspace.State.Ready?> = workspaceState.map {
        it as? AppsWorkspace.State.Ready
    }

    private val searchQueryFlow = MutableStateFlow(TextFieldValue(""))
    private val dialogStateFlow = MutableStateFlow<AppsDialogState>(AppsDialogState.None)

    sealed interface State {
        data object Initializing : State

        data class Error(val error: Throwable) : State

        data class Ready(
            val apps: List<AppItem> = emptyList(),
            val searchQuery: TextFieldValue = TextFieldValue(""),
            val viewStyle: AppsViewStyle = AppsViewStyle.default(),
            val filterConfig: TagFilterConfig = TagFilterConfig(),
            val sortSettings: SortSettings = SortSettings(),
            val selectedAppIds: Set<InstallId> = emptySet(),
            val hasRoot: Boolean = false,
            val hasAdb: Boolean = false,
            val isLoading: Boolean = true,
            val isRefreshing: Boolean = false,
            val error: Throwable? = null,
            val dialogState: AppsDialogState = AppsDialogState.None,
            val availableActions: List<AppsActionBarItem> = emptyList(),
        ) : State {
            // Selection state counts only visible apps, so it always matches what actions operate on.
            val selectedApps: List<AppItem> get() = apps.filter { it.pkg.installId in selectedAppIds }
            val isMultiSelectMode: Boolean get() = selectedApps.isNotEmpty()
            val selectionCount: Int get() = selectedApps.size

            // Body properties, not constructor defaults: the generated copy() passes existing
            // property values instead of re-evaluating defaults, which would keep stale counts.
            val systemAppsCount: Int = apps.count { it.isSystemApp }
            val userAppsCount: Int = apps.size - systemAppsCount
        }
    }

    val state: Flow<State> = workspaceSource.flatMapLatest { ws ->
        if (ws == null) {
            flowOf(State.Initializing)
        } else {
            workspaceState.flatMapLatest { wsState ->
                when (wsState) {
                    null, is AppsWorkspace.State.Initializing -> flowOf(State.Initializing)
                    is AppsWorkspace.State.Error -> flowOf(State.Error(wsState.error))
                    is AppsWorkspace.State.Ready -> combine(
                        flowOf(wsState),
                        searchQueryFlow,
                        dialogStateFlow,
                    ) { readyState, searchQuery, dialogState ->
                        val actions = buildAvailableActions(readyState)
                        State.Ready(
                            apps = readyState.filteredApps,
                            searchQuery = searchQuery,
                            viewStyle = readyState.viewStyle,
                            filterConfig = readyState.filterConfig,
                            sortSettings = readyState.sortSettings,
                            selectedAppIds = readyState.selectedAppIds,
                            hasRoot = readyState.hasRoot,
                            hasAdb = readyState.hasAdb,
                            isLoading = readyState.isLoading,
                            isRefreshing = readyState.isRefreshing,
                            error = readyState.error,
                            dialogState = dialogState,
                            availableActions = actions,
                        )
                    }
                }
            }
        }
    }

    private fun buildAvailableActions(wsState: AppsWorkspace.State.Ready): List<AppsActionBarItem> {
        return if (wsState.selectedApps.isNotEmpty()) {
            buildSelectionActions(wsState)
        } else {
            buildDefaultActions(wsState)
        }
    }

    private fun buildSelectionActions(wsState: AppsWorkspace.State.Ready): List<AppsActionBarItem> {
        val selectedApps = wsState.selectedApps
        return buildList {
            add(AppsActionBarItem.OpenInTab(selectedApps))

            if (selectedApps.size != wsState.filteredApps.size && wsState.filteredApps.isNotEmpty()) {
                add(AppsActionBarItem.SelectAll)
            }

            if (wsState.canEnableDisable) {
                val disableAction = AppsActionBarItem.Disable(selectedApps)
                if (disableAction.isVisible) add(disableAction)

                val enableAction = AppsActionBarItem.Enable(selectedApps)
                if (enableAction.isVisible) add(enableAction)
            }

            if (wsState.canClearCache) {
                add(AppsActionBarItem.ClearCache(selectedApps))
            }

            if (wsState.canClearData) {
                add(AppsActionBarItem.ClearData(selectedApps))
            }

            add(AppsActionBarItem.Uninstall(selectedApps))
            add(AppsActionBarItem.ExportApk(selectedApps))

            val shareAction = AppsActionBarItem.Share(selectedApps)
            if (shareAction.isVisible) add(shareAction)
        }
    }

    private fun buildDefaultActions(wsState: AppsWorkspace.State.Ready): List<AppsActionBarItem> {
        return buildList {
            add(AppsActionBarItem.Refresh)
            add(AppsActionBarItem.Sort)

            val toggledViewStyle = when (wsState.viewStyle) {
                is AppsViewStyle.List -> AppsViewStyle.Grid()
                is AppsViewStyle.Grid -> AppsViewStyle.List()
            }
            add(AppsActionBarItem.UpdateViewStyle(toggledViewStyle))
        }
    }

    private fun onAppLongClick(item: AppItem) = launch {
        log(tag) { "onAppLongClick(${item.packageName})" }
        toggleAppSelection(item.pkg.installId)
    }

    private suspend fun toggleAppSelection(installId: InstallId) {
        val workspace = getWorkspace()
        val readyState = workspaceReadyState.filterNotNull().first()
        val isSelected = installId in readyState.selectedAppIds
        workspace.selectApp(installId, !isSelected)
    }

    fun onSearchQueryChanged(query: TextFieldValue) = launch {
        log(tag, DEBUG) { "Search query changed: ${query.text}" }
        searchQueryFlow.value = query
        getWorkspace().updateSearchQuery(query.text)
    }

    fun onFilterChanged(filterConfig: TagFilterConfig) = launch {
        log(tag) { "Filter changed: $filterConfig" }
        getWorkspace().updateFilterConfig(filterConfig)
        appsSettings.defaultFilterConfig.value(filterConfig)
    }

    private fun onFilterTagRemoved(appTag: AppTag, isExcluded: Boolean) = launch {
        log(tag) { "Removing filter tag: $appTag (excluded: $isExcluded)" }
        val currentConfig = workspaceReadyState.filterNotNull().first().filterConfig
        val newConfig = if (isExcluded) {
            currentConfig.copy(excludeTags = currentConfig.excludeTags - appTag)
        } else {
            currentConfig.copy(includeTags = currentConfig.includeTags - appTag)
        }
        getWorkspace().updateFilterConfig(newConfig)
        appsSettings.defaultFilterConfig.value(newConfig)
    }

    fun onSortSettingsChanged(sortSettings: SortSettings) = launch {
        log(tag) { "Sort settings changed: $sortSettings" }
        getWorkspace().updateSortSettings(sortSettings)
        appsSettings.defaultSortSettings.value(sortSettings)
    }

    fun onClearSelection() = launch {
        log(tag) { "Clearing selection" }
        getWorkspace().clearSelection()
    }

    fun onSelectAll() = launch {
        log(tag) { "Selecting all" }
        getWorkspace().selectAll()
    }

    fun onSelectUserApps() = launch {
        val ids = getReadyState().apps.filter { !it.isSystemApp }.map { it.pkg.installId }.toSet()
        log(tag) { "Selecting ${ids.size} user apps" }
        getWorkspace().selectApps(ids)
    }

    fun onSelectSystemApps() = launch {
        val ids = getReadyState().apps.filter { it.isSystemApp }.map { it.pkg.installId }.toSet()
        log(tag) { "Selecting ${ids.size} system apps" }
        getWorkspace().selectApps(ids)
    }

    fun onRefresh() = launch {
        log(tag) { "Refreshing apps" }
        getWorkspace().refresh()
    }

    private suspend fun launchApp(pkgId: Pkg.Id) {
        log(tag) { "Launching app: ${pkgId.name}" }
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkgId.name)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                log(tag, WARN) { "No launch intent for ${pkgId.name}" }
            }
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to launch ${pkgId.name}: $e" }
        }
    }

    fun openAppInfo(pkgId: Pkg.Id) = launch {
        log(tag) { "Opening app info: ${pkgId.name}" }
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:${pkgId.name}".toUri()
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to open app info for ${pkgId.name}: $e" }
        }
    }

    fun showAppDetails(app: AppItem) = launch {
        log(tag) { "Showing app details (modal): ${app.packageName}" }
        workspaceRemote.createAndFocus(
            type = Workspace.Type.APP_DETAILS,
            arguments = AppDetailsArguments(
                packageName = app.packageName,
                callerWorkspaceId = id,
            )
        )
    }

    fun openAppDetailsInTab(app: AppItem) = launch {
        log(tag) { "Opening app details in tab: ${app.packageName}" }
        workspaceRemote.createAndFocus(
            type = Workspace.Type.APP_DETAILS,
            arguments = AppDetailsArguments(
                packageName = app.packageName,
                callerWorkspaceId = null,
            )
        )
    }

    fun showFilterDialog() = launch {
        log(tag) { "Showing filter dialog" }
        val currentState = getReadyState()

        val userProfileTags = currentState.apps
            .filter { it.userProfile.handle.handleId != 0 }
            .map { AppTag.User(it.userProfile.handle.handleId, it.userProfile.label) }
            .distinctBy { it.handleId }

        @Suppress("USELESS_CAST")
        val availableTags = (AppTag.standardTags + userProfileTags)
            .filterNotNull()

        dialogStateFlow.value = AppsDialogState.FilterOptions(
            availableTags = availableTags,
        )
    }

    fun showSortDialog() = launch {
        log(tag) { "Showing sort dialog" }
        val currentState = getReadyState()
        dialogStateFlow.value = AppsDialogState.SortOptions(currentState.sortSettings)
    }

    fun dismissDialog() = launch {
        log(tag) { "Dismissing dialog" }
        dialogStateFlow.value = AppsDialogState.None
    }

    fun performEnableApps(apps: List<AppItem>) = launch {
        log(tag) { "Enabling ${apps.size} apps" }
        dismissDialog()
        getWorkspace().enableApps(apps)
    }

    fun performDisableApps(apps: List<AppItem>) = launch {
        log(tag) { "Disabling ${apps.size} apps" }
        dismissDialog()
        getWorkspace().disableApps(apps)
    }

    fun performUninstallApps(apps: List<AppItem>) = launch {
        log(tag) { "Uninstalling ${apps.size} apps" }
        dismissDialog()
        getWorkspace().uninstallApps(apps)
    }

    fun performClearCacheApps(apps: List<AppItem>) = launch {
        log(tag) { "Clearing cache for ${apps.size} apps" }
        dismissDialog()
        getWorkspace().clearCacheApps(apps)
    }

    fun performClearDataApps(apps: List<AppItem>) = launch {
        log(tag) { "Clearing data for ${apps.size} apps" }
        dismissDialog()
        getWorkspace().clearDataApps(apps)
    }

    fun closeWorkspace() = launch {
        log(tag) { "Closing workspace" }
        workspaceRemote.execute(WorkspaceAction.Close(id))
    }

    /**
     * Unified handler for all page-level actions.
     * Dispatches to appropriate methods based on action type.
     */
    fun onPageAction(action: AppsPageAction) {
        log(tag, INFO) { "onPageAction(): $action" }

        when (action) {
            // Workspace lifecycle
            is AppsPageAction.Workspace.ShareError -> { /* Handled globally by WorkspaceMapper */ }
            is AppsPageAction.Workspace.Close -> closeWorkspace()

            // Search
            is AppsPageAction.Search.UpdateQuery -> onSearchQueryChanged(action.query)

            // Filter chips
            is AppsPageAction.Filter.OpenDialog -> showFilterDialog()
            is AppsPageAction.Filter.RemoveTag -> onFilterTagRemoved(action.tag, action.isExcluded)

            // App interactions
            is AppsPageAction.Apps.Refresh -> onRefresh()
            is AppsPageAction.Apps.Click -> handleAppClick(action.app)
            is AppsPageAction.Apps.LongClick -> onAppLongClick(action.app)

            // Selection
            is AppsPageAction.Selection.Clear -> onClearSelection()
            is AppsPageAction.Selection.SelectUserApps -> onSelectUserApps()
            is AppsPageAction.Selection.SelectSystemApps -> onSelectSystemApps()

            // Dialog
            is AppsPageAction.Dialog.Dismiss -> dismissDialog()
            is AppsPageAction.Dialog.ApplyFilter -> onFilterChanged(action.config)
            is AppsPageAction.Dialog.ApplySort -> {
                dialogStateFlow.value = AppsDialogState.None
                onSortSettingsChanged(action.settings)
            }
            is AppsPageAction.Dialog.ConfirmEnable -> performEnableApps(action.apps)
            is AppsPageAction.Dialog.ConfirmDisable -> performDisableApps(action.apps)
            is AppsPageAction.Dialog.ConfirmUninstall -> performUninstallApps(action.apps)
            is AppsPageAction.Dialog.ConfirmClearCache -> performClearCacheApps(action.apps)
            is AppsPageAction.Dialog.ConfirmClearData -> performClearDataApps(action.apps)

            // Action bar clicks
            is AppsPageAction.ActionBarClick -> onActionBarClick(action.item)
        }
    }

    /**
     * Handles app click with conditional logic (multi-select mode check).
     */
    private fun handleAppClick(app: AppItem) = launch {
        log(tag) { "handleAppClick(${app.packageName})" }
        val currentState = getReadyState()
        if (currentState.isMultiSelectMode) {
            toggleAppSelection(app.pkg.installId)
        } else {
            showAppDetails(app)
        }
    }

    private fun onActionBarClick(action: AppsActionBarItem) {
        log(tag) { "onActionBarClick: ${action.javaClass.simpleName}" }

        when (action) {
            is AppsActionBarItem.SelectAll -> onSelectAll()
            is AppsActionBarItem.DeselectAll -> onClearSelection()
            is AppsActionBarItem.Refresh -> onRefresh()
            is AppsActionBarItem.Sort -> showSortDialog()
            is AppsActionBarItem.Filter -> showFilterDialog()

            is AppsActionBarItem.Launch -> launch {
                launchApp(action.app.pkg.id)
            }

            is AppsActionBarItem.OpenInfo -> {
                openAppInfo(action.app.pkg.id)
            }

            is AppsActionBarItem.Disable -> launch {
                log(tag) { "Disable action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmDisable(action.apps)
            }

            is AppsActionBarItem.Enable -> launch {
                log(tag) { "Enable action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmEnable(action.apps)
            }

            is AppsActionBarItem.Uninstall -> launch {
                log(tag) { "Uninstall action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmUninstall(action.apps)
            }

            is AppsActionBarItem.ClearCache -> launch {
                log(tag) { "Clear cache action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmClearCache(action.apps)
            }

            is AppsActionBarItem.ClearData -> launch {
                log(tag) { "Clear data action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmClearData(action.apps)
            }

            is AppsActionBarItem.ExportApk -> launch {
                log(tag) { "Export APK action for ${action.apps.size} apps" }
                val apkUris = action.apps.mapNotNull { app ->
                    (app.pkg as? SourceAvailable)?.sourceDir?.path?.let { "file://$it" }
                }
                if (apkUris.isNotEmpty()) {
                    workspaceRemote.createAndFocus(
                        type = Workspace.Type.SAVER,
                        arguments = SaverArguments.Default(
                            sourceUris = apkUris,
                            callerPackage = null,
                            callerWorkspaceId = id,
                        ),
                    )
                    getWorkspace().clearSelection()
                } else {
                    log(tag, WARN) { "No APK source paths available for export" }
                }
            }

            is AppsActionBarItem.Share -> launch {
                log(tag) { "Share action for ${action.apps.size} apps" }
                val shareText = action.apps.joinToString("\n\n") { app ->
                    buildString {
                        val version = app.versionName ?: app.versionCode.toString()
                        append("- **${app.label.get(context)}** (${app.packageName}) v$version")

                        app.installerInfo?.installer?.let { installer ->
                            val appStore = installer as? AppStore
                            val url = appStore?.urlGenerator?.invoke(app.pkg.id)
                            append("\n  Source: ")
                            if (url != null) {
                                append("[${installer.label?.get(context) ?: installer.id.name}]($url)")
                            } else {
                                append(installer.label?.get(context) ?: installer.id.name)
                            }
                        }
                    }
                }

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(
                    Intent.createChooser(intent, null).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )

                getWorkspace().clearSelection()
            }

            is AppsActionBarItem.OpenInTab -> launch {
                log(tag) { "Opening ${action.apps.size} apps in tabs" }
                action.apps.forEach { app ->
                    openAppDetailsInTab(app)
                }
                getWorkspace().clearSelection()
            }

            is AppsActionBarItem.BrowsePath -> launch {
                log(tag) { "Browse path action for ${action.app.packageName}: ${action.path}" }
                workspaceRemote.createAndFocus(
                    type = Workspace.Type.EXPLORER,
                    arguments = ExplorerArguments.Default(startPath = action.path),
                )
            }

            is AppsActionBarItem.UpdateViewStyle -> launch {
                getWorkspace().updateViewStyle(action.viewStyle)
                appsSettings.defaultViewStyle.value(action.viewStyle)
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): AppsWorkspaceViewModel
    }
}
