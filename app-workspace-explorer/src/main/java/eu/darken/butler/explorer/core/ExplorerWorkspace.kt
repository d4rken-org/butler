package eu.darken.butler.explorer.core

import dagger.Module
import dagger.Provides
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.error.ErrorIncident
import eu.darken.butler.common.error.ErrorIncidentFactory
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.explorer.core.engine.BrowsingEngine
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.explorer.core.operations.CompressOperation
import eu.darken.butler.explorer.core.operations.CopyOperation
import eu.darken.butler.explorer.core.operations.CreateOperation
import eu.darken.butler.explorer.core.operations.CreateTextFileOperation
import eu.darken.butler.explorer.core.operations.DeleteOperation
import eu.darken.butler.explorer.core.operations.ExplorerCommand
import eu.darken.butler.explorer.core.operations.ExplorerOperation
import eu.darken.butler.explorer.core.operations.DownloadLocalCopyOperation
import eu.darken.butler.explorer.core.operations.ExtractOperation
import eu.darken.butler.explorer.core.operations.RestoreOperation
import eu.darken.butler.explorer.core.operations.MoveOperation
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.explorer.PickerConfig
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceDisplay
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceTypeKey
import eu.darken.butler.workspace.core.initialInfo
import eu.darken.butler.workspace.core.label
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.awaitCompletion
import eu.darken.butler.workspace.core.operations.operationsForWorkspace
import eu.darken.butler.workspace.core.operations.submitAndGet
import eu.darken.butler.workspace.core.operations.withOnlyStateChanges
import eu.darken.butler.workspace.core.stateInWorkspace
import eu.darken.butler.workspace.core.tracker.PathAccessTracker
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer


