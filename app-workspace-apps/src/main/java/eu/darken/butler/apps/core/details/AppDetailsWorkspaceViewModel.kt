package eu.darken.butler.apps.core.details

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.apps.core.arguments.DetailTab
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@HiltViewModel(assistedFactory = AppDetailsWorkspaceViewModel.Factory::class)
class AppDetailsWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    @Assisted arguments: Workspace.Arguments?,
    @ApplicationContext private val context: Context,
    dispatchers: DispatcherProvider,
    navController: NavigationController,
    workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
) : ViewModel4(dispatchers, logTag("AppDetails", "Workspace", id.shortTag, "Page"), navController) {

    private val workspaceSource: Flow<AppDetailsWorkspace?> =
        workspaceProvider.retrieve(id)
            .map { workspace: Workspace<out Workspace.Arguments>? -> workspace as? AppDetailsWorkspace }

    private suspend fun getWorkspace(): AppDetailsWorkspace = workspaceSource.filterNotNull().first()

    val state: Flow<AppDetailsWorkspace.State> = workspaceSource
        .filterNotNull()
        .flatMapLatest { it.state }

    fun onTabSelected(tab: DetailTab) = launch {
        log(tag) { "Tab selected: $tab" }
        getWorkspace().updateSelectedTab(tab)
    }

    fun onLaunchApp(app: AppInfo) = launch {
        log(tag) { "Launching app: ${app.packageName}" }
        launchApp(app.pkgId)
    }

    fun onShowAppInfo(app: AppInfo) {
        log(tag) { "Opening app info: ${app.packageName}" }
        openAppInfo(app.pkgId)
    }

    fun onBrowsePath(path: eu.darken.butler.common.files.APath<*>) = launch {
        log(tag) { "Browsing path: $path" }
        workspaceRemote.createAndFocus(
            type = Workspace.Type.EXPLORER,
            arguments = ExplorerArguments.Default(startPath = path),
        )
    }

    fun onUninstall(app: AppInfo) = launch {
        log(tag) { "Uninstalling app: ${app.packageName}" }
        // TODO: Implement uninstall operation
        log(tag, WARN) { "Uninstall not implemented yet" }
    }

    fun onExportApk(app: AppInfo) = launch {
        log(tag) { "Exporting APK: ${app.packageName}" }
        // TODO: Implement APK export
        log(tag, WARN) { "Export APK not implemented yet" }
    }

    fun onShareApk(app: AppInfo) = launch {
        log(tag) { "Sharing APK: ${app.packageName}" }
        // TODO: Implement APK sharing
        log(tag, WARN) { "Share APK not implemented yet" }
    }

    fun onEnableDisable(app: AppInfo) = launch {
        log(tag) { "Toggle enable/disable: ${app.packageName}, current=${app.isEnabled}" }
        // TODO: Implement enable/disable operation
        log(tag, WARN) { "Enable/disable not implemented yet" }
    }

    fun close() = launch {
        log(tag) { "Closing app details workspace" }
        workspaceRemote.execute(eu.darken.butler.workspace.core.WorkspaceAction.Close(id))
    }

    private fun launchApp(pkgId: Pkg.Id) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkgId.name)
        if (launchIntent != null) {
            context.startActivity(launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } else {
            log(tag, WARN) { "No launch intent found for: ${pkgId.name}" }
        }
    }

    private fun openAppInfo(pkgId: Pkg.Id) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:${pkgId.name}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id, arguments: Workspace.Arguments?): AppDetailsWorkspaceViewModel
    }
}
