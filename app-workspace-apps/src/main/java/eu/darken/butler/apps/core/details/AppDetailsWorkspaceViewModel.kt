package eu.darken.butler.apps.core.details

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.apkExportFileName
import eu.darken.butler.apps.core.details.components.AppComponentsController
import eu.darken.butler.apps.core.details.components.AppComponentsLoader
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentToggleState
import eu.darken.butler.apps.core.operations.PackageCommand
import eu.darken.butler.apps.ui.details.AppDetailsConfirmRequest
import eu.darken.butler.apps.ui.details.components.ComponentsActionBarItem
import eu.darken.butler.apps.ui.details.components.ComponentsConfirmRequest
import eu.darken.butler.apps.ui.operations.PackageOperationUiController
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.navigation.Nav
import eu.darken.butler.common.navigation.destSetup
import eu.darken.butler.common.pkgs.features.AppStore
import eu.darken.butler.common.pkgs.features.SourceAvailable
import eu.darken.butler.common.pkgs.isEnabled
import eu.darken.butler.common.ui.ViewModel4
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.saver.SaverArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceProvider
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.createAndFocus
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationFocusRequest
import eu.darken.butler.workspace.ui.page.WorkspacePageChrome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@HiltViewModel(assistedFactory = AppDetailsWorkspaceViewModel.Factory::class)
class AppDetailsWorkspaceViewModel @AssistedInject constructor(
    @Assisted private val id: Workspace.Id,
    @ApplicationContext private val context: Context,
    dispatchers: DispatcherProvider,
    workspaceProvider: WorkspaceProvider,
    private val workspaceRemote: WorkspaceRemote,
    componentsLoader: AppComponentsLoader,
    chromeFactory: WorkspacePageChrome.Factory,
    operationFocusRequest: OperationFocusRequest,
) : ViewModel4(dispatchers, logTag("AppDetails", "Workspace", id.shortTag, "Page")) {

    private val chrome = chromeFactory.create(id, vmScope)

    val operationsUi = PackageOperationUiController(
        workspaceId = id,
        chrome = chrome,
        operationFocusRequest = operationFocusRequest,
        scope = vmScope,
        tag = tag,
    )

    val shareIntentEvent = chrome.shareIntentEvent
    val pendingErrorShare = chrome.pendingErrorShare

    fun confirmErrorShare() = chrome.confirmErrorShare()

    fun dismissErrorShare() = chrome.dismissErrorShare()

    private val workspaceSource: Flow<AppDetailsWorkspace?> =
        workspaceProvider.retrieve(id)
            .map { workspace: Workspace<out Workspace.Arguments>? -> workspace as? AppDetailsWorkspace }

    private suspend fun getWorkspace(): AppDetailsWorkspace = workspaceSource.filterNotNull().first()

    val state: Flow<AppDetailsWorkspace.State> = workspaceSource
        .filterNotNull()
        .flatMapLatest { it.state }

    private val componentsController = AppComponentsController(
        scope = vmScope,
        loader = componentsLoader,
    )

    val componentsState = componentsController.state
    val selectedComponent = componentsController.selectedComponent

    /**
     * Eagerly shared, like [selectedComponent]: a cold flow would drop the checkboxes and the bars
     * for a frame on rotation or a pane remount, and a Back press landing in that frame would
     * navigate away instead of clearing the selection.
     */
    val selectedComponentKeys: StateFlow<Set<String>> = componentsController.selectedComponents
        .map { entries -> entries.map { it.key }.toSet() }
        .stateIn(vmScope, SharingStarted.Eagerly, emptySet())

    val componentToggleState: StateFlow<ComponentToggleState> = state
        .map { it.componentToggleState }
        .distinctUntilChanged()
        .stateIn(vmScope, SharingStarted.Eagerly, ComponentToggleState.UNSUPPORTED)

    /** The items filter their own direction via `isVisible`, which is what `WorkspaceActionBar` expects. */
    val componentActions: StateFlow<List<ComponentsActionBarItem>> = combine(
        componentsController.selectedComponents,
        componentToggleState,
    ) { selection, toggleState ->
        if (selection.isEmpty() || toggleState != ComponentToggleState.AVAILABLE) {
            emptyList()
        } else {
            listOf(
                ComponentsActionBarItem.Disable(selection),
                ComponentsActionBarItem.Enable(selection),
            )
        }
    }.stateIn(vmScope, SharingStarted.Eagerly, emptyList())

    private val componentConfirmFlow = MutableStateFlow<ComponentsConfirmRequest?>(null)

    // Assigned, not `.asStateFlow()`: ViewModel2 declares a member extension of that name which
    // shadows the kotlinx one inside every subclass and returns a plain Flow.
    val componentConfirm: StateFlow<ComponentsConfirmRequest?> = componentConfirmFlow

    private val appConfirmFlow = MutableStateFlow<AppDetailsConfirmRequest?>(null)

    // Assigned for the same reason as componentConfirm above.
    val appConfirm: StateFlow<AppDetailsConfirmRequest?> = appConfirmFlow

    init {
        // A "tap to resolve" notification routes here (see the controller).
        operationsUi.focusRequestHandler.launchInViewModel()
        operationsUi.issueRetireHandler.launchInViewModel()

        // Driven from the nullable source, not from `state`: that one filters the absent workspace
        // away and would never emit, leaving the controller holding data, a live selection and a
        // running load in a ViewModel that outlives the pane.
        workspaceSource
            .flatMapLatest { workspace -> workspace?.state ?: flowOf<AppDetailsWorkspace.State?>(null) }
            .onEach { workspaceState ->
                componentsController.onAppChanged(workspaceState?.app)
                componentsController.onComponentsRouteActive(workspaceState?.selectedTab == DetailTab.COMPONENTS)
                retireStaleAppConfirm(workspaceState)
            }
            .launchInViewModel()

        // A pending batch dialog must not outlive the selection it was raised for: onAppChanged
        // (package update) and route changes clear the controller's keys without touching this flow.
        componentsController.selectedComponents
            .onEach { live ->
                val pending = componentConfirmFlow.value ?: return@onEach
                if (live.mapTo(mutableSetOf()) { it.key } != pending.entries.mapTo(mutableSetOf()) { it.key }) {
                    componentConfirmFlow.value = null
                }
            }
            .launchInViewModel()
    }

    fun onComponentSelected(entry: ComponentEntry) {
        log(tag) { "onComponentSelected(${entry.key})" }
        componentsController.onItemClick(entry)
    }

    fun onComponentSelectionChanged(keys: Set<String>) {
        log(tag) { "onComponentSelectionChanged(${keys.size} keys)" }
        componentsController.setSelection(keys)
    }

    fun clearComponentSelection() {
        log(tag) { "clearComponentSelection()" }
        componentsController.clearSelection()
    }

    fun onComponentSheetDismissed() {
        log(tag) { "onComponentSheetDismissed()" }
        componentsController.dismiss()
    }

    /** Batch actions are always confirmed; the single-component toggle in the sheet is not. */
    fun onComponentAction(item: ComponentsActionBarItem) {
        log(tag) { "onComponentAction($item)" }
        componentConfirmFlow.value = when (item) {
            is ComponentsActionBarItem.Enable -> ComponentsConfirmRequest(item.entries, enable = true)
            is ComponentsActionBarItem.Disable -> ComponentsConfirmRequest(item.entries, enable = false)
        }
    }

    fun onComponentConfirm(request: ComponentsConfirmRequest) = launch {
        val live = componentsController.selectedComponents.value
        componentConfirmFlow.value = null
        if (live.mapTo(mutableSetOf()) { it.key } != request.entries.mapTo(mutableSetOf()) { it.key }) {
            log(tag, WARN) { "onComponentConfirm(): selection changed under the dialog, ignoring" }
            return@launch
        }
        log(tag) { "onComponentConfirm(${live.size} components, enable=${request.enable})" }
        applyComponentState(live, request.enable)
    }

    fun onComponentConfirmDismiss() {
        log(tag) { "onComponentConfirmDismiss()" }
        componentConfirmFlow.value = null
    }

    fun onSetComponentEnabled(entry: ComponentEntry, enabled: Boolean) = launch {
        log(tag) { "onSetComponentEnabled(${entry.key}, enabled=$enabled)" }
        applyComponentState(listOf(entry), enabled)
    }

    fun openElevatedAccessSetup() {
        log(tag) { "openElevatedAccessSetup()" }
        navTo(
            Nav.Main.destSetup(
                typeFilter = setOf(SetupModule.Type.ROOT, SetupModule.Type.SHIZUKU),
                showCompleted = true,
            )
        )
    }

    private suspend fun applyComponentState(entries: List<ComponentEntry>, enabled: Boolean) {
        val app = state.first().app ?: return
        val managed = submit(
            PackageCommand.SetComponents(
                target = PackageCommand.Target(app.installId, app.label),
                entries = entries,
                enabled = enabled,
            )
        ) ?: return
        componentsController.clearSelection()
        // Completed is the one terminal state of this framework - success, failure and cancellation
        // all arrive as it - so the list is reloaded after every outcome.
        managed.state.first { it is Operation.State.Completed }
        componentsController.refresh()
    }

    fun onTabSelected(tab: DetailTab) = launch {
        log(tag) { "Tab selected: $tab" }
        getWorkspace().updateSelectedTab(tab)
    }

    fun onRetryAppInfo() = launch {
        log(tag) { "onRetryAppInfo()" }
        try {
            getWorkspace().refreshPackageData()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swallowed on purpose: a failed retry arrives on the screen through the state flow as
            // the same error card, so raising a dialog on top of it would say it twice.
            log(tag, WARN) { "onRetryAppInfo() failed: ${e.asLog()}" }
        }
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
            sourceWorkspaceId = id,
        )
    }

    fun onUninstall(app: AppInfo) = launch {
        val hasElevatedAccess = state.first().let { it.hasRoot || it.hasAdb }
        if (!hasElevatedAccess) {
            // Android's dialog IS the confirmation on this path, and which path to take is decided
            // here, once: re-reading root/ADB inside the operation a moment later would let a flip
            // to available run an elevated uninstall that nobody ever confirmed.
            log(tag) { "onUninstall(${app.packageName}): no elevated access, using the system dialog" }
            submit(PackageCommand.Uninstall(listOf(app.asTarget()), viaSystemDialog = true))
            return@launch
        }
        log(tag) { "onUninstall(${app.packageName}): asking for confirmation" }
        appConfirmFlow.value = AppDetailsConfirmRequest.Uninstall(app)
    }

    /** A pending dialog must not outlive its target: the package can vanish or change under it. */
    private fun retireStaleAppConfirm(workspaceState: AppDetailsWorkspace.State?) {
        val pending = appConfirmFlow.value ?: return
        val live = (workspaceState?.appState as? AppInfoState.Ready)?.info
        if (live == null || live.installId != pending.app.installId) {
            log(tag, WARN) { "Retiring pending confirmation, its target is gone" }
            appConfirmFlow.value = null
        }
    }

    fun onAppConfirm(request: AppDetailsConfirmRequest) = launch {
        // Consumed atomically before dispatching, so a duplicated callback cannot act twice.
        if (!appConfirmFlow.compareAndSet(request, null)) {
            log(tag, WARN) { "onAppConfirm($request): request is no longer pending, ignoring" }
            return@launch
        }
        log(tag) { "onAppConfirm($request)" }
        when (request) {
            is AppDetailsConfirmRequest.ClearData ->
                submit(PackageCommand.ClearData(listOf(request.app.asTarget())))

            is AppDetailsConfirmRequest.Uninstall ->
                submit(PackageCommand.Uninstall(listOf(request.app.asTarget()), viaSystemDialog = false))
        }
    }

    fun onAppConfirmDismiss() {
        log(tag) { "onAppConfirmDismiss()" }
        appConfirmFlow.value = null
    }

    fun onExportApk(app: AppInfo) = launch {
        log(tag) { "Exporting APK: ${app.packageName}" }
        val apkUri = (app.install as? SourceAvailable)?.sourceDir?.path?.let { "file://$it" }
        if (apkUri != null) {
            workspaceRemote.createAndFocus(
                type = Workspace.Type.SAVER,
                arguments = SaverArguments.Default(
                    sourceUris = listOf(apkUri),
                    sourceNames = listOf(
                        apkExportFileName(
                            label = app.label.get(context),
                            packageName = app.packageName,
                            versionName = app.versionName,
                            versionCode = app.versionCode,
                        )
                    ),
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
        val targets = listOf(app.asTarget())
        submit(if (app.isEnabled) PackageCommand.Disable(targets) else PackageCommand.Enable(targets))
    }

    fun onLaunchComponent(packageName: String, className: String) {
        log(tag) { "Launching activity: $className" }
        try {
            val intent = Intent().apply {
                component = ComponentName(packageName, className)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Exported doesn't guarantee launchable (permission-protected/disabled) — surface it.
            log(tag, WARN) { "Failed to launch activity $className: ${e.asLog()}" }
            errorEvents.emitBlocking(
                IllegalStateException(context.getString(R.string.apps_components_launch_failed), e),
            )
        }
    }

    fun onForceStop(app: AppInfo) = launch {
        log(tag) { "Force stopping: ${app.packageName}" }
        submit(PackageCommand.ForceStop(listOf(app.asTarget())))
    }

    private fun AppInfo.asTarget() = PackageCommand.Target(installId, label)

    private suspend fun submit(command: PackageCommand) = getWorkspace().submit(command)

    /** Always confirmed: there is no undo, and no system dialog stands between tap and wipe. */
    fun onClearData(app: AppInfo) {
        log(tag) { "onClearData(${app.packageName}): asking for confirmation" }
        appConfirmFlow.value = AppDetailsConfirmRequest.ClearData(app)
    }

    fun onOpenSizePermissionSetup() = launch {
        log(tag) { "Opening setup for usage access" }
        navTo(
            Nav.Main.destSetup(
                typeFilter = setOf(SetupModule.Type.USAGE_STATS),
                satisfyingCombos = setOf(setOf(SetupModule.Type.USAGE_STATS)),
                autoCloseWhenComplete = true,
            )
        )
    }

    fun close() = launch {
        log(tag) { "Closing app details workspace" }
        workspaceRemote.execute(eu.darken.butler.workspace.core.WorkspaceAction.Close(id))
    }

    override fun onCleared() {
        operationsUi.onCleared()
        super.onCleared()
    }

    @AssistedFactory
    interface Factory {
        fun create(id: Workspace.Id): AppDetailsWorkspaceViewModel
    }
}