class ExplorerWorkspace @AssistedInject constructor(
    @Assisted override val id: Workspace.Id,
    @Assisted private val creationArguments: ExplorerArguments,
    dispatcherProvider: DispatcherProvider,
    browsingEngineFactory: BrowsingEngine.Factory,
    fileSystemHinter: FileSystemHinter,
    private val pathAccessTracker: PathAccessTracker,
    private val issueHandler: IssueHandler,
    private val operationsManager: OperationsManager,
    private val deleteOperationFactory: DeleteOperation.Factory,
    private val createOperationFactory: CreateOperation.Factory,
    private val createTextFileOperationFactory: CreateTextFileOperation.Factory,
    private val copyOperationFactory: CopyOperation.Factory,
    private val moveOperationFactory: MoveOperation.Factory,
    private val compressOperationFactory: CompressOperation.Factory,
    private val extractOperationFactory: ExtractOperation.Factory,
    private val downloadLocalCopyOperationFactory: DownloadLocalCopyOperation.Factory,
    private val restoreOperationFactory: RestoreOperation.Factory,
    private val explorerSettings: ExplorerSettings,
    private val errorIncidentFactory: ErrorIncidentFactory,
) : Workspace<ExplorerArguments> {

    private val tag = logTag("Explorer", "Workspace", id.shortTag)

    override val type: Workspace.Type = Workspace.Type.EXPLORER

    private val scope = CoroutineScope(
        dispatcherProvider.IO + CoroutineName(tag) + CoroutineExceptionHandler { _, throwable ->
            log(tag, ERROR) { "Uncaught exception in workspace scope: ${throwable.asLog()}" }
            _state.value = State.Error(throwable)
        }
    )

    private val _state = MutableStateFlow<State>(State.Initializing)
    val state: Flow<State> = _state.asStateFlow()


    override suspend fun createArguments(): ExplorerArguments {
        // Both fields describe ONE location: the target the tab is on. Taking the path from the
        // loaded location instead would persist a half-updated pair whenever a save runs while a
        // navigation is in flight, cancelled or failed - the tab would reopen somewhere else.
        // Home/Device/Trash have no path, which is what startTarget captures.
        //
        // The creation arguments are handed back verbatim while the tab is still initializing or
        // after a failed load, so the reveal hint has to be stripped on that path too - a session
        // save landing in that window would persist it and re-highlight on every restore.
        val currentTarget = (_state.value as? State.Ready)?.currentTarget ?: return creationArguments.withoutReveal()
        return ExplorerArguments.Default(
            startPath = (currentTarget as? ExplorerNavigation.Target.Directory)?.path,
            startTarget = currentTarget.asStartTarget,
        )
    }

    /** The location [createArguments] reports; the tab's info publishes no content path. */
    override val restorableStateFingerprint: Any?
        get() = (_state.value as? State.Ready)?.currentTarget

    /**
     * The item to highlight once this tab has settled on the location it was created for, handed
     * out exactly ONCE.
     *
     * The page's ViewModel is rebuilt whenever the tab is recomposed into a pane, while this
     * instance lives on; re-reading the arguments there would highlight the file again long after
     * the user moved on.
     */
    fun consumeRevealHint(): RevealHint? = pendingReveal.getAndUpdate { null }

    /**
     * The pending hint WITHOUT taking it, so a page can learn what it will have to wait for and
     * still leave the hint for its successor if it is torn down before the listing arrives.
     * [consumeRevealHint] is the claim.
     */
    fun peekRevealHint(): RevealHint? = pendingReveal.value

    private val pendingReveal = MutableStateFlow(
        (creationArguments as? ExplorerArguments.Default)?.let { args ->
            val location = args.startPath
            val path = args.revealPath
            if (location != null && path != null) RevealHint(location = location, path = path) else null
        }
    )

    /** Highlight [path] once this tab is at [location]; both come from the creation arguments. */
    data class RevealHint(
        val location: APath<*>,
        val path: APath<*>,
    )

    private fun ExplorerArguments.withoutReveal(): ExplorerArguments = when {
        this is ExplorerArguments.Default && revealPath != null -> copy(revealPath = null)
        else -> this
    }

    private val browsingEngine = browsingEngineFactory.create(id, scope)

    // Picker configuration if this is a picker workspace
    val pickerConfig: PickerConfig? = (creationArguments as? ExplorerArguments.Picker)?.let {
        PickerConfig(
            callerWorkspaceId = it.callerWorkspaceId,
            selection = it.selection,
            requireWritable = it.requireWritable,
        )
    }

    // SaveAs filename state (only used when pickerConfig.selection is SaveAs)
    private val _saveAsFilename = MutableStateFlow(
        (pickerConfig?.selection as? PickerConfig.Selection.SaveAs)?.suggestedFilename ?: ""
    )
    val saveAsFilename: StateFlow<String> = _saveAsFilename.asStateFlow()

    fun updateSaveAsFilename(filename: String) {
        log(tag) { "updateSaveAsFilename($filename)" }
        _saveAsFilename.value = filename
    }

    // Same derivation the factory hands the paused stand-in, so both name this tab identically
    private val seedDisplay = deriveExplorerDisplay(creationArguments)


    override val info: StateFlow<Workspace.Info> = combine(
        _state,
        operationsManager.operationsForWorkspace(id).withOnlyStateChanges()
    ) { state, operations ->
        val states = operations.map { it.id to it.state.value }
        val activeOperations: Int = states.count { it.second !is Operation.State.Completed }
        val attentionCount: Int = states.count {
            val value = it.second
            if (value is Operation.State.Waiting) return@count true
            if (value is Operation.State.Completed && value.error != null && value.error !is CancellationException) return@count true
            return@count false
        }
        val readyState = state as? State.Ready
        // The seed stands in only until the tab knows where it is. Once it does, the target
        // describes itself completely: falling back per field would let a location that has no
        // second line keep the previous one - a tab titled "Home" still reading "Recover deleted
        // files" - and disagree with what a restore of it would show.
        val identity = readyState?.currentTarget?.display ?: seedDisplay
        Workspace.Info(
            id = id,
            type = type,
            title = identity?.title ?: type.label,
            subtitle = identity?.subtitle,
            lifecycleState = when (state) {
                is State.Initializing -> Workspace.LifecycleState.Initializing
                is State.Error -> Workspace.LifecycleState.Error(state.error)
                is State.Ready -> Workspace.LifecycleState.Ready
            },
            operationCount = activeOperations,
            attentionCount = attentionCount,
            callerWorkspaceId = pickerConfig?.callerWorkspaceId,
            modalPresentation = (creationArguments as? Workspace.ArgumentsWithCaller)?.modalPresentation
                ?: Workspace.ModalPresentationMode.PANE_LOCAL,
        )
    }.stateInWorkspace(
        scope = scope,
        initial = initialInfo(
            title = seedDisplay?.title ?: type.label,
            subtitle = seedDisplay?.subtitle,
            arguments = creationArguments,
        ),
    )


    private val navigationRequests = MutableSharedFlow<ExplorerNavigation>(replay = 1)

    /** Navigation state as it was when [target]'s content last settled: what a cancel rolls back to. */
    private data class StableNav(
        val target: ExplorerNavigation.Target,
        val historyIndex: Int,
        val navigationHistory: List<ExplorerNavigation.Target>,
    )

    @Volatile private var stableNav: StableNav? = null

    sealed interface State {
        data object Initializing : State

        data class Ready(
            val historyIndex: Int = 0,
            val navigationHistory: List<ExplorerNavigation.Target> = emptyList(),
            val currentTarget: ExplorerNavigation.Target? = null,
            val currentLocation: ExplorerLocation? = null,
            val currentBreadcrumbs: List<ExplorerBreadcrumb>? = null,
            /** The failed navigation, frozen when it failed rather than when the user shares it. */
            val errorIncident: ErrorIncident? = null,
            /** A refresh of the location that is already displayed is running, not a load for a new target. */
            val isRefreshing: Boolean = false,
            /** Counts refreshes, so one that starts and finishes between two collections is still noticed. */
            val refreshId: Int = 0,
        ) : State {
            val error: Throwable? get() = errorIncident?.error
            val canGoBack: Boolean get() = historyIndex > 0
            val canGoForward: Boolean get() = historyIndex < navigationHistory.size - 1
        }

        data class Error(val error: Throwable) : State
    }

    private inline fun updateReady(block: State.Ready.() -> State.Ready) {
        _state.update {
            when (it) {
                is State.Initializing -> it
                is State.Ready -> it.block()
                is State.Error -> it
            }
        }
    }

    /**
     * The engine republishes the same failure on every state change around it (a new refreshId, new
     * breadcrumbs). Only a throwable we have not seen yet becomes a new incident; the one already
     * held is carried on unchanged, so the report keeps the moment the navigation actually failed.
     */
    private suspend fun resolveEngineIncident(engineState: BrowsingEngine.State): ErrorIncident? {
        val error = engineState.error ?: return null
        val current = (_state.value as? State.Ready)?.errorIncident
        if (current != null && current.error === error) return current
        return freezeNavigationIncident(
            error = error,
            location = engineState.location,
            breadcrumbs = engineState.breadcrumbs,
            isRefreshing = engineState.isRefreshing,
            refreshId = engineState.refreshId,
        )
    }

    private suspend fun freezeNavigationIncident(
        error: Throwable,
        location: ExplorerLocation?,
        breadcrumbs: List<ExplorerBreadcrumb>? = null,
        isRefreshing: Boolean? = null,
        refreshId: Int? = null,
    ): ErrorIncident {
        val ready = _state.value as? State.Ready
        return errorIncidentFactory.freeze(
            error = error,
            context = mapOf(
                "nav.target" to ready?.currentTarget?.toString(),
                "nav.location" to location?.locationId,
                "nav.breadcrumbs" to (breadcrumbs ?: ready?.currentBreadcrumbs)
                    ?.joinToString("/") { it.target.toString() },
                "nav.historyIndex" to ready?.historyIndex?.toString(),
                "nav.isRefreshing" to (isRefreshing ?: ready?.isRefreshing)?.toString(),
                "nav.refreshId" to (refreshId ?: ready?.refreshId)?.toString(),
            ),
        )
    }

    init {
        browsingEngine.location
            .onEach { engineState ->
                // Frozen out here, never inside the update lambda: MutableStateFlow.update re-runs its
                // lambda on contention, which would mint a second id, re-stamp the timestamp and
                // re-spool the log for one and the same failure.
                val incident = resolveEngineIncident(engineState)
                updateReady {
                    copy(
                        currentLocation = engineState.location,
                        currentBreadcrumbs = engineState.breadcrumbs ?: currentBreadcrumbs,
                        errorIncident = incident,
                        isRefreshing = engineState.isRefreshing,
                        refreshId = engineState.refreshId,
                    )
                }
                rememberStableNavigation(engineState)
            }
            .launchIn(scope)

        // Process navigation requests
        navigationRequests
            .onEach { request ->
                log(tag, INFO) { "New navigation request: $request" }
                updateReady { copy(errorIncident = null) }
                try {
                    processNavigationRequest(request)
                } catch (e: Exception) {
                    when (e) {
                        is CancellationException -> {
                            log(tag, INFO) { "Navigation cancelled" }
                            updateReady { copy(currentLocation = null, isRefreshing = false) }
                            throw e
                        }

                        else -> {
                            log(tag, ERROR) { "Navigation failed: $e" }
                            val incident = freezeNavigationIncident(e, location = null)
                            updateReady {
                                copy(currentLocation = null, errorIncident = incident, isRefreshing = false)
                            }
                        }
                    }
                }
            }
            .launchIn(scope)

        // Forward filesystem hints to browsing engine
        fileSystemHinter.events
            .onEach { event -> browsingEngine.hint(event) }
            .launchIn(scope)

        // Load initial location
        scope.launch {
            log(tag, INFO) { "Loading initial data... ($creationArguments)" }
            // Transition to Ready state before processing navigation
            _state.value = State.Ready()
            try {
                val startPath = when (creationArguments) {
                    is ExplorerArguments.Picker -> creationArguments.startPath
                    is ExplorerArguments.Default -> creationArguments.startPath
                }
                val startTarget = (creationArguments as? ExplorerArguments.Default)?.startTarget
                // The setting only decides when the arguments carry no location of their own,
                // so it is not read (a DataStore hit) in the restore cases
                val defaultLocation = when {
                    startPath != null || startTarget != null -> null
                    else -> explorerSettings.defaultStartLocation.value().also {
                        log(tag, INFO) { "Using default start location from settings: $it" }
                    }
                }
                navigationRequests.emit(explorerStartTarget(startPath, startTarget, defaultLocation))
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to initialize: ${e.asLog()}" }
                Bugs.report(e)
                _state.value = State.Error(e)
            }
        }
    }

    private suspend fun processNavigationRequest(request: ExplorerNavigation) {
        log(tag, INFO) { "Processing navigation request: $request" }
        when (request) {
            is ExplorerNavigation.Target -> {
                loadTarget(request, addToHistory = true)
            }

            is ExplorerNavigation.Back -> {
                val readyState = _state.value as? State.Ready ?: return
                if (readyState.historyIndex > 0) {
                    val newIndex = readyState.historyIndex - 1
                    goToHistoryEntry(readyState.navigationHistory[newIndex], newIndex)
                }
            }

            is ExplorerNavigation.Forward -> {
                val readyState = _state.value as? State.Ready ?: return
                if (readyState.historyIndex < readyState.navigationHistory.size - 1) {
                    val newIndex = readyState.historyIndex + 1
                    goToHistoryEntry(readyState.navigationHistory[newIndex], newIndex)
                }
            }

            is ExplorerNavigation.Refresh -> {
                log(tag, INFO) { "Refreshing current location" }
                browsingEngine.refresh()
            }

            is ExplorerNavigation.Cancel -> {
                log(tag, INFO) { "Navigation cancel request processed" }
                when (val result = browsingEngine.cancelLoad()) {
                    // The engine republished whatever is needed, it arrives via its location flow.
                    is BrowsingEngine.CancelResult.NoLoadRunning -> log(tag) { "Nothing was loading" }
                    is BrowsingEngine.CancelResult.RefreshCancelled -> log(tag, INFO) { "Refresh cancelled" }
                    is BrowsingEngine.CancelResult.NavigationRestored -> restoreStableNavigation(result.target)
                    is BrowsingEngine.CancelResult.NothingToRestore -> {
                        // Only reachable while nothing ever settled here, so this is the state from
                        // before the initial load: the aborted target must not stay in the history,
                        // or the dialog's retry would append a duplicate and make "back" lead to
                        // the very target the user just aborted.
                        log(tag, INFO) { "Aborted before anything settled, resetting navigation state" }
                        updateReady {
                            copy(
                                currentTarget = null,
                                navigationHistory = emptyList(),
                                historyIndex = 0,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Pairs settled content with the navigation state it belongs to.
     *
     * The target match matters: a settle emission and a new navigation can race, and pairing one
     * target's content with another target's history would restore an inconsistent tab on cancel.
     */
    private fun rememberStableNavigation(engineState: BrowsingEngine.State) {
        if (engineState.location?.isLoading != false || engineState.error != null) return
        val target = engineState.target ?: return
        val readyState = _state.value as? State.Ready ?: return
        if (readyState.currentTarget != target) return
        stableNav = StableNav(
            target = target,
            historyIndex = readyState.historyIndex,
            navigationHistory = readyState.navigationHistory,
        )
    }

    /**
     * Puts the tab back on [restoredTarget], the target whose content the engine put back on screen.
     *
     * Without a matching snapshot the history is repaired deterministically instead, so target,
     * history and content stay consistent in every interleaving.
     */
    private fun restoreStableNavigation(restoredTarget: ExplorerNavigation.Target) {
        log(tag, INFO) { "restoreStableNavigation($restoredTarget)" }
        val snapshot = stableNav?.takeIf { it.target == restoredTarget }
        if (snapshot != null) {
            updateReady {
                copy(
                    currentTarget = snapshot.target,
                    historyIndex = snapshot.historyIndex,
                    navigationHistory = snapshot.navigationHistory,
                )
            }
            return
        }
        log(tag, WARN) { "No navigation snapshot for $restoredTarget, repairing the history" }
        updateReady {
            val index = navigationHistory.lastIndexOf(restoredTarget)
            if (index != -1) {
                copy(
                    currentTarget = restoredTarget,
                    navigationHistory = navigationHistory.take(index + 1),
                    historyIndex = index,
                )
            } else {
                copy(
                    currentTarget = restoredTarget,
                    navigationHistory = listOf(restoredTarget),
                    historyIndex = 0,
                )
            }
        }
    }

    /**
     * Moves along the existing history. Target and index are written together and before the load
     * starts: a settle collected between two separate writes would be paired with the index of the
     * entry the tab just left, and a cancel would then restore that mismatched pair.
     */
    private suspend fun goToHistoryEntry(target: ExplorerNavigation.Target, index: Int) {
        log(tag, INFO) { "goToHistoryEntry($target, $index)" }
        updateReady { copy(currentTarget = target, historyIndex = index) }
        loadTarget(target, addToHistory = false, updateCurrentTarget = false)
    }

    private suspend fun loadTarget(
        target: ExplorerNavigation.Target,
        addToHistory: Boolean,
        updateCurrentTarget: Boolean = true,
    ) {
        log(tag, INFO) { "loadTarget($target, $addToHistory)" }

        if (updateCurrentTarget) updateReady { copy(currentTarget = target) }

        if (addToHistory) {
            log(tag) { "loadTarget(): Updating history" }

            updateReady {
                log(tag) { "loadTarget(): Old history: index=$historyIndex history=$navigationHistory" }
                // Remove forward history when navigating to new location
                val trimmedHistory = navigationHistory.take(historyIndex + 1)
                val newHistory = trimmedHistory + target
                copy(
                    navigationHistory = newHistory,
                    historyIndex = newHistory.size - 1
                ).also {
                    log(tag) { "loadTarget(): New history: index=${it.historyIndex} history=${it.navigationHistory}" }
                }
            }

            // Track path access for shortcuts (only for new navigations to directories)
            if (target is ExplorerNavigation.Target.Directory) {
                pathAccessTracker.trackPathAccess(target.path)
            }
        }

        // Trigger load in browsing engine
        browsingEngine.setTarget(target)
    }

    fun navigate(request: ExplorerNavigation) {
        log(tag, INFO) { "navigate(): $request" }
        scope.launch {
            navigationRequests.emit(request)
        }
    }

    suspend fun execute(command: ExplorerCommand): Operation.State.Completed {
        log(tag) { "execute(): $command" }
        val executable = createOperation(command)
        val managed = operationsManager.submitAndGet(executable)
        log(tag) { "execute(): Submitted ${managed.id}, awaiting completion" }
        val completed = managed.awaitCompletion()
        log(tag) { "execute(): ${managed.id} completed" }
        return completed
    }

    private fun createOperation(command: ExplorerCommand): ExplorerOperation = when (command) {
        is ExplorerCommand.Delete -> deleteOperationFactory.create(
            workspaceId = id,
            command = command,
        )
        is ExplorerCommand.Restore -> restoreOperationFactory.create(
            workspaceId = id,
            command = command,
        )
        is ExplorerCommand.Create -> createOperationFactory.create(
            workspaceId = id,
            command = command,
        )
        is ExplorerCommand.CreateTextFile -> createTextFileOperationFactory.create(
            workspaceId = id,
            command = command,
        )
        is ExplorerCommand.Copy -> copyOperationFactory.create(
            workspaceId = id,
            command = command,
        )
        is ExplorerCommand.Move -> moveOperationFactory.create(
            workspaceId = id,
            command = command,
        )
        is ExplorerCommand.Compress -> compressOperationFactory.create(
            workspaceId = id,
            command = command,
        )
        is ExplorerCommand.Extract -> extractOperationFactory.create(
            workspaceId = id,
            command = command,
        )
        is ExplorerCommand.DownloadLocalCopy -> downloadLocalCopyOperationFactory.create(
            workspaceId = id,
            command = command,
        )
    }

    fun resolveConflict(operationId: Operation.Id, resolution: PathActionIssue.Resolution) {
        log(tag, INFO) { "Resolving conflict for operation $operationId: $resolution" }
        scope.launch {
            issueHandler.resolveIssue(operationId, resolution)
        }
    }

    fun cancelOperation(operationId: Operation.Id) {
        log(tag, INFO) { "Cancelling operation: $operationId" }
        scope.launch {
            operationsManager.cancel(operationId)
        }
    }

    override suspend fun release() {
        log(tag, INFO) { "release()" }
        browsingEngine.release()
        scope.cancel()
    }

    @AssistedFactory
    interface Factory : WorkspaceFactory<ExplorerArguments> {

        override fun create(id: Workspace.Id, arguments: ExplorerArguments): ExplorerWorkspace

        override val argumentsSerializer: KSerializer<ExplorerArguments> get() = serializer()

        override fun deriveDisplay(arguments: ExplorerArguments): WorkspaceDisplay? =
            deriveExplorerDisplay(arguments)
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object FactoryModule {
        @Provides
        @IntoMap
        @WorkspaceTypeKey(Workspace.Type.EXPLORER)
        fun factory(factory: Factory): WorkspaceFactory<*> = factory
    }
}
