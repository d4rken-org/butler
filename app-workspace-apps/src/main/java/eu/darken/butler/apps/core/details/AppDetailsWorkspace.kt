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
import eu.darken.butler.apps.core.details.components.ComponentToggleAvailability
import eu.darken.butler.apps.core.details.components.ComponentToggleState
import eu.darken.butler.apps.core.operations.PackageActionOperation
import eu.darken.butler.apps.core.operations.PackageCommand
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.flow.combine
import eu.darken.butler.common.pkgs.PkgRepo
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import eu.darken.butler.common.pkgs.features.SourceAvailable
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.permissions.core.PathPermissionCheck
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.contracts.apps.AppDetailsArguments
import eu.darken.butler.workspace.contracts.apps.DetailTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceDisplay
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.isPausableAsChild
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.current
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.toOperationCounts
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val operationsManager: OperationsManager,
    private val operationFactory: PackageActionOperation.Factory,
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

    /** Sub-tab and resolved label [createArguments] reports; the info carries neither. */
    override val restorableStateFingerprint: Any?
        get() = listOf(selectedTabFlow.value, cachedAppLabel)

    private val selectedTabFlow = MutableStateFlow(args.initialTab)

    // Fetch app info from package manager; shared so `state` and `info` collect the repo once.
    // Built from `data` rather than `pkgs()`: `catch` is terminal, so a source error would end the
    // flow for good and leave the workspace stuck with no way to retry. Reading `error` first keeps
    // `pkgs` - which throws while an error is set - out of reach until it cannot throw.
    // A gone package is a page state, not a close: the page shows an end state with a Close action
    // so an uninstall's receipt stays reachable on the tab that produced it.
    private val appInfoFlow: StateFlow<AppInfoState> = pkgRepo.data
        .map { data ->
            data.error?.let { error ->
                log(tag, ERROR) { "Failed to resolve app info: ${error.asLog()}" }
                return@map AppInfoState.SourceError(error)
            }
            val pkg = data.pkgs.firstOrNull { it.installId == args.installId }
            if (pkg == null) AppInfoState.Gone else AppInfoState.Ready(AppInfo(install = pkg))
        }
        .stateIn(scope, SharingStarted.Eagerly, AppInfoState.Loading)

    /** For consumers that only ever cared about the app itself; anything else reads as null. */
    private val appInfoOrNull: StateFlow<AppInfo?> = appInfoFlow
        .map { (it as? AppInfoState.Ready)?.info }
        .stateIn(scope, SharingStarted.Eagerly, null)

    // Declared after appInfoFlow on purpose: it reads that flow, so it has to see the shared one.
    private val packageInfoLoader = PackageInfoLoader(
        scope = scope,
        appInfo = appInfoOrNull,
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
        appInfo = appInfoOrNull,
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
     * An absence is only trusted with root, and only for the user these paths belong to.
     *
     * TODO Withholding the external row needs more than a strict probe: from API 30 on the probe
     *  escalates to the privileged host, which stats in its own mount namespace and launches
     *  without mount-master, so an ABSENT under `Android/data` says nothing about the app.
     */
    private suspend fun resolveAvailability(candidate: StorageCandidate, hasRoot: Boolean): Availability {
        val path = candidate.path
        if (!candidate.mayBeWithheld) return Availability.UNKNOWN
        if (!hasRoot || !isPrimaryUser) return Availability.UNKNOWN
        return try {
            val existence = gatewaySwitch.existsStrict(path)
            currentCoroutineContext().ensureActive()
            when (existence) {
                Existence.PRESENT -> Availability.EXISTS
                Existence.ABSENT -> Availability.ABSENT
                // A probe that could not tell says nothing; absent has to be a positive answer.
                Existence.UNKNOWN -> Availability.UNKNOWN
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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

    /**
     * What this workspace's own operations add up to, recomputed on every state change.
     *
     * A snapshot rather than the operation list itself: [withOnlyStateChanges] re-emits the SAME
     * list instance when an operation changes state, so a StateFlow of that list would conflate
     * every transition away and freeze the counts.
     *
     * Declared before [state] and [info], which read it: properties initialise in declaration order.
     */
    private data class OwnOps(
        val unfinished: Int,
        val active: Int,
        /** Component toggles excluded: they never block an app-wide action, or each other. */
        val unfinishedAppWide: Int,
        val attention: Int,
    )

    private val ownOps: StateFlow<OwnOps> = operationsManager.operationsForWorkspace(id)
        .withOnlyStateChanges()
        .map { operations ->
            val counts = operations.toOperationCounts()
            // Its own pass: the app-wide scope is this workspace's, not something the shared
            // derivation knows about.
            val unfinishedAppWide = operations.count { operation ->
                operation.metadata.kind != Operation.Metadata.Kind.COMPONENTS &&
                    operation.state.value !is Operation.State.Completed
            }
            OwnOps(
                unfinished = counts.unfinished,
                active = counts.active,
                unfinishedAppWide = unfinishedAppWide,
                attention = counts.attention,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, OwnOps(0, 0, 0, 0))

    /**
     * Rejects a second app-wide package action while one is running. The rows going disabled is
     * feedback only - two taps can both be delivered before a recomposition, so this is what
     * actually stops the second one. The check and the submit share a lock, otherwise two callers
     * could both pass the check before either had submitted.
     *
     * Component toggles stay out of it: they have their own selection and confirmation flow, and
     * one lock across both would let a component toggle block an uninstall.
     */
    private val submitLock = Mutex()

    suspend fun submit(command: PackageCommand): ManagedOperation? = submitLock.withLock {
        if (command !is PackageCommand.SetComponents) {
            val running = operationsManager.current().filter {
                it.metadata.origin.workspaceId == id &&
                    it.isUnfinished &&
                    it.metadata.kind != Operation.Metadata.Kind.COMPONENTS
            }
            if (running.isNotEmpty()) {
                log(tag, WARN) { "submit($command) rejected, another package action is still running" }
                return@withLock null
            }
        }
        log(tag) { "submit($command)" }
        operationsManager.submitManaged(
            operationFactory.create(
                actionOrigin = Operation.Metadata.Origin.Apps(id),
                command = command,
            )
        )
    }

    data class State(
        val appState: AppInfoState = AppInfoState.Loading,
        val selectedTab: DetailTab = DetailTab.OVERVIEW,
        val availablePaths: List<AppPath> = emptyList(),
        val isLoadingSize: Boolean = false,
        val sizesAvailable: Boolean = true,
        val callerWorkspaceId: Workspace.Id? = null,
        val hasRoot: Boolean = false,
        val hasAdb: Boolean = false,
        val componentToggleState: ComponentToggleState = ComponentToggleState.UNSUPPORTED,
        val packageInfo: PackageInfoState = PackageInfoState.Loading,
        val isPkgActionRunning: Boolean = false,
        /** Survives the package going away, so a gone page still names the app it was about. */
        val title: CaString = CaString.EMPTY,
    ) {
        val app: AppInfo? get() = (appState as? AppInfoState.Ready)?.info
        val isLoading: Boolean get() = appState is AppInfoState.Loading
        val isGone: Boolean get() = appState is AppInfoState.Gone
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
        ownOps,
    ) { appState, selectedTab, hasRoot, hasAdb, componentToggleState, sizeSnapshot, sizesAvailable, isLoadingSize,
        packageInfo, insights, ownOps ->
        val withSize = when (appState) {
            is AppInfoState.Ready -> when (val size = sizeSnapshot.sizes[appState.info.installId]) {
                null -> appState
                else -> AppInfoState.Ready(
                    appState.info.copy(
                        appSize = size.appBytes,
                        dataSize = size.dataBytes,
                        cacheSize = size.cacheBytes,
                    )
                )
            }

            else -> appState
        }
        val paths = if (withSize is AppInfoState.Ready) buildAppPaths(insights) else emptyList()
        State(
            selectedTab = selectedTab,
            appState = withSize,
            availablePaths = paths,
            isLoadingSize = isLoadingSize,
            sizesAvailable = sizesAvailable,
            callerWorkspaceId = args.callerWorkspaceId,
            hasRoot = hasRoot,
            hasAdb = hasAdb,
            componentToggleState = componentToggleState,
            packageInfo = packageInfo,
            isPkgActionRunning = ownOps.unfinishedAppWide > 0,
            title = (cachedAppLabel ?: args.packageName).toCaString(),
        )
    }

    // Same derivation the factory hands the paused stand-in, so both name this tab identically.
    // The live tab enriches this to the app label once package data resolves.
    private val seedDisplay = deriveAppDetailsDisplay(args)

    override val info: StateFlow<Workspace.Info> = combine(
        appInfoOrNull,
        selectedTabFlow,
        ownOps,
    ) { app, _, ownOps ->
        // Falls back to the cached label, so a package that is gone keeps the tab title and
        // subtitle it had while it was there.
        val label = normalizedAppLabel(app?.label?.get(context), args.packageName) ?: cachedAppLabel
        Workspace.Info(
            id = id,
            type = type,
            title = label?.toCaString() ?: seedDisplay.title ?: type.label,
            subtitle = label?.let { args.packageName.toCaString() },
            lifecycleState = Workspace.LifecycleState.Ready,
            operationCount = ownOps.unfinished,
            activeCount = ownOps.active,
            attentionCount = ownOps.attention,
            isPausable = ownOps.unfinished == 0,
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

    /** Retry after a [AppInfoState.SourceError]; the result arrives through [state]. */
    suspend fun refreshPackageData() {
        log(tag) { "refreshPackageData()" }
        pkgRepo.refresh()
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

        appInfoFlow
            .onEach { appState ->
                // Only ever upgrades: a gone package must not erase the cached label.
                normalizedAppLabel((appState as? AppInfoState.Ready)?.info?.label?.get(context), args.packageName)
                    ?.let { cachedAppLabel = it }
            }
            .launchIn(scope)

        // Keyed on "not yet attempted at the current revision", not on the install id: a details
        // workspace's id never changes, so an id-keyed trigger would fire once and the card would
        // stay empty forever after any invalidation.
        scope.launch {
            combine(
                appInfoOrNull,
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
