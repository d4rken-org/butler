package eu.darken.butler.apps.ui.apps

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.apps.core.AppsSettings
import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.apps.core.engine.AppsState
import eu.darken.butler.apps.core.engine.SortMode
import eu.darken.butler.apps.ui.apps.dialogs.AppsDialogState
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
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
    navController: NavigationController,
    workspaceProvider: WorkspaceProvider,
    private val appsSettings: AppsSettings,
) : ViewModel4(dispatchers, logTag("Apps", "Workspace", id.shortTag, "Page"), navController) {

    private val workspaceSource: Flow<AppsWorkspace?> =
        workspaceProvider.retrieve(id).map { workspace: Workspace? -> workspace as? AppsWorkspace }

    private suspend fun getWorkspace(): AppsWorkspace = workspaceSource.filterNotNull().first()

    private val searchQueryFlow = MutableStateFlow("")
    private val viewModeFlow = MutableStateFlow(ViewMode.LIST)
    private val dialogStateFlow = MutableStateFlow<AppsDialogState>(AppsDialogState.None)

    data class State(
        val appsState: AppsState = AppsState(),
        val searchQuery: String = "",
        val viewMode: ViewMode = ViewMode.LIST,
        val isLoading: Boolean = true,
        val dialogState: AppsDialogState = AppsDialogState.None,
        val availableActions: List<AppsAction> = emptyList(),
    ) {
        val apps: List<AppItem>
            get() = appsState.filteredApps

        val filterConfig: AppsState.FilterConfig
            get() = appsState.filterConfig

        val sortMode: SortMode
            get() = appsState.sortMode

        val selectedAppIds: Set<String>
            get() = appsState.selectedAppIds

        val selectedApps: List<AppItem>
            get() = apps.filter { it.packageName in selectedAppIds }

        val isMultiSelectMode: Boolean
            get() = appsState.isMultiSelectMode

        val selectionCount: Int
            get() = selectedAppIds.size
    }

    val state: Flow<State> = combine(
        workspaceSource.filterNotNull().flatMapLatest { it.appsEngine.state },
        searchQueryFlow,
        viewModeFlow,
        dialogStateFlow,
    ) { appsState, searchQuery, viewMode, dialogState ->
        // Calculate available actions based on selection state
        val actions = if (appsState.selectedAppIds.isNotEmpty()) {
            val selectedApps = appsState.filteredApps.filter { it.packageName in appsState.selectedAppIds }
            buildList {
                // Select All / Deselect All
                if (appsState.selectedAppIds.size == appsState.filteredApps.size) {
                    add(AppsAction.DeselectAll)
                } else if (appsState.filteredApps.isNotEmpty()) {
                    add(AppsAction.SelectAll)
                }

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

                // Uninstall
                add(AppsAction.Uninstall(selectedApps))

                // Clear Cache
                add(AppsAction.ClearCache(selectedApps))

                // Clear Data
                add(AppsAction.ClearData(selectedApps))

                // Export APK
                add(AppsAction.ExportApk(selectedApps))

                // Share (only if reasonable number)
                val shareAction = AppsAction.Share(selectedApps)
                if (shareAction.isVisible) {
                    add(shareAction)
                }
            }
        } else {
            emptyList()
        }

        State(
            appsState = appsState,
            searchQuery = searchQuery,
            viewMode = viewMode,
            isLoading = appsState.isLoading,
            dialogState = dialogState,
            availableActions = actions,
        )
    }

    enum class ViewMode {
        LIST,
        GRID,
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

    fun onSearchQueryChanged(query: String) = launch {
        log(tag, DEBUG) { "Search query changed: $query" }
        searchQueryFlow.value = query
        getWorkspace().appsEngine.updateSearchQuery(query)
    }

    fun onFilterChanged(filterConfig: AppsState.FilterConfig) = launch {
        log(tag) { "Filter changed: $filterConfig" }
        getWorkspace().appsEngine.updateFilterConfig(filterConfig)
        appsSettings.defaultFilterConfig.value(filterConfig)
    }

    fun onSortModeChanged(sortMode: SortMode) = launch {
        log(tag) { "Sort mode changed: $sortMode" }
        getWorkspace().appsEngine.updateSortMode(sortMode)
        appsSettings.defaultSortMode.value(sortMode)
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
        log(tag) { "Showing app details: ${app.packageName}" }
        dialogStateFlow.value = AppsDialogState.AppDetails(app)
    }

    fun showFilterDialog() = launch {
        log(tag) { "Showing filter dialog" }
        val currentState = state.first()
        dialogStateFlow.value = AppsDialogState.FilterOptions(currentState.filterConfig)
    }

    fun showSortDialog() = launch {
        log(tag) { "Showing sort dialog" }
        val currentState = state.first()
        dialogStateFlow.value = AppsDialogState.SortOptions(currentState.sortMode)
    }

    fun dismissDialog() = launch {
        log(tag) { "Dismissing dialog" }
        dialogStateFlow.value = AppsDialogState.None
    }

    fun onAction(action: AppsAction) {
        log(tag) { "Executing action: ${action.javaClass.simpleName}" }

        when (action) {
            is AppsAction.SelectAll -> onSelectAll()
            is AppsAction.DeselectAll -> onClearSelection()
            is AppsAction.Refresh -> onRefresh()

            is AppsAction.Launch -> launch {
                launchApp(action.app.pkg.id)
            }

            is AppsAction.OpenInfo -> {
                openAppInfo(action.app.pkg.id)
            }

            is AppsAction.Disable -> launch {
                log(tag) { "Disable action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmDisable(action.apps)
                // TODO: Implement actual disable operation
            }

            is AppsAction.Enable -> launch {
                log(tag) { "Enable action for ${action.apps.size} apps" }
                dialogStateFlow.value = AppsDialogState.ConfirmEnable(action.apps)
                // TODO: Implement actual enable operation
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
                // TODO: Implement APK export
                log(tag, WARN) { "Export APK not implemented yet" }
            }

            is AppsAction.Share -> launch {
                log(tag) { "Share action for ${action.apps.size} apps" }
                // TODO: Implement APK sharing
                log(tag, WARN) { "Share APK not implemented yet" }
            }

            is AppsAction.BrowseData -> launch {
                log(tag) { "Browse data action for ${action.app.packageName}" }
                // TODO: Implement browse data (open Explorer at /data/data/package.name)
                log(tag, WARN) { "Browse data not implemented yet" }
            }

            is AppsAction.BrowseExternal -> launch {
                log(tag) { "Browse external action for ${action.app.packageName}" }
                // TODO: Implement browse external storage
                log(tag, WARN) { "Browse external storage not implemented yet" }
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): AppsWorkspaceViewModel
    }
}
