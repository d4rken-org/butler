package eu.darken.butler.apps.core.details

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.apps.R
import eu.darken.butler.common.ElevatedAccessUnavailableException
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.features.AppStore
import eu.darken.butler.common.pkgs.features.SourceAvailable
import eu.darken.butler.common.pkgs.isEnabled
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.saver.SaverArguments
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
    @ApplicationContext private val context: Context,
    dispatchers: DispatcherProvider,
    workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
    private val pkgOps: PkgOps,
) : ViewModel4(dispatchers, logTag("AppDetails", "Workspace", id.shortTag, "Page")) {

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
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            context.startActivity(launchIntent.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } else {
            log(tag, WARN) { "No launch intent found for: ${app.packageName}" }
        }
    }

    fun onShowAppInfo(app: AppInfo) {
        log(tag) { "Opening app info: ${app.packageName}" }
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${app.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
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
        try {
            pkgOps.uninstall(app.installId)
            // Don't close here — auto-close in AppDetailsWorkspace handles it reactively
        } catch (e: Exception) {
            val isElevatedUnavailable = generateSequence<Throwable>(e) { it.cause }
                .any { it is ElevatedAccessUnavailableException }
            if (isElevatedUnavailable) {
                log(tag) { "Elevated access unavailable, falling back to system uninstall intent" }
                val intent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:${app.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                throw e
            }
        }
    }

    fun onExportApk(app: AppInfo) = launch {
        log(tag) { "Exporting APK: ${app.packageName}" }
        val apkUri = (app.install as? SourceAvailable)?.sourceDir?.path?.let { "file://$it" }
        if (apkUri != null) {
            workspaceRemote.createAndFocus(
                type = Workspace.Type.SAVER,
                arguments = SaverArguments.Default(
                    sourceUris = listOf(apkUri),
                    callerPackage = null,
                    callerWorkspaceId = id,
                ),
            )
        } else {
            log(tag, WARN) { "No APK source path available for: ${app.packageName}" }
        }
    }

    fun onShareApk(app: AppInfo) = launch {
        log(tag) { "Sharing app info: ${app.packageName}" }
        val shareText = buildString {
            val version = app.versionName ?: app.versionCode.toString()
            append("- **${app.label.get(context)}** (${app.packageName}) v$version")

            app.installerInfo?.installer?.let { installer ->
                val appStore = installer as? AppStore
                val url = appStore?.urlGenerator?.invoke(app.id)
                append("\n  Source: ")
                if (url != null) {
                    append("[${installer.label?.get(context) ?: installer.id.name}]($url)")
                } else {
                    append(installer.label?.get(context) ?: installer.id.name)
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
    }

    fun onEnableDisable(app: AppInfo) = launch {
        log(tag) { "Toggle enable/disable: ${app.packageName}, current=${app.install.isEnabled}" }
        pkgOps.changePackageState(app.id, enabled = !app.isEnabled)
    }

    fun onLaunchActivity(activityInfo: ActivityInfo) {
        log(tag) { "Launching activity: ${activityInfo.name}" }
        try {
            val intent = Intent().apply {
                component = ComponentName(activityInfo.packageName, activityInfo.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Exported doesn't guarantee launchable (permission-protected/disabled) — surface it.
            log(tag, WARN) { "Failed to launch activity ${activityInfo.name}: ${e.asLog()}" }
            errorEvents.emitBlocking(
                IllegalStateException(context.getString(R.string.apps_components_launch_failed), e),
            )
        }
    }

    fun onForceStop(app: AppInfo) = launch {
        log(tag) { "Force stopping: ${app.packageName}" }
        pkgOps.forceStop(app.id)
    }

    fun onClearCache(app: AppInfo) = launch {
        log(tag) { "Clearing cache: ${app.packageName}" }
        pkgOps.clearCache(app.installId)
    }

    fun onClearData(app: AppInfo) = launch {
        log(tag) { "Clearing data: ${app.packageName}" }
        pkgOps.clearData(app.installId)
    }

    fun close() = launch {
        log(tag) { "Closing app details workspace" }
        workspaceRemote.execute(eu.darken.butler.workspace.core.WorkspaceAction.Close(id))
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): AppDetailsWorkspaceViewModel
    }
}
