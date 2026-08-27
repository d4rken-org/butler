package eu.darken.butler.apps.core.details

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.AppPath
import eu.darken.butler.apps.core.AppSizeCache
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.apps.core.details.components.ComponentToggleAvailability
import eu.darken.butler.apps.core.details.components.ComponentToggleState
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import eu.darken.butler.common.pkgs.features.SourceAvailable
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.pkgs.pkgops.PkgOpsException
import eu.darken.butler.common.pkgs.pkgs
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceDisplay
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.isPausableAsChild
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.core.stateInWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

class AppDetailsWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: AppDetailsArguments,
    @ApplicationContext private val context: Context,
    dispatcherProvider: DispatcherProvider,
    private val pkgRepo: PkgRepo,
    private val pkgOps: PkgOps,
    private val apkArchiveParser: ApkArchiveParser,
    private val appSizeCache: AppSizeCache,
    private val gatewaySwitch: GatewaySwitch,
    private val pathPermissionCheck: PathPermissionCheck,
    private val rootManager: RootManager,
    private val adbManager: AdbManager,
    private val workspaceRemote: WorkspaceRemote,
) : Workspace<AppDetailsArguments> {

    private val tag = logTag("AppDetails", "Workspace", id.shortTag)
    private val scope = CoroutineScope(dispatcherProvider.IO + CoroutineName(tag))

    private val args = creationArguments
    override val type: Workspace.Type = Workspace.Type.APP_DETAILS

    // Cached separately because `args` is immutable: the captured label has to survive into every
    // later createArguments() call, not just the copy handed to the serializer.
    @Volatile private var cachedAppLabel: String? = creationArguments.appLabel

    override suspend fun createArguments(): AppDetailsArguments {
        // Two callers with opposite needs:
        // - As a modal (callerWorkspaceId set) this is only ever captured by a pause of the owning
        //   tab, which the user expects to come back exactly as they left it - including the sub-tab.
        //   Modals are never session-saved, so keeping it cannot leak into a restore.
        // - As a tab this IS what session save persists, and the Components sub-screen is transient
        //   navigation state, so a restored workspace always reopens on Overview regardless of where
        //   the user navigated.
        val tabToKeep = if (args.callerWorkspaceId != null) selectedTabFlow.value else DetailTab.OVERVIEW
        return args.copy(
            initialTab = tabToKeep,
            appLabel = cachedAppLabel,
        )
    }

    private val selectedTabFlow = MutableStateFlow(args.initialTab)
    private var wasAppSeen = false

    // Fetch app info from package manager; shared so `state` and `info` collect pkgs() once.
    // Plain stateIn (not shareLatest): null is a legitimate value meaning "package gone".
    private val appInfoFlow: StateFlow<AppInfo?> = pkgRepo.pkgs().map { pkgs ->
        val pkg = pkgs.firstOrNull { it.installId == args.installId } ?: return@map null

        AppInfo(
            install = pkg,
        )
    }
        .catch { error ->
            log(tag, ERROR) { "Failed to resolve app info: ${error.asLog()}" }
            emit(null)
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    // Declared after appInfoFlow on purpose: it reads that flow, so it has to see the shared one.
    private val packageInfoLoader = PackageInfoLoader(
        scope = scope,
        appInfo = appInfoFlow,
        load = { app -> loadPackageInfo(app) },
    )

    /**
     * Primary source is the installed-package query: re-parsing only `sourceDir` would miss the
     * manifests of split APKs. The file fallback covers packages the local PackageManager cannot
     * see. Known limitation: [PkgOps.queryPkg]'s local path ignores `userHandle` and always queries
     * the current user, which is exactly the case the sourceDir fallback picks up.
     */
    private suspend fun loadPackageInfo(app: AppInfo): PackageInfoState = try {
        // A throwing query is the same situation as one that found nothing: the fallback still has
        // to run, otherwise a package the local PackageManager chokes on reads as unavailable.
        val primary = try {
            pkgOps.queryPkg(
                pkgName = app.id,
                flags = apkArchiveParser.queryFlags().toLong(),
                userHandle = app.installId.userHandle,
            )?.let { apkArchiveParser.map(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "Package query failed for ${app.packageName}: ${e.asLog()}" }
            null
        }
        // The fallback renders neither label nor icon - the toolbar already shows the app identity.
        val info = primary
            ?: (app.install as? SourceAvailable)?.sourceDir
                ?.let { apkArchiveParser.parseFile(it, includeIcon = false) }
        when (info) {
            null -> PackageInfoState.Unavailable
            else -> PackageInfoState.Ready(info)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(tag, WARN) { "Failed to load package info for ${app.packageName}: ${e.asLog()}" }
        PackageInfoState.Unavailable
    }

    // Declared after appInfoFlow on purpose: it probes that flow, so it has to see the shared one.
    private val componentToggleAvailability = ComponentToggleAvailability(
        scope = scope,
        appInfo = appInfoFlow,
        rootManager = rootManager,
        adbManager = adbManager,
        ownPackageName = context.packageName,
    )

    private val _sizeLoading = MutableStateFlow(false)

    private data class StorageCandidate(
        val path: APath<*>,
        val label: CaString,
        /**
         * Only paths visible from every mount namespace may be withheld. The root host launches
         * without mount-master, so its view of emulated storage does not carry other apps'
         * `Android/data` and a stat there answers false for an app that does have external data.
         */
        val mayBeWithheld: Boolean = false,
    )

    /** What the row for a path needs, but only I/O can answer. */
    private data class PathInsight(
        val availability: Availability = Availability.UNKNOWN,
        val requirements: PathRequirements? = null,
    )

    private enum class Availability {
        EXISTS,
        ABSENT,

        /** Nothing was learned; the row is offered and the Explorer reports whatever it finds. */
        UNKNOWN,
    }

    // Fixed for the workspace's whole life: its install identity is immutable.
    // TODO These are user 0's directories while the install carries a user handle, so a
    //  work-profile install points at the wrong ones.
    private val storageCandidates: List<StorageCandidate> = listOf(
        StorageCandidate(
            path = LocalPath.build("/data/data/${args.packageName}"),
            label = R.string.apps_path_internal_data_label.toCaString(),
            mayBeWithheld = true,
        ),
        StorageCandidate(
            path = LocalPath.build("/storage/emulated/0/Android/data/${args.packageName}"),
            label = R.string.apps_path_external_data_label.toCaString(),
        ),
    )

    private val isPrimaryUser = args.installId.userHandle.handleId == 0

    /**
     * Resolved once per root state instead of inside the state combine, which re-runs on every
     * emission of its inputs. A pause releases the workspace, so a resume rebuilds this from
     * scratch and a directory created in the meantime (external app storage is created lazily)
     * shows up again.
     */
    private val pathInsights: StateFlow<Map<APath<*>, PathInsight>> = rootManager.useRoot
        .distinctUntilChanged()
        .flatMapLatest { hasRoot ->
            flow {
                // The first render must not wait for I/O, and no knowledge means "offer the row".
                emit(emptyMap<APath<*>, PathInsight>())
                val known = storageCandidates.associate { candidate ->
                    candidate.path to PathInsight(availability = resolveAvailability(candidate, hasRoot))
                }
                emit(known)
                // Setup tracking can stall on a module that never resolves, so it is published on
                // its own and cannot hold back what is already known.
                emit(known.mapValues { (path, insight) -> insight.copy(requirements = resolveRequirements(path)) })
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * [eu.darken.butler.common.files.FileSystemOps.exists] answers false both for "not there" and
     * for "could not look" - a denied stat returns false without throwing - so a false answer is
     * only worth trusting with root, and only for the user these paths belong to.
     *
     * TODO A strict IO-layer probe reporting present/absent/could-not-tell per backend would say
     *  which of the two a false answer is, which is what withholding the external row again needs.
     */
    private suspend fun resolveAvailability(candidate: StorageCandidate, hasRoot: Boolean): Availability {
        val path = candidate.path
        if (!candidate.mayBeWithheld) return Availability.UNKNOWN
        if (!hasRoot || !isPrimaryUser) return Availability.UNKNOWN
        return try {
            val exists = gatewaySwitch.exists(path)
            currentCoroutineContext().ensureActive()
            if (exists) Availability.EXISTS else Availability.ABSENT
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed probe says nothing; absent has to be a positive answer.
            log(tag, WARN) { "Existence check failed for $path: ${e.asLog()}" }
            Availability.UNKNOWN
        }
    }

    private suspend fun resolveRequirements(path: APath<*>): PathRequirements? = try {
        pathPermissionCheck.monitor(path).first().also { currentCoroutineContext().ensureActive() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(tag, WARN) { "Requirement check failed for $path: ${e.asLog()}" }
        null
    }

    data class State(
        val app: AppInfo? = null,
        val selectedTab: DetailTab = DetailTab.OVERVIEW,
        val availablePaths: List<AppPath> = emptyList(),
        val isLoading: Boolean = true,
        val isLoadingSize: Boolean = false,
        val sizesAvailable: Boolean = true,
        val callerWorkspaceId: Workspace.Id? = null,
        val hasRoot: Boolean = false,
        val hasAdb: Boolean = false,
        val componentToggleState: ComponentToggleState = ComponentToggleState.UNSUPPORTED,
        val packageInfo: PackageInfoState = PackageInfoState.Loading,
    ) {
        val canEnableDisable: Boolean get() = hasRoot || hasAdb
        val canForceStop: Boolean get() = hasRoot || hasAdb
        val canClearData: Boolean get() = hasRoot || hasAdb
    }

    val state: Flow<State> = combine(
        appInfoFlow,
        selectedTabFlow,
        rootManager.useRoot,
        adbManager.useAdb,
        componentToggleAvailability.state.filterNotNull(),
        appSizeCache.snapshot,
        appSizeCache.isAvailable,
        _sizeLoading,
        packageInfoLoader.state,
        pathInsights,
    ) { app, selectedTab, hasRoot, hasAdb, componentToggleState, sizeSnapshot, sizesAvailable, isLoadingSize,
        packageInfo, insights ->
        val withSize = app?.let { info ->
            val size = sizeSnapshot.sizes[info.installId] ?: return@let info
            info.copy(
                appSize = size.appBytes,
                dataSize = size.dataBytes,
                cacheSize = size.cacheBytes,
            )
        }
        val paths = if (withSize != null) buildAppPaths(insights) else emptyList()
        State(
            selectedTab = selectedTab,
            app = withSize,
            availablePaths = paths,
            isLoading = withSize == null,
            isLoadingSize = isLoadingSize,
            sizesAvailable = sizesAvailable,
            callerWorkspaceId = args.callerWorkspaceId,
            hasRoot = hasRoot,
            hasAdb = hasAdb,
            componentToggleState = componentToggleState,
            packageInfo = packageInfo,
        )
    }

    // Same derivation the factory hands the paused stand-in, so both name this tab identically.
    // The live tab enriches this to the app label once package data resolves.
    private val seedDisplay = deriveAppDetailsDisplay(args)

    /**
     * Number of package operations (enable/disable/uninstall/clear/component toggle) currently
     * running. Package operations don't go through OperationsManager, so this is the only signal
     * that keeps a pause from releasing the workspace mid-operation.
     */
    private val pkgOpsInFlight = MutableStateFlow(0)

    private suspend fun <T> trackPkgOp(block: suspend () -> T): T {
        pkgOpsInFlight.update { it + 1 }
        try {
            return block()
        } finally {
            pkgOpsInFlight.update { it - 1 }
        }
    }

    override val info: StateFlow<Workspace.Info> = combine(
        appInfoFlow,
        selectedTabFlow,
        pkgOpsInFlight,
    ) { app, _, opsInFlight ->
        val label = normalizedAppLabel(app?.label?.get(context), args.packageName)
        Workspace.Info(
            id = id,
            type = type,
            title = label?.toCaString() ?: seedDisplay.title ?: type.label,
            subtitle = label?.let { args.packageName.toCaString() },
            lifecycleState = Workspace.LifecycleState.Ready,
            operationCount = 0,
            attentionCount = 0,
            isPausable = opsInFlight == 0,
            callerWorkspaceId = args.callerWorkspaceId,
            modalPresentation = args.modalPresentation,
            // Built by hand instead of via initialInfo(), so the relationship fields have to be
            // carried explicitly - a missing one here silently reads as "not pausable with my owner"
            pausableAsChild = args.isPausableAsChild,
        )
    }.stateInWorkspace(
        scope = scope,
        initial = initialInfo(
            title = seedDisplay.title ?: type.label,
            subtitle = seedDisplay.subtitle,
            arguments = args,
        ),
    )

    private fun buildAppPaths(insights: Map<APath<*>, PathInsight>): List<AppPath> = storageCandidates
        .mapNotNull { candidate ->
            val insight = insights[candidate.path] ?: PathInsight()
            // A row for a directory that is not there only leads to a failed navigation.
            if (insight.availability == Availability.ABSENT) return@mapNotNull null
            AppPath(
                path = candidate.path,
                label = candidate.label,
                requirement = insight.requirements?.let { requirementLabel(it) },
            )
        }

    /** Null while access is available, including through an existing SAF grant or the SAF picker. */
    private fun requirementLabel(requirements: PathRequirements): CaString? {
        if (!requirements.needsSetup) return null
        // Combos are alternatives, so single-module ones read as "either of these".
        val alternatives = requirements.combos.takeIf { combos -> combos.all { it.size == 1 } }?.flatten()?.toSet()
        return when (alternatives) {
            setOf(SetupModule.Type.ROOT) -> R.string.apps_path_requires_root_label
            setOf(SetupModule.Type.SHIZUKU) -> R.string.apps_path_requires_shizuku_label
            setOf(SetupModule.Type.ROOT, SetupModule.Type.SHIZUKU) ->
                R.string.apps_path_requires_root_or_shizuku_label
            else -> R.string.apps_path_requires_access_label
        }.toCaString()
    }

    fun updateSelectedTab(tab: DetailTab) {
        log(tag) { "Tab selected: $tab" }
        selectedTabFlow.value = tab
        // Every entry re-runs the load, which is also the retry after a transient Unavailable.
        if (tab == DetailTab.PACKAGE_INFO) packageInfoLoader.onRequested()
    }

    // Package operations live here, not on the ViewModel, so pkgOpsInFlight actually covers them.
    // Exceptions propagate to the caller, which owns error surfacing and any fallback.

    suspend fun uninstallApp(app: AppInfo): Boolean = trackPkgOp {
        log(tag) { "uninstallApp(${app.packageName})" }
        pkgOps.uninstall(app.installId)
    }

    suspend fun forceStopApp(app: AppInfo): Boolean = trackPkgOp {
        log(tag) { "forceStopApp(${app.packageName})" }
        pkgOps.forceStop(app.id)
    }

    suspend fun clearDataApp(app: AppInfo): Boolean = trackPkgOp {
        log(tag) { "clearDataApp(${app.packageName})" }
        pkgOps.clearData(app.installId)
    }

    suspend fun setAppEnabled(app: AppInfo, enabled: Boolean) = trackPkgOp {
        log(tag) { "setAppEnabled(${app.packageName}, enabled=$enabled)" }
        pkgOps.changePackageState(app.id, enabled = enabled)
    }

    suspend fun setComponentsEnabled(entries: List<ComponentEntry>, enabled: Boolean) = trackPkgOp {
        log(tag) { "setComponentsEnabled(${entries.size} components, enabled=$enabled)" }
        val failures = mutableListOf<Pair<ComponentEntry, Exception>>()
        entries.forEach { entry ->
            try {
                pkgOps.changeComponentState(entry.packageName.toPkgId(), entry.className, enabled = enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(tag, WARN) { "Failed to set ${entry.className} to enabled=$enabled: $e" }
                failures.add(entry to e)
            }
        }
        if (failures.isNotEmpty()) {
            val verb = if (enabled) "enable" else "disable"
            throw PkgOpsException(
                "Failed to $verb ${failures.size}/${entries.size} components",
                failures.first().second,
            )
        }
    }

    override suspend fun release() {
        log(tag, INFO) { "Releasing AppDetailsWorkspace: $id" }
        scope.cancel()
    }

    init {
        log(tag, INFO) { "AppDetailsWorkspace initialized: $id, package=${args.packageName}" }

        // A paused modal comes back on the sub-tab it was left on, and that entry never goes
        // through updateSelectedTab - without this the route would stay on its spinner forever.
        if (args.initialTab == DetailTab.PACKAGE_INFO) packageInfoLoader.onRequested()

        // Auto-close when the package is removed (e.g. after uninstall)
        // Only close after app was seen at least once (to avoid closing during initial load)
        appInfoFlow
            .onEach { appInfo ->
                // Only ever upgrades: a gone package must not erase the cached label.
                normalizedAppLabel(appInfo?.label?.get(context), args.packageName)
                    ?.let { cachedAppLabel = it }

                if (appInfo != null) {
                    wasAppSeen = true
                } else if (wasAppSeen) {
                    log(tag, INFO) { "Package ${args.packageName} removed, auto-closing workspace" }
                    workspaceRemote.execute(WorkspaceAction.Close(id))
                }
            }
            .launchIn(scope)

        // Keyed on "not yet attempted at the current revision", not on the install id: a details
        // workspace's id never changes, so an id-keyed trigger would fire once and the card would
        // stay empty forever after any invalidation.
        scope.launch {
            combine(
                appInfoFlow,
                appSizeCache.snapshot,
                appSizeCache.isAvailable,
            ) { app, snapshot, isAvailable ->
                Triple(app, snapshot, isAvailable)
            }.collectLatest { (app, snapshot, _) ->
                if (app == null) return@collectLatest
                // Ahead of the attempted-check, not inside resolve(): once a size has been measured
                // this collector returns early forever, so a permission revoked afterwards would
                // never be re-derived and the card would keep showing numbers Android no longer
                // updates instead of the setup block. isAvailable stays in the combine so the
                // flip re-triggers here; the flag itself is deliberately not a gate, which would
                // latch the screen off for the whole process.
                appSizeCache.refreshAvailability()
                if (app.installId in snapshot.attempted) return@collectLatest
                log(tag) { "Resolving size for ${app.packageName}" }
                _sizeLoading.value = true
                try {
                    appSizeCache.resolve(listOf(app.install))
                } finally {
                    _sizeLoading.value = false
                }
            }
        }
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<AppDetailsArguments> {
        override fun create(id: Workspace.Id, arguments: AppDetailsArguments): AppDetailsWorkspace

        override val argumentsSerializer: KSerializer<AppDetailsArguments> get() = serializer()

        override fun deriveDisplay(arguments: AppDetailsArguments): WorkspaceDisplay =
            deriveAppDetailsDisplay(arguments)
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.APP_DETAILS)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}
