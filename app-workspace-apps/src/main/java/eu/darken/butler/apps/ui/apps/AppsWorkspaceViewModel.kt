package eu.darken.butler.apps.ui.apps

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.text.input.TextFieldValue
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.apps.core.AppsSettings
import eu.darken.butler.apps.core.AppsViewStyle
import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.core.arguments.AppDetailsArguments
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.core.engine.AppTag
import eu.darken.butler.apps.core.engine.AppsState
import eu.darken.butler.apps.core.engine.SortSettings
import eu.darken.butler.apps.core.engine.TagFilterConfig
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogState
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.datastore.valueBlocking
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.AppStore
import eu.darken.butler.common.pkgs.features.SourceAvailable
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.saver.core.arguments.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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

    private val searchQueryFlow = MutableStateFlow(TextFieldValue(""))
    private val viewStyleFlow = MutableStateFlow<AppsViewStyle>(appsSettings.defaultViewStyle.valueBlocking)
    private val dialogStateFlow = MutableStateFlow<AppsDialogState>(AppsDialogState.None)

    data class State(
        val appsState: AppsState = AppsState(),
        val searchQuery: TextFieldValue = TextFieldValue(""),
        val viewStyle: AppsViewStyle = AppsViewStyle.default(),
        val isLoading: Boolean = true,
        val dialogState: AppsDialogState = AppsDialogState.None,
        val availableActions: List<AppsAction> = emptyList(),
    ) {
        val apps: List<AppItem>
            get() = appsState.filteredApps

        val filterConfig: TagFilterConfig
            get() = appsState.filterConfig

        val sortSettings: SortSettings
            get() = appsState.sortSettings

        val selectedAppIds: Set<String>
            get() = appsState.selectedAppIds

        val selectedApps: List<AppItem>
            get() = apps.filter { it.packageName in selectedAppIds }

        val isMultiSelectMode: Boolean
            get() = appsState.isMultiSelectMode

        val selectionCount: Int
            get() = selectedAppIds.size

        val userAppsCount: Int
            get() = apps.count { !it.isSystemApp }

        val systemAppsCount: Int
            get() = apps.count { it.isSystemApp }
    }

    val state: Flow<State> = combine(
        workspaceSource.filterNotNull().flatMapLatest { it.state },
        workspaceSource.filterNotNull().flatMapLatest { it.appsEngine.state },
        searchQueryFlow,
        viewStyleFlow,
        dialogStateFlow,
    ) { workspaceState, appsState, searchQuery, viewStyle, dialogState ->
        // Calculate available actions based on selection state
        val actions = if (appsState.selectedAppIds.isNotEmpty()) {
            val selectedApps = appsState.filteredApps.filter { it.packageName in appsState.selectedAppIds }
            buildList {
                // Open in Tab - primary action for selections
                add(AppsAction.OpenInTab(selectedApps))

                // Select All
                if (appsState.selectedAppIds.size != appsState.filteredApps.size && appsState.filteredApps.isNotEmpty()) {
                    add(AppsAction.SelectAll)
                }

                // Disable/Enable/ClearCache/ClearData only if elevated access (root/Shizuku) is available
                if (workspaceState.hasElevatedAccess) {
                    // Disable (only if all selected apps are enabled)
                    val disableAction = AppsAction.Disable(selectedApps)
                    if (disableAction.isVisible) {
                        add(disableAction)
                    }

                    // Enable (only if any selected apps are disabled)
                    val enableAction = AppsAction.Enable(selectedApps)
                    if (enableAction.isVisible) {
                        add(enableAction)
                    }

                    // Clear Cache
                    add(AppsAction.ClearCache(selectedApps))

                    // Clear Data
                    add(AppsAction.ClearData(selectedApps))
                }

                // Uninstall
                add(AppsAction.Uninstall(selectedApps))

                // Export APK
                add(AppsAction.ExportApk(selectedApps))

                // Share (only if reasonable number)
                val shareAction = AppsAction.Share(selectedApps)
                if (shareAction.isVisible) {
                    add(shareAction)
                }
            }
        } else {
            buildList {
                add(AppsAction.Refresh)
                add(AppsAction.Sort)
                add(AppsAction.Filter)
                // View style toggle - shows the CURRENT style, clicking switches to the other
                val toggledViewStyle = when (viewStyle) {
                    is AppsViewStyle.List -> AppsViewStyle.Grid()
                    is AppsViewStyle.Grid -> AppsViewStyle.List()
                }
                add(AppsAction.UpdateViewStyle(toggledViewStyle))
            }
        }

        State(
            appsState = appsState,
            searchQuery = searchQuery,
            viewStyle = viewStyle,
            isLoading = appsState.isLoading,
            dialogState = dialogState,
            availableActions = actions,
        )
    }

    fun onAppClick(item: AppItem) = launch {
        log(tag) { "onAppClick(${item.packageName})" }
        if (state.first().isMultiSelectMode) {
            toggleAppSelection(item.packageName)
        } else {
            launchApp(item.pkg.id)
        }
    }

    fun onAppLongClick(item: AppItem) = launch {
        log(tag) { "onAppLongClick(${item.packageName})" }
        toggleAppSelection(item.packageName)
    }

    private suspend fun toggleAppSelection(packageName: String) {
        val workspace = getWorkspace()
        val currentState = workspace.appsEngine.state.first()
        val isSelected = packageName in currentState.selectedAppIds
        workspace.appsEngine.selectApp(packageName, !isSelected)
    }

    fun onSearchQueryChanged(query: TextFieldValue) = launch {
        log(tag, DEBUG) { "Search query changed: ${query.text}" }
        searchQueryFlow.value = query
        getWorkspace().appsEngine.updateSearchQuery(query.text)
    }

    fun onFilterChanged(filterConfig: TagFilterConfig) = launch {
        log(tag) { "Filter changed: $filterConfig" }
        getWorkspace().appsEngine.updateFilterConfig(filterConfig)
        appsSettings.defaultFilterConfig.value(filterConfig)
    }

    fun onSortSettingsChanged(sortSettings: SortSettings) = launch {
        log(tag) { "Sort settings changed: $sortSettings" }
        getWorkspace().appsEngine.updateSortSettings(sortSettings)
        appsSettings.defaultSortSettings.value(sortSettings)
    }

    fun onClearSelection() = launch {
        log(tag) { "Clearing selection" }
        getWorkspace().appsEngine.clearSelection()
    }

    fun onSelectAll() = launch {
        log(tag) { "Selecting all" }
        getWorkspace().appsEngine.selectAll()
    }

    fun onRefresh() = launch {
        log(tag) { "Refreshing apps" }
        getWorkspace().appsEngine.refresh()
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
                data = android.net.Uri.parse("package:${pkgId.name}")
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
                callerWorkspaceId = null,  // No caller = opens as tab
            )
        )
    }

    fun showFilterDialog() = launch {
        log(tag) { "Showing filter dialog" }
        val currentState = state.first()

        // Build available tags from standard tags + user profile tags from the app list
        val userProfileTags = currentState.apps
            .filter { it.userProfile.handle.handleId != 0 }
            .map { AppTag.User(it.userProfile.handle.handleId, it.userProfile.label) }
            .distinctBy { it.handleId }

        // Defensive: filter out any potential nulls that might sneak in through R8/reflection
        @Suppress("USELESS_CAST")
        val availableTags = (AppTag.standardTags + userProfileTags)
            .filterNotNull()

        dialogStateFlow.value = AppsDialogState.FilterOptions(
            currentFilter = currentState.filterConfig,
            availableTags = availableTags,
        )
    }

    fun showSortDialog() = launch {
        log(tag) { "Showing sort dialog" }
        val currentState = state.first()
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

    fun onAction(action: AppsAction) {
        log(tag) { "Executing action: ${action.javaClass.simpleName}" }

        when (action) {
            is AppsAction.SelectAll -> onSelectAll()
            is AppsAction.DeselectAll -> onClearSelection()
            is AppsAction.Refresh -> onRefresh()
            is AppsAction.Sort -> showSortDialog()
            is AppsAction.Filter -> showFilterDialog()

            is AppsAction.Launch -> launch {
                launchApp(action.app.pkg.id)
            }

            is AppsAction.OpenInfo -> {
                openAppInfo(action.app.pkg.id)
            }

            is AppsAction.Disable -> launch {
                log(tag) { "Disable action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmDisable(action.apps)
            }

            is AppsAction.Enable -> launch {
                log(tag) { "Enable action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmEnable(action.apps)
            }

            is AppsAction.Uninstall -> launch {
                log(tag) { "Uninstall action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmUninstall(action.apps)
                // TODO: Implement actual uninstall operation
            }

            is AppsAction.ClearCache -> launch {
                log(tag) { "Clear cache action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmClearCache(action.apps)
                // TODO: Implement actual clear cache operation
            }

            is AppsAction.ClearData -> launch {
                log(tag) { "Clear data action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmClearData(action.apps)
                // TODO: Implement actual clear data operation
            }

            is AppsAction.ExportApk -> launch {
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
                        ),
                    )
                    // Clear selection after initiating export
                    getWorkspace().appsEngine.clearSelection()
                } else {
                    log(tag, WARN) { "No APK source paths available for export" }
                }
            }

            is AppsAction.Share -> launch {
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

                getWorkspace().appsEngine.clearSelection()
            }

            is AppsAction.OpenInTab -> launch {
                log(tag) { "Opening ${action.apps.size} apps in tabs" }
                action.apps.forEach { app ->
                    openAppDetailsInTab(app)
                }
                // Clear selection after opening
                getWorkspace().appsEngine.clearSelection()
            }

            is AppsAction.BrowsePath -> launch {
                log(tag) { "Browse path action for ${action.app.packageName}: ${action.path}" }
                workspaceRemote.createAndFocus(
                    type = Workspace.Type.EXPLORER,
                    arguments = ExplorerArguments.Default(startPath = action.path),
                )
            }

            is AppsAction.UpdateViewStyle -> {
                viewStyleFlow.value = action.viewStyle
                launch {
                    appsSettings.defaultViewStyle.value(action.viewStyle)
                }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): AppsWorkspaceViewModel
    }
}
