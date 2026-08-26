package eu.darken.butler.workspace.core

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.upgrade.UpgradeRepo
import eu.darken.butler.upgrade.isProForUi
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.usage.WorkspaceUsageRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Singleton
class WorkspaceRepo @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val factoryMap: Map<Workspace.Type, @JvmSuppressWildcards WorkspaceFactory<*>>,
    private val workspaceSettings: WorkspaceSettings,
    private val operationsManager: OperationsManager,
    private val upgradeRepo: UpgradeRepo,
    private val usageRepo: WorkspaceUsageRepo,
) : WorkspaceProvider, WorkspaceRemote {

    private val lock = Mutex()
    private val _workspaces = MutableStateFlow<List<Workspace<*>>>(emptyList())
    private val _events = MutableSharedFlow<WorkspaceEvent>()

    // Content paths reserved via ClaimContentPath, keyed by (type, path). Guarded by [lock].
    // A claim stands in for a workspace that is about to publish the path in its Info (e.g. an
    // editor tab switching files) so concurrent Creates/claims dedup to the claimant meanwhile.
    private val contentClaims = mutableMapOf<Pair<Workspace.Type, APath<*>>, Workspace.Id>()

    // User-set workspace names, overlaid onto every published Workspace.Info. Owned here instead of
    // by the workspace implementations: most derive their title reactively and would overwrite it on
    // the next upstream emission. Mutated only while holding [lock].
    private val _customTitles = MutableStateFlow<Map<Workspace.Id, String>>(emptyMap())

    /**
     * When each open workspace came into existence, the ordering key for "oldest tab". Written only
     * while holding [lock], but read without it ([peekCreatedAt] is non-suspending and session
     * saving calls it outside the lock), so it is an immutable map replaced wholesale rather than a
     * mutable one.
     */
    @Volatile
    private var createdAtById: Map<Workspace.Id, Instant> = emptyMap()

    /**
     * Workspaces that owe their caller a result ([Workspace.ArgumentsForResult]) - pickers. Closing
     * one cancels a result the caller's ViewModel is still collecting, so the limit dialog refuses
     * any tab with one in its stack.
     *
     * Snapshotted from the creation arguments rather than projected onto [Workspace.Info]: live
     * workspaces rebuild their Info from scratch on every emission (ExplorerWorkspace does), so a
     * flag carried there would survive only until the first state change. Written under [lock] and
     * replaced wholesale, matching [createdAtById].
     */
    @Volatile
    private var resultReturningIds: Set<Workspace.Id> = emptySet()

    private val _pendingConfirmations = MutableStateFlow<Map<String, PendingWorkspaceConfirmation>>(emptyMap())
    val pendingConfirmations: Flow<Map<String, PendingWorkspaceConfirmation>> = _pendingConfirmations
        .setupCommonEventHandlers(TAG, enabled = { Bugs.isDebug }) { "PendingConfirmations" }
        .replayingShare(appScope)

    /**
     * Work parked behind a dialog, run under [lock] once the user resolves it. These always report
     * null: nothing waits on their outcome.
     */
    private val pendingActions = ConcurrentHashMap<String, suspend () -> Throwable?>()

    /**
     * Creates parked behind the free-tier limit dialog, replayed under [lock] with the tabs the user
     * picked. Separate from [pendingActions] because the argument is only known once the user has
     * made a selection, and because [resolveLimitByClosing] has a caller waiting for the failure to
     * report.
     */
    private val pendingLimitRecoveries = ConcurrentHashMap<String, PendingLimitRecovery>()

    /**
     * A create the free-tier limit blocked, kept until the user resolves or dismisses its dialog.
     *
     * [arguments] is carried alongside [retry] purely so the blocked create can still be recognised
     * as holding whatever it points at. Without it the arguments are sealed inside the closure, and
     * anything that reclaims unreferenced resources (see `ExternalImportSweeper`) would read a create
     * that is merely waiting for the user as one that no longer exists.
     */
    private data class PendingLimitRecovery(
        val arguments: Workspace.Arguments?,
        val retry: suspend (Set<Workspace.Id>) -> Throwable?,
    )

    /**
     * Arguments of every create currently parked behind a free-tier limit dialog. Empty once each is
     * either committed (it becomes a real workspace) or dismissed (it is dropped).
     */
    fun peekPendingCreateArguments(): List<Workspace.Arguments> =
        pendingLimitRecoveries.values.mapNotNull { it.arguments }

    /**
     * Normalized user-set name, or null when the input clears it. Single source of truth for what a
     * custom title may be: no control characters (they must never reach the DB or the tab strip),
     * trimmed, capped at [WorkspaceAction.Rename.MAX_CUSTOM_TITLE_LENGTH], blank == clear.
     */
    private fun normalizeCustomTitle(raw: String?): String? = raw
        ?.filterNot { it.isISOControl() }
        ?.trim()
        ?.take(WorkspaceAction.Rename.MAX_CUSTOM_TITLE_LENGTH)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun Workspace.Info.withCustomTitle(titles: Map<Workspace.Id, String>) =
        copy(customTitle = titles[id])

    private val infos: Flow<List<Workspace.Info>> = _workspaces.flatMapLatest { workspaces ->
        if (workspaces.isEmpty()) {
            flowOf(emptyList())
        } else {
            val infoFlows = workspaces.map { it.info }
            combine(infoFlows) { infos -> infos.toList() }
        }
    }

    /**
     * The custom-title overlay is folded into THIS combine instead of sitting in its own stage
     * between [infos] and here. [kotlinx.coroutines.flow.combine] collects each upstream in its own
     * child coroutine, so an extra stage adds a dispatch boundary and delays every [state] emission
     * by one more hop. On-demand session restore is sensitive to that: WorkspacePageManager awaits
     * `state.first { … }` per Created event and assigns focus from what it sees, so the added
     * latency let focus land on a paused stand-in after restore had finished, which then resumed
     * a tab that was supposed to stay paused.
     */
    override val state: Flow<WorkspaceRemote.State> = combine(
        infos,
        _customTitles,
        workspaceSettings.layoutModePortrait.flow,
        workspaceSettings.layoutModeLandscape.flow,
    ) { workspaceInfos, customTitles, layoutModePortrait, layoutModeLandscape ->
        WorkspaceRemote.State(
            infos = workspaceInfos.map { it.withCustomTitle(customTitles) },
            portraitPanelMode = layoutModePortrait,
            landscapePanelMode = layoutModeLandscape,
        )
    }
        .distinctUntilChanged()
        .setupCommonEventHandlers(TAG, enabled = { Bugs.isTrace }) { "WorkspaceState" }
        .replayingShare(appScope)

    override val events: Flow<WorkspaceEvent> = _events
        .setupCommonEventHandlers(TAG, enabled = { Bugs.isDebug }) { "WorkspaceEvents" }
        .replayingShare(appScope)

    override suspend fun emitEvent(event: WorkspaceEvent) {
        log(TAG) { "emitEvent($event)" }
        _events.emit(event)
    }

    private suspend fun create(
        type: Workspace.Type,
        arguments: Workspace.Arguments,
        idToReplace: Workspace.Id? = null,
        existingId: Workspace.Id? = null,
        createdAt: Instant? = null,
    ): Workspace.Id {
        log(TAG) { "create($type, $arguments, $idToReplace, existingId=$existingId)" }
        return commitWorkspace(
            newWorkspace = buildWorkspace(type, arguments, idToReplace, existingId),
            idToReplace = idToReplace,
            createdAt = createdAt,
            returnsResult = arguments is Workspace.ArgumentsForResult,
        )
    }

    /**
     * Instantiates a workspace without touching any repo state — the only half of creation that can
     * throw, split off so a failing factory can never leave a half-applied mutation behind (limit
     * recovery builds the replacement BEFORE closing anything). Must be called while holding [lock];
     * the instance it returns is owned by the caller until [commitWorkspace] takes it, so an
     * abandoned one has to be released.
     */
    private fun buildWorkspace(
        type: Workspace.Type,
        arguments: Workspace.Arguments,
        idToReplace: Workspace.Id?,
        existingId: Workspace.Id?,
    ): Workspace<out Workspace.Arguments> {
        // Honoring a caller-supplied id (single create and batch) must never append a duplicate id —
        // that would break retrieve/close/reorder and event targeting. Reusing the id of the tab being
        // replaced is the one legitimate collision.
        if (existingId != null && existingId != idToReplace && _workspaces.value.any { it.id == existingId }) {
            throw IllegalStateException("Cannot create workspace with id $existingId: already in use")
        }

        @Suppress("UNCHECKED_CAST")
        val factory = factoryMap[type] as? WorkspaceFactory<Workspace.Arguments>
            ?: throw IllegalArgumentException("No factory found for workspace type: $type")
        val newWorkspace = factory.create(
            id = existingId ?: Workspace.Id(),
            arguments = arguments
        ) as Workspace<out Workspace.Arguments>

        if (Bugs.isDebug) {
            val expected = arguments as? Workspace.ArgumentsWithCaller
            val seeded = newWorkspace.info.value
            val expectedModal = expected?.modalPresentation ?: Workspace.ModalPresentationMode.PANE_LOCAL
            if (seeded.callerWorkspaceId != expected?.callerWorkspaceId || seeded.modalPresentation != expectedModal) {
                log(TAG, ERROR) {
                    "Info seed mismatch for ${newWorkspace.id}: " +
                        "seeded=(${seeded.callerWorkspaceId}, ${seeded.modalPresentation}), " +
                        "expected=(${expected?.callerWorkspaceId}, $expectedModal). " +
                        "Lifecycle decisions read info.value — fix the workspace's initial Info."
                }
            }
        }

        return newWorkspace
    }

    /**
     * Publishes an instance built by [buildWorkspace]: replace-or-append, creation timestamp, orphan
     * cleanup. Must be called while holding [lock].
     */
    private suspend fun commitWorkspace(
        newWorkspace: Workspace<out Workspace.Arguments>,
        idToReplace: Workspace.Id?,
        createdAt: Instant?,
        returnsResult: Boolean,
    ): Workspace.Id {
        val wip = _workspaces.value.toMutableList()
        if (idToReplace != null) {
            val index = wip.indexOfFirst { it.id == idToReplace }
            if (index == -1) throw IllegalStateException("Tab not found")
            val replaced = wip[index]
            log(TAG) { "Replacing workspace at index $index" }
            wip[index] = newWorkspace
            replaced.release()
            operationsManager.removeWorkspace(replaced.id)
            // Same leak guard as executeClose: the replaced instance's claims die with it
            contentClaims.entries.removeAll { (_, owner) -> owner == replaced.id }
            // The slot's age belongs to the instance, not to the tab: a morph is a new workspace
            createdAtById = createdAtById - idToReplace
            resultReturningIds = resultReturningIds - idToReplace
            // A custom name belongs to the tab slot, not the instance: the Templates->X morph keeps
            // the name the user gave the tab. Migrated before publishing the new list so the infos
            // combine can never pair the new workspace with a stale title.
            if (newWorkspace.id != idToReplace) {
                _customTitles.update { titles ->
                    val carried = titles[idToReplace]
                    val without = titles - idToReplace
                    if (carried != null) without + (newWorkspace.id to carried) else without
                }
            }
        } else {
            wip.add(newWorkspace)
        }

        // Before the publish: create() returns to its caller only after the list was published, so a
        // save observing that emission must already see the timestamp.
        createdAtById = createdAtById + (newWorkspace.id to (createdAt ?: Clock.System.now()))
        resultReturningIds = if (returnsResult) {
            resultReturningIds + newWorkspace.id
        } else {
            resultReturningIds - newWorkspace.id
        }

        _workspaces.value = wip

        if (idToReplace != null && newWorkspace.id != idToReplace) {
            // Close sub-workspaces orphaned by the replace — their parent instance is gone
            _workspaces.value
                .filter { it.info.value.callerWorkspaceId == idToReplace }
                .forEach { executeClose(it.id) }
        }

        return newWorkspace.id
    }

    /**
     * Identity a paused stand-in shows, from the type's own [WorkspaceFactory.deriveDisplay].
     * A broken derivation must never fail session restore, so any failure degrades to the type
     * label. Note the returned [CaString]s can still be lazy: a resolution failure surfaces later,
     * during composition, not here.
     */
    private fun deriveDisplay(type: Workspace.Type, arguments: Workspace.Arguments): WorkspaceDisplay? = try {
        @Suppress("UNCHECKED_CAST")
        val factory = factoryMap[type] as? WorkspaceFactory<Workspace.Arguments>
        factory?.deriveDisplay(arguments)
    } catch (e: Exception) {
        log(TAG, WARN) { "Failed to derive display for $type ($arguments): ${e.asLog()}" }
        null
    }

    /**
     * Paused stand-ins are reported as absent: typed consumers cast the result to their concrete
     * workspace type, so a paused id must behave exactly like an id that doesn't exist yet — the
     * flow emits the instance once [WorkspaceAction.Resume] has swapped it in.
     */
    override fun retrieve(id: Workspace.Id): Flow<Workspace<out Workspace.Arguments>?> {
        return _workspaces.flatMapLatest { wss ->
            flowOf(wss.singleOrNull { it.id == id }?.takeIf { it !is PausedWorkspace })
        }
    }

    /**
     * Current instance for [id] INCLUDING paused stand-ins, read straight off the backing state.
     * For session saving, which must serialize the held arguments of workspaces that were never
     * resumed, and for authoritative checks that must not observe a stale snapshot (e.g. preview
     * capture re-validating that a workspace is still live before composing it) - [state] is an
     * asynchronous share whose replay cache can still hold the value from before the last swap.
     * Everything else uses [retrieve], which hides paused entries.
     */
    fun peek(id: Workspace.Id): Workspace<out Workspace.Arguments>? = _workspaces.value.singleOrNull { it.id == id }

    /**
     * Every workspace that exists right now, live or paused, read from the same authoritative list
     * [peek] uses. Callers that must not miss one - anything deciding a resource is unreachable -
     * enumerate through this rather than [state], whose replay can still be mid-restore and report
     * fewer workspaces than actually exist.
     */
    fun peekAll(): List<Workspace<out Workspace.Arguments>> = _workspaces.value

    /**
     * The current workspaces with their custom names applied, read from the authoritative state
     * rather than [state], whose replay can still hold the snapshot from before a create, close or
     * rename that has already been committed. Preview capture and fetch start right after such a
     * mutation, so naming from the replay is what bakes a stale name into a cached thumbnail.
     *
     * [lock] is NOT reentrant, so this must not be called from a path that already holds it.
     */
    suspend fun peekInfos(): List<Workspace.Info> = lock.withLock {
        val titles = _customTitles.value
        _workspaces.value.map { it.info.value.withCustomTitle(titles) }
    }

    /**
     * When [id] was created, or null when it is unknown. Survives a pause/resume round-trip - the
     * map is keyed by id and [executeResume] only swaps the instance in its list slot - so session
     * saving can persist the true creation instant instead of the instant of the first save.
     */
    fun peekCreatedAt(id: Workspace.Id): Instant? = createdAtById[id]

    /**
     * Ownership topology of the current workspace list, read straight off the backing state for the
     * same reason as [peek]: [state] is an asynchronous share whose replay cache can lag a swap, and
     * acting on a stale topology is what leaves a live modal over a released owner.
     */
    fun peekStacks(): WorkspaceStacks = WorkspaceStacks(_workspaces.value.map { it.info.value })

    /**
     * Id of the ownership unit [id] belongs to - the key every [WorkspacePauseGate] lease of a
     * participant must use, so one lease covers a whole stack. Falls back to [id] itself when the
     * ownership chain cannot be resolved; [WorkspaceAction.Pause] refuses that case anyway.
     */
    fun peekOwnershipRoot(id: Workspace.Id): Workspace.Id = peekStacks().rootOf(id)?.id ?: id

    /**
     * Drops every open confirmation and the work parked behind it, without running any of it. The
     * three stores are one unit: a confirmation the user can no longer see must not keep a create or
     * a close alive that could fire later.
     */
    private fun discardAllConfirmations() {
        _pendingConfirmations.value = emptyMap()
        pendingActions.clear()
        pendingLimitRecoveries.clear()
    }

    fun resolveConfirmation(confirmationId: String, confirmed: Boolean) {
        log(TAG, INFO) { "resolveConfirmation($confirmationId, confirmed=$confirmed)" }
        // Claim before publishing the removal, not after. This runs off the action lock, so an
        // overlapping close superseding this confirmation is concurrent with it: whoever takes the
        // action out of the map wins. Dropping the entry first would leave a window where the
        // supersede takes an action the user has already confirmed, and the close would be lost.
        val action = pendingActions.remove(confirmationId)
        _pendingConfirmations.update { it - confirmationId }
        // Dismissing a limit dialog drops its parked create too, or it would outlive the dialog
        pendingLimitRecoveries.remove(confirmationId)
        if (confirmed && action != null) {
            appScope.launch {
                lock.withLock { action() }
            }
        }
    }

    /**
     * Resolves a free-tier limit dialog by closing the tabs the user picked and completing the
     * create that was blocked. A no-op when nothing was parked for [confirmationId] - the dialog is
     * dismissed either way, exactly like [resolveConfirmation].
     *
     * The returned [Deferred] completes with the failure to show the user, or null when the recovery
     * worked. The recovery itself deliberately runs on the uncancellable [appScope]: awaiting the
     * result may be cancelled (configuration change, screen gone), the work in between closing the
     * victims and committing the replacement may not.
     */
    fun resolveLimitByClosing(confirmationId: String, victims: Set<Workspace.Id>): Deferred<Throwable?> {
        log(TAG, INFO) { "resolveLimitByClosing($confirmationId, $victims)" }
        _pendingConfirmations.update { it - confirmationId }
        val parked = pendingLimitRecoveries.remove(confirmationId)
        if (parked == null) {
            log(TAG, WARN) { "No blocked create parked for $confirmationId, nothing to recover" }
            return CompletableDeferred(null)
        }
        val retry = parked.retry
        return appScope.async {
            lock.withLock { retry(victims) }
        }
    }

    override suspend fun execute(action: WorkspaceAction): WorkspaceAction.Result {
        // Read outside the lock: isProForUi() suspends on the upgrade info flow and must not stall the repo.
        val isPro = when (action) {
            is WorkspaceAction.Create -> action.needsLimitCheck && upgradeRepo.isProForUi()
            is WorkspaceAction.CreateBatch -> upgradeRepo.isProForUi()
            else -> false
        }
        return lock.withLock {
        log(TAG, INFO) { "execute($action)" }
        if (Bugs.isDebug) when (action) {
            is WorkspaceAction.Create -> assertTypeMatchesArguments(action)
            is WorkspaceAction.CreateBatch -> action.requests.forEach { assertTypeMatchesArguments(it) }
            else -> Unit
        }
        when (action) {
            is WorkspaceAction.Create -> {
                log(TAG, INFO) { "Creating new workspace with $action" }

                // Singleton enforcement: refuse duplicates of singleton workspace types
                findExistingSingleton(action)?.let { existingId ->
                    log(TAG, INFO) { "Singleton ${action.type} already open as $existingId, returning AlreadyOpen" }
                    return@withLock WorkspaceAction.Create.Result.AlreadyOpen(existingId)
                }

                // Content-path dedup: refuse duplicates of an already-open content path.
                // Before the limit check so re-opening an open file never triggers the upgrade dialog.
                findExistingContentMatch(action)?.let { existingId ->
                    log(TAG, INFO) { "Content of ${action.type} create already open as $existingId, returning AlreadyOpen" }
                    return@withLock WorkspaceAction.Create.Result.AlreadyOpen(existingId)
                }

                // Check workspace limit for non-pro users
                if (!canCreateWorkspace(action, isPro)) {
                    log(TAG, INFO) { "Workspace limit reached, showing upgrade dialog" }
                    val limitRetry: (suspend (Set<Workspace.Id>) -> Throwable?)? = if (action.allowLimitRecovery) {
                        { victimIds -> recoverFromLimit(action, isPro, victimIds) }
                    } else {
                        null
                    }
                    postLimitDialog(retry = limitRetry, heldArguments = action.arguments)
                    return@withLock WorkspaceAction.Create.Result.LimitReached
                }

                val newId = create(
                    type = action.type,
                    arguments = action.arguments,
                    idToReplace = action.replace,
                    existingId = action.id,
                    createdAt = action.createdAt,
                )
                trackUsage(action, Clock.System.now())
                log(TAG) { "New workspace created with ID $newId, emitting event" }
                _events.emit(
                    WorkspaceEvent.Created(
                        workspaceId = newId,
                        replacedId = action.replace,
                        autoFocus = action.autoFocus,
                        sourceWorkspaceId = action.sourceWorkspaceId,
                    )
                )

                WorkspaceAction.Create.Result.Success(newId)
            }

            is WorkspaceAction.RegisterPaused -> {
                log(TAG, INFO) { "Registering paused workspace ${action.id} (${action.type})" }
                try {
                    if (_workspaces.value.any { it.id == action.id }) {
                        throw IllegalStateException("Cannot register paused workspace ${action.id}: id already in use")
                    }
                    // A stand-in displaying one type while holding another's arguments would also
                    // fail resuming permanently: the factory picked by type gets the wrong arguments
                    if (action.type != action.arguments.type) {
                        throw IllegalArgumentException(
                            "Cannot register paused workspace ${action.id}: type ${action.type} " +
                                "does not match arguments type ${action.arguments.type}"
                        )
                    }
                    // Sub-workspaces stay refused here even though [WorkspaceAction.Pause] may now
                    // release one together with its owner: this door exists for session restore, and
                    // a modal has no owner to belong to at that point. Sessions never save one
                    // either; stale rows are dropped earlier, while building the restore candidates.
                    if (action.arguments.isForSubWorkspace) {
                        throw IllegalArgumentException(
                            "Cannot register paused workspace ${action.id}: sub-workspaces are not persisted"
                        )
                    }
                    val display = deriveDisplay(action.type, action.arguments)
                    val paused = PausedWorkspace(
                        id = action.id,
                        type = action.type,
                        heldArguments = action.arguments,
                        title = display?.title ?: action.type.label,
                        subtitle = display?.subtitle,
                    )
                    createdAtById = createdAtById + (paused.id to (action.createdAt ?: Clock.System.now()))
                    _workspaces.value = _workspaces.value + paused
                    _events.emit(
                        WorkspaceEvent.Created(
                            workspaceId = paused.id,
                            replacedId = null,
                            autoFocus = false,
                        )
                    )
                    WorkspaceAction.RegisterPaused.Result.Success(paused.id)
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Failed to register paused workspace ${action.id}: ${e.asLog()}" }
                    WorkspaceAction.RegisterPaused.Result.Failed(e)
                }
            }

            is WorkspaceAction.Resume -> executeResume(action.id)

            is WorkspaceAction.Pause -> executePause(action.id)

            is WorkspaceAction.CreateBatch -> {
                log(TAG, INFO) { "Creating batch of ${action.requests.size} workspaces" }

                if (action.requests.size != action.requests.toSet().size) {
                    log(TAG, WARN) {
                        "Batch contains duplicate Create requests; equal entries will collapse in result map"
                    }
                }

                // Pre-resolve singletons and already-open content paths BEFORE applying the
                // free-tier limit. AlreadyOpen entries do not consume tab slots — they just refocus
                // an existing tab. A singleton type or content path appearing more than once in the
                // same batch (with no existing instance) is deduped here: the first occurrence stays
                // in pendingCreates, later ones are deferred and resolved to AlreadyOpen once the
                // instance exists. This keeps quota and confirmation accounting from counting the
                // same tab twice.
                val preResolved = mutableMapOf<WorkspaceAction.Create, Workspace.Id>()
                val pendingCreates = mutableListOf<WorkspaceAction.Create>()
                val deferredDupes = mutableListOf<WorkspaceAction.Create>()
                val queuedSingletonTypes = mutableSetOf<Workspace.Type>()
                val queuedContentKeys = mutableSetOf<Pair<Workspace.Type, APath<*>>>()
                action.requests.forEach { req ->
                    val existingId = findExistingSingleton(req) ?: findExistingContentMatch(req)
                    when {
                        existingId != null -> preResolved[req] = existingId
                        req.createsSingletonTab && !queuedSingletonTypes.add(req.type) -> deferredDupes += req
                        req.batchContentKey?.let { !queuedContentKeys.add(it) } == true -> deferredDupes += req
                        else -> pendingCreates += req
                    }
                }

                // Decide whether to ask for confirmation based on how many new tabs we'd open right
                // now. The actual quota is re-applied at execution time (in finalizeBatch), so a
                // confirmation that resolves after the user opened more tabs can never push them past
                // the limit — the planned count here is only an estimate for the dialog.
                val plannedAllowed = applyFreeTierLimit(pendingCreates, isPro)

                if (plannedAllowed.size >= CONFIRMATION_THRESHOLD) {
                    log(TAG, INFO) {
                        "Batch size (${plannedAllowed.size}) >= threshold ($CONFIRMATION_THRESHOLD), requesting confirmation"
                    }
                    val confirmationId = Uuid.random().toString()

                    pendingActions[confirmationId] = {
                        finalizeBatch(pendingCreates, preResolved, deferredDupes, isPro, action.sourceWorkspaceId)
                        null
                    }

                    _pendingConfirmations.update {
                        it + (confirmationId to PendingWorkspaceConfirmation(
                            id = confirmationId,
                            sourceWorkspaceId = action.sourceWorkspaceId,
                            data = PendingWorkspaceConfirmation.ConfirmationData.BatchWorkspaceCreation(
                                totalCount = plannedAllowed.size,
                                skippedCount = pendingCreates.size - plannedAllowed.size,
                            ),
                        ))
                    }

                    return@withLock WorkspaceAction.CreateBatch.Result.AwaitingConfirmation
                }

                finalizeBatch(pendingCreates, preResolved, deferredDupes, isPro, action.sourceWorkspaceId)
            }

            is WorkspaceAction.ClaimContentPath -> {
                val existing = findContentPathHolder(action.type, action.contentPath, excludeId = action.claimantId)
                if (existing != null) {
                    log(TAG, INFO) { "Claim on ${action.contentPath} denied for ${action.claimantId}: open as $existing" }
                    WorkspaceAction.ClaimContentPath.Result.AlreadyOpen(existing)
                } else {
                    contentClaims[action.type to action.contentPath] = action.claimantId
                    log(TAG) { "Claim on ${action.contentPath} granted to ${action.claimantId}" }
                    WorkspaceAction.ClaimContentPath.Result.Granted
                }
            }

            is WorkspaceAction.ReleaseContentPath -> {
                val removed = contentClaims.entries.removeAll { (key, owner) ->
                    owner == action.claimantId && key.second == action.contentPath
                }
                log(TAG) { "Claim release on ${action.contentPath} by ${action.claimantId}: removed=$removed" }
                WorkspaceAction.ReleaseContentPath.Result
            }

            is WorkspaceAction.Close -> {
                log(TAG, INFO) { "Closing workspace with id ${action.id}" }

                val workspace = _workspaces.value.firstOrNull { it.id == action.id }

                // Closing a tab takes its whole modal stack down with it, so the guard has to
                // cover everything executeClose would destroy, not just the id the close names: a
                // clean tab can own a dirty child (a Saver still holding shared content), and that
                // child is precisely the thing whose loss has to be confirmed.
                val dirtyMembers = closingSubtreeOf(action.id)
                    .filter { it.info.value.hasUnsavedChanges }
                val needsConfirmation = action.requireConfirmation || dirtyMembers.isNotEmpty()

                if (needsConfirmation) {
                    // The dialog has to name something. Usually that is the close target, but a
                    // close of an id nothing holds still has to be confirmable when it would take
                    // a dirty orphan down with it.
                    val naming = dirtyMembers.firstOrNull() ?: workspace
                    if (naming == null) {
                        log(TAG, WARN) { "Cannot request close confirmation - workspace ${action.id} not found" }
                        return@withLock WorkspaceAction.Close.Result
                    }

                    // Two closes overlap when one's subtree holds the other's target: answering
                    // either decides workspaces the other also claims. Only one of them may be
                    // pending, or the screen renders a single dialog for several queued answers and
                    // one dismissal only retires one of them.
                    val closingIds = closingIdsOf(action.id)
                    val overlapping = _pendingConfirmations.value.filterValues { pending ->
                        val data = pending.data
                        data is PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation &&
                            (data.workspaceId in closingIds || action.id in closingIdsOf(data.workspaceId))
                    }
                    val duplicate = overlapping.values.any {
                        val data = it.data as PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation
                        data.workspaceId == action.id
                    }
                    if (duplicate) {
                        log(TAG) { "Close confirmation already pending for ${action.id}, ignoring duplicate" }
                        return@withLock WorkspaceAction.Close.Result
                    }
                    // The newer request replaces the older rather than being dropped by it: a close
                    // the user just asked for must not be swallowed by one they have not answered.
                    overlapping.keys.forEach { supersededId ->
                        log(TAG, INFO) { "Close confirmation $supersededId overlaps ${action.id}, superseding it" }
                        pendingActions.remove(supersededId)
                    }

                    // The unsaved copy reads "<name> has unsaved changes", so it has to name a
                    // member holding them; without a dirty member the target names itself.
                    val workspaceInfo = naming.info.value.withCustomTitle(_customTitles.value)
                    val confirmationId = Uuid.random().toString()

                    log(TAG, INFO) { "Requesting confirmation to close workspace: ${action.id}, naming ${workspaceInfo.displayTitle}" }

                    pendingActions[confirmationId] = {
                        executeClose(action.id)
                        null
                    }

                    _pendingConfirmations.update {
                        it - overlapping.keys + (confirmationId to PendingWorkspaceConfirmation(
                            id = confirmationId,
                            // Anchors the dialog to the workspace the close was invoked from, which
                            // is the overlay on top when a unit is closed from one of its children.
                            sourceWorkspaceId = action.sourceWorkspaceId ?: action.id,
                            data = PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation(
                                workspaceId = action.id,
                                workspaceTitle = workspaceInfo.displayTitle,
                                hasUnsavedChanges = dirtyMembers.isNotEmpty(),
                                // The close takes the whole subtree, so naming one member while
                                // discarding several would understate what is lost
                                unsavedCount = dirtyMembers.size,
                            ),
                        ))
                    }

                    return@withLock WorkspaceAction.Close.Result
                }

                executeClose(action.id)

                WorkspaceAction.Close.Result
            }
            is WorkspaceAction.Reorder -> {
                log(TAG, INFO) { "Reordering workspaces: ${action.ownerIds}" }

                // Every surface that reorders lists one entry per ownership unit, so what arrives is
                // a unit order. Expanding here - inside the lock, against the current topology - is
                // what keeps a drag from being rejected because a create or close landed between the
                // snapshot and the drop.
                val current = _workspaces.value
                log(TAG) { "BEFORE re-order:\n${current.joinToString("\n")}" }
                val stacks = peekStacks()
                val membersByOwner = current.groupBy { stacks.ownerOf(it.id) }
                val expanded = action.ownerIds.flatMap { membersByOwner[it].orEmpty() }
                log(TAG) { "AFTER re-order:\n${expanded.joinToString("\n")}" }

                if (expanded.size != current.size) {
                    log(TAG, ERROR) { "Reorder failed: ${action.ownerIds} does not cover every unit" }
                    return WorkspaceAction.Reorder.Result(false)
                }

                _workspaces.value = expanded
                _events.emit(WorkspaceEvent.Reordered(workspaceIds = expanded.map { it.id }))

                WorkspaceAction.Reorder.Result(true)
            }
            is WorkspaceAction.Rename -> {
                val normalized = normalizeCustomTitle(action.customTitle)
                log(TAG, INFO) { "Renaming workspace ${action.id} to $normalized" }

                if (_workspaces.value.none { it.id == action.id }) {
                    log(TAG, WARN) { "Cannot rename ${action.id}: no such workspace" }
                    return@withLock WorkspaceAction.Rename.Result(false)
                }

                _customTitles.update {
                    if (normalized == null) it - action.id else it + (action.id to normalized)
                }
                _events.emit(WorkspaceEvent.Renamed(workspaceId = action.id, customTitle = normalized))

                WorkspaceAction.Rename.Result(true)
            }
            is WorkspaceAction.CloseSelected -> {
                log(TAG, INFO) { "Closing ${action.ownerIds.size} selected workspace(s)" }
                // executeClose is the post-confirmation path, so an unsaved member goes down with
                // the rest instead of parking another dialog the caller already asked about.
                var closed = 0
                action.ownerIds.forEach { id ->
                    if (_workspaces.value.none { it.id == id }) {
                        log(TAG) { "Selected workspace $id already gone, skipping" }
                        return@forEach
                    }
                    executeClose(id)
                    closed++
                }
                WorkspaceAction.CloseSelected.Result(closed)
            }

            WorkspaceAction.CloseAll -> {
                log(TAG, INFO) { "Closing all workspaces" }
                _workspaces.value.forEach {
                    it.release()
                    operationsManager.removeWorkspace(it.id)
                }
                _workspaces.value = emptyList()
                contentClaims.clear()
                _customTitles.value = emptyMap()
                createdAtById = emptyMap()
                resultReturningIds = emptySet()
                // Every pending confirmation asks about tabs that no longer exist. Left behind, a
                // limit dialog would survive with its blocked create still parked and re-open a tab
                // moments after the user emptied the session.
                discardAllConfirmations()
                _events.emit(WorkspaceEvent.AllClosed)

                WorkspaceAction.CloseAll.Result
            }
        }
        }
    }

    /**
     * Releases the live instances of the ownership unit [requestedId] belongs to and swaps a
     * [PausedWorkspace] stand-in into each of their list slots. Must be called while holding [lock].
     *
     * Five phases, in this order, because a unit has to pause all-or-nothing: reusing a per-workspace
     * pause would publish and release each member as it goes, so a later member's failing
     * [Workspace.createArguments] would leave the earlier ones already released with no way back.
     *
     * 1. Resolve the topology (cycle- and dangling-guarded) - the unit is the root plus every
     *    descendant, never just the requested id.
     * 2. Capture the arguments of every live member. No mutation at all in this phase.
     * 3. Revalidate every member, including eligibility against the arguments that were actually
     *    captured - those are the objects that have to round-trip, and [Workspace.createArguments]
     *    suspends, so a member can have gone busy meanwhile.
     * 4. Publish the complete replacement list to [_workspaces] exactly once, so no consumer ever
     *    observes a half-paused unit.
     * 5. Release the old instances deepest-first, logging failures without failing the result.
     *
     * Known limitation: [execute] holds one global mutex across [Workspace.createArguments] and
     * [Workspace.release], so a slow engine release stalls unrelated create/close/resume actions.
     * Auto-pause issues Pause actions strictly sequentially, one per evaluation pass, to bound this.
     */
    private suspend fun executePause(requestedId: Workspace.Id): WorkspaceAction.Pause.Result {
        if (_workspaces.value.none { it.id == requestedId }) {
            log(TAG) { "Pause($requestedId): unknown workspace, nothing to do" }
            return WorkspaceAction.Pause.Result.NoOp
        }

        // Phase 1: topology
        val stacks = peekStacks()
        val root = stacks.rootOf(requestedId)
        if (root == null) {
            log(TAG, WARN) { "Pause($requestedId) refused: ownership chain is cyclic or dangling" }
            return WorkspaceAction.Pause.Result.Refused(WorkspaceAction.Pause.Reason.BROKEN_OWNERSHIP)
        }
        val memberIds = stacks.unitOf(root.id).orEmpty().map { it.id }
        val members = memberIds.mapNotNull { id -> _workspaces.value.firstOrNull { it.id == id } }
        val liveMembers = members.filterNot { it is PausedWorkspace }
        if (liveMembers.isEmpty()) {
            log(TAG) { "Pause($requestedId): the unit rooted at ${root.id} is already paused" }
            return WorkspaceAction.Pause.Result.NoOp
        }

        members.forEach { member ->
            pauseRefusal(member, isRoot = member.id == root.id)?.let { reason ->
                log(TAG, INFO) { "Pause($requestedId) refused because of ${member.id}: $reason" }
                return WorkspaceAction.Pause.Result.Refused(reason)
            }
        }

        // Phase 2: capture, nothing else
        val captured = mutableListOf<Pair<Workspace<*>, Workspace.Arguments>>()
        liveMembers.forEach { member ->
            val arguments = try {
                member.createArguments()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, ERROR) {
                    "Pause($requestedId): capturing arguments of ${member.id} failed, " +
                        "keeping the whole unit live: ${e.asLog()}"
                }
                return WorkspaceAction.Pause.Result.Failed(e)
            }
            captured += member to arguments
        }

        // Phase 3: revalidate against what we captured
        captured.forEach { (member, arguments) ->
            if (_workspaces.value.none { it.id == member.id }) {
                log(TAG, WARN) { "Pause($requestedId): ${member.id} vanished while capturing arguments" }
                return WorkspaceAction.Pause.Result.NoOp
            }
            val isRoot = member.id == root.id
            if (!isRoot && !arguments.isPausableAsChild) {
                log(TAG, INFO) {
                    "Pause($requestedId) refused: the captured arguments of ${member.id} do not " +
                        "survive being paused with their owner"
                }
                return WorkspaceAction.Pause.Result.Refused(WorkspaceAction.Pause.Reason.HAS_CHILDREN)
            }
            pauseRefusal(member, isRoot = isRoot)?.let { reason ->
                log(TAG, INFO) { "Pause($requestedId) refused after capturing arguments: $reason" }
                return WorkspaceAction.Pause.Result.Refused(reason)
            }
        }

        // Phase 4: point of no return. The unit is reported as paused from here on, so a failing
        // release() below is logged but does not turn the result into a failure. Content claims are
        // not cleared - pauseRefusal() guarantees no member holds one.
        val standIns = captured.associate { (member, arguments) ->
            // The live info names the tab here; the derivation is only the fallback for the day a
            // workspace publishes no identity of its own.
            val display = deriveDisplay(member.type, arguments)
            member.id to PausedWorkspace(
                id = member.id,
                type = member.type,
                heldArguments = arguments,
                title = display?.title ?: member.type.label,
                subtitle = display?.subtitle,
                carriedInfo = member.info.value,
            )
        }
        _workspaces.value = _workspaces.value.map { standIns[it.id] ?: it }

        // Phase 5: deepest-first, so an owner is never released before what it owns
        captured.asReversed().forEach { (member, _) ->
            try {
                member.release()
            } catch (e: Exception) {
                log(TAG, ERROR) { "Pause($requestedId): release() of ${member.id} failed: ${e.asLog()}" }
            }
        }

        log(TAG, INFO) { "Paused ${captured.size} workspace(s) of the unit rooted at ${root.id}" }
        return WorkspaceAction.Pause.Result.Success(id = root.id, pausedIds = memberIds)
    }

    /**
     * Instantiates every paused member of the ownership unit [requestedId] belongs to, owners before
     * the workspaces they own. Must be called while holding [lock].
     *
     * A member whose factory throws keeps its stand-in (and its error), and its descendants are not
     * attempted at all: composing a modal over a released owner has nothing to bind to. Independent
     * branches are unaffected, so one broken tab cannot block the rest of a unit.
     */
    private fun executeResume(requestedId: Workspace.Id): WorkspaceAction.Resume.Result {
        if (_workspaces.value.none { it.id == requestedId }) {
            log(TAG) { "Resume($requestedId): unknown workspace, nothing to do" }
            return WorkspaceAction.Resume.Result.NoOp
        }
        val stacks = peekStacks()
        val root = stacks.rootOf(requestedId)
        if (root == null) {
            log(TAG, WARN) { "Resume($requestedId): ownership chain is cyclic or dangling, leaving it alone" }
            return WorkspaceAction.Resume.Result.NoOp
        }
        val members = stacks.unitOf(root.id).orEmpty()
        if (members.none { _workspaces.value.firstOrNull { ws -> ws.id == it.id } is PausedWorkspace }) {
            log(TAG) { "Resume($requestedId): nothing in the unit rooted at ${root.id} is paused" }
            return WorkspaceAction.Resume.Result.NoOp
        }

        val outcomes = mutableMapOf<Workspace.Id, WorkspaceAction.Resume.MemberOutcome>()
        // Members are breadth-first from the root, so an owner's outcome is always known by the time
        // its children are visited.
        members.forEach { info ->
            val blockedBy = info.callerWorkspaceId?.let { callerId ->
                when (val callerOutcome = outcomes[callerId]) {
                    is WorkspaceAction.Resume.MemberOutcome.Failed ->
                        callerId to callerOutcome.error
                    is WorkspaceAction.Resume.MemberOutcome.SkippedAncestorFailed ->
                        callerOutcome.ancestorId to callerOutcome.error
                    else -> null
                }
            }
            if (blockedBy != null) {
                val (ancestorId, error) = blockedBy
                log(TAG, WARN) { "Resume($requestedId): skipping ${info.id}, its owner $ancestorId stayed paused" }
                outcomes[info.id] = WorkspaceAction.Resume.MemberOutcome.SkippedAncestorFailed(ancestorId, error)
                return@forEach
            }

            // Not paused (already resumed by a concurrent call, or never paused) is skipped, which is
            // what keeps a double resume from running the factory twice.
            val paused = _workspaces.value.firstOrNull { it.id == info.id } as? PausedWorkspace
            if (paused == null) {
                outcomes[info.id] = WorkspaceAction.Resume.MemberOutcome.AlreadyLive
                return@forEach
            }

            try {
                @Suppress("UNCHECKED_CAST")
                val factory = factoryMap[paused.type] as? WorkspaceFactory<Workspace.Arguments>
                    ?: throw IllegalArgumentException("No factory found for workspace type: ${paused.type}")
                val resumed = factory.create(
                    id = paused.id,
                    arguments = paused.heldArguments,
                ) as Workspace<out Workspace.Arguments>

                val wip = _workspaces.value.toMutableList()
                wip[wip.indexOfFirst { it.id == paused.id }] = resumed
                _workspaces.value = wip

                log(TAG, INFO) { "Resumed workspace ${paused.id} (${paused.type})" }
                outcomes[info.id] = WorkspaceAction.Resume.MemberOutcome.Resumed
            } catch (e: Exception) {
                log(TAG, ERROR) { "Failed to resume workspace ${paused.id}: ${e.asLog()}" }
                paused.markResumeError(e)
                outcomes[info.id] = WorkspaceAction.Resume.MemberOutcome.Failed(e)
            }
        }

        return when (val requestedOutcome = outcomes[requestedId]) {
            is WorkspaceAction.Resume.MemberOutcome.Failed ->
                WorkspaceAction.Resume.Result.Failed(requestedOutcome.error, outcomes)
            is WorkspaceAction.Resume.MemberOutcome.SkippedAncestorFailed ->
                WorkspaceAction.Resume.Result.Failed(requestedOutcome.error, outcomes)
            else -> WorkspaceAction.Resume.Result.Success(newId = requestedId, outcomes = outcomes)
        }
    }

    /**
     * The reason [workspace] must not be paused right now, or null when pausing it is safe. Must be
     * called while holding [lock].
     *
     * Already-paused members are only checked for eligibility: they hold nothing that could be lost.
     * [isRoot] members skip the eligibility check - a unit is always acted on through its root, so
     * the root's own relationship to whatever created it is irrelevant.
     */
    private fun pauseRefusal(workspace: Workspace<*>, isRoot: Boolean): WorkspaceAction.Pause.Reason? {
        val info = workspace.info.value
        // Structural, so it outranks the transient guards: this unit can never be paused, as opposed
        // to not being pausable right now.
        if (!isRoot && !info.pausableAsChild) return WorkspaceAction.Pause.Reason.HAS_CHILDREN
        if (workspace is PausedWorkspace) return null
        return when {
            // An open-transition is in flight; dropping the claim could let a duplicate tab open
            // on that path, so the claim is never cleared - the pause waits instead.
            contentClaims.values.any { it == workspace.id } -> WorkspaceAction.Pause.Reason.CLAIM_HELD
            info.operationCount > 0 || info.attentionCount > 0 -> WorkspaceAction.Pause.Reason.BUSY
            info.hasUnsavedChanges -> WorkspaceAction.Pause.Reason.UNSAVED_CHANGES
            !info.isPausable -> WorkspaceAction.Pause.Reason.NOT_PAUSABLE
            info.lifecycleState !is Workspace.LifecycleState.Ready -> WorkspaceAction.Pause.Reason.NOT_READY
            else -> null
        }
    }

    /**
     * True when this create counts against [FREE_TIER_WORKSPACE_LIMIT]: not a session restore
     * ([WorkspaceAction.Create.skipLimitCheck]), not a quota-exempt type ([Workspace.Type.isQuotaExempt]),
     * not a sub-workspace (modal/picker), not a replace.
     */
    private val WorkspaceAction.Create.needsLimitCheck: Boolean
        get() = !skipLimitCheck && !type.isQuotaExempt && !arguments.isForSubWorkspace && replace == null

    /**
     * True when this create would occupy the single tab slot for its singleton type — so a second
     * such create in the same batch is redundant and can be deferred to AlreadyOpen. Mirrors the
     * gating in [findExistingSingleton]: singleton sub-workspaces, restores ([skipLimitCheck]) and
     * replaces are NOT deduped (they are legitimately distinct creates).
     */
    private val WorkspaceAction.Create.createsSingletonTab: Boolean
        get() = type.isSingleton && !arguments.isForSubWorkspace && !skipLimitCheck && replace == null

    /**
     * True when this create reflects a deliberate user choice of workspace type and should feed the
     * "recently used" ranking: not a session restore ([WorkspaceAction.Create.skipLimitCheck]), not a
     * system/utility type ([Workspace.Type.isQuotaExempt]), not a sub-workspace (picker, app details,
     * saver) and not the templates picker itself (which is the entry point offering the ranking).
     * A replace (tab morph) does count — the user picked that type.
     */
    private val WorkspaceAction.Create.isTrackableUsage: Boolean
        get() = !skipLimitCheck &&
            !type.isQuotaExempt &&
            !arguments.isForSubWorkspace &&
            type != Workspace.Type.TEMPLATES

    /**
     * Fire-and-forget on [appScope] so a DataStore write never stalls the repo's [lock]. [usedAt] is
     * captured by the caller right after creation: computing it inside the coroutine would let
     * scheduling reorder timestamps relative to actual creation order.
     */
    private fun trackUsage(request: WorkspaceAction.Create, usedAt: Instant) {
        if (!request.isTrackableUsage) return
        appScope.launch { usageRepo.track(request.type, usedAt) }
    }

    /**
     * Number of open tab workspaces that count toward [FREE_TIER_WORKSPACE_LIMIT]: excludes modal
     * sub-workspaces and quota-exempt types ([Workspace.Type.isQuotaExempt]).
     */
    private fun countedTabCount(): Int = _workspaces.value.count { it.isCountedTab }

    /**
     * True when this workspace occupies one of the free tier's slots. The single definition of that
     * set: the quota counts it, the limit dialog lists it, and only it may be closed to free a slot -
     * closing anything else would leave the count exactly where it was.
     */
    private val Workspace<*>.isCountedTab: Boolean
        get() = !info.value.isSubWorkspace && !type.isQuotaExempt

    /**
     * Debug-only invariant check: [WorkspaceAction.Create.type] must match its [arguments] type.
     * Both quota policy ([Workspace.Type.isQuotaExempt]) and factory selection key off [Create.type],
     * so a mismatch is a caller bug that could pick the wrong factory or bypass the quota.
     */
    private fun assertTypeMatchesArguments(create: WorkspaceAction.Create) {
        if (create.type != create.arguments.type) {
            log(TAG, ERROR) {
                "Create.type (${create.type}) != arguments.type (${create.arguments.type}); " +
                    "quota and factory selection key off Create.type — fix the caller."
            }
        }
    }

    private fun canCreateWorkspace(action: WorkspaceAction.Create, isPro: Boolean): Boolean {
        if (!action.needsLimitCheck) return true
        if (isPro) return true

        return countedTabCount() < FREE_TIER_WORKSPACE_LIMIT
    }

    /**
     * Returns the id of an existing non-sub-workspace instance of [action.type] when [action] would
     * create a duplicate of a singleton ([Workspace.Type.isSingleton]) — meaning the caller should
     * focus the existing tab instead of creating a new one. Returns null when creation should
     * proceed normally.
     *
     * Skipped (returns null) for: non-singleton types, sub-workspace creates, session restoration
     * ([WorkspaceAction.Create.skipLimitCheck]), and replace operations that target the existing
     * singleton itself (legitimate "morph in place").
     */
    private fun findExistingSingleton(action: WorkspaceAction.Create): Workspace.Id? {
        if (!action.type.isSingleton) return null
        if (action.arguments.isForSubWorkspace) return null
        if (action.skipLimitCheck) return null

        val existingId = _workspaces.value.firstOrNull { ws ->
            ws.type == action.type && !ws.info.value.isSubWorkspace
        }?.id ?: return null

        // Replacing the existing singleton itself is legitimate (templates "morph in place")
        if (action.replace == existingId) return null

        return existingId
    }

    /**
     * Content path this create dedups on, or null when dedup doesn't apply. Mirrors
     * [findExistingSingleton]'s gating: sub-workspace creates, session restoration
     * ([WorkspaceAction.Create.skipLimitCheck]) and creates that opted out
     * ([WorkspaceAction.Create.skipContentDedup]) never dedup.
     */
    private val WorkspaceAction.Create.dedupContentPath: APath<*>?
        get() {
            if (arguments.isForSubWorkspace) return null
            if (skipLimitCheck) return null
            if (skipContentDedup) return null
            return (arguments as? Workspace.ArgumentsWithContentPath)?.contentPath
        }

    /** In-batch dedup key for content-path creates; null for replaces (legitimately distinct). */
    private val WorkspaceAction.Create.batchContentKey: Pair<Workspace.Type, APath<*>>?
        get() = if (replace != null) null else dedupContentPath?.let { type to it }

    /**
     * Id of a live non-sub-workspace of [type] publishing [contentPath] via [Workspace.Info.contentPath],
     * or holding a claim on it. [excludeId] keeps a workspace from matching itself (claim flows).
     * Content paths are not exclusive (Save-As convergence, restored duplicates) — ties resolve to
     * the first workspace in list order. Must be called while holding [lock].
     */
    private fun findContentPathHolder(
        type: Workspace.Type,
        contentPath: APath<*>,
        excludeId: Workspace.Id? = null,
    ): Workspace.Id? {
        _workspaces.value.firstOrNull { ws ->
            ws.id != excludeId && ws.type == type && !ws.info.value.isSubWorkspace &&
                ws.info.value.contentPath == contentPath
        }?.id?.let { return it }
        return contentClaims[type to contentPath]?.takeIf { it != excludeId }
    }

    /**
     * Returns the id of an existing workspace already holding the content path [action] would open
     * (see [Workspace.ArgumentsWithContentPath]), or null when creation should proceed. Same skip
     * rules as [findExistingSingleton], including replace targeting the holder itself.
     */
    private fun findExistingContentMatch(action: WorkspaceAction.Create): Workspace.Id? {
        val path = action.dedupContentPath ?: return null
        val existingId = findContentPathHolder(action.type, path) ?: return null
        if (action.replace == existingId) return null
        return existingId
    }

    /**
     * Why closing the tab [workspace] roots would destroy something the user still needs, or null
     * when it is safe to close. Deliberately stricter than [pauseRefusal]: a pause is reversible,
     * this is not.
     *
     * Reports a reason rather than a bare boolean because the limit dialog lists the blocked tabs
     * too - "why can't I close that one" is the whole point of showing them.
     *
     * Answers for the whole ownership unit, not just the root. [WorkspaceAction.Close] auto-closes
     * children, so a drill-down that is busy or dirty is destroyed by closing the tab it sits on -
     * asking only the root would happily close a tab whose stacked child holds unsaved work.
     *
     * A stacked child is not a blocker in itself: an Apps tab with an app's details open is a tab
     * with a detail view, and closing it is exactly what the user picked. Only a workspace that owes
     * its caller a result ([Workspace.ArgumentsForResult] - a picker) is, because its result
     * collector lives in the caller's ViewModel and closing the unit cancels it silently.
     *
     * Must be called while holding [lock]; [stacks] is the ownership topology of the same snapshot.
     */
    private fun limitCloseBlocker(
        workspace: Workspace<*>,
        stacks: WorkspaceStacks,
    ): WorkspaceLimitCandidate.Blocker? {
        // A counted root should always resolve; when it does not there is no safe set to act on and
        // no honest reason to give, so refuse without inventing one.
        val unit = stacks.unitOf(workspace.id) ?: run {
            log(TAG, WARN) { "Counted tab ${workspace.id} has no resolvable unit, refusing to close it" }
            return WorkspaceLimitCandidate.Blocker.UNAVAILABLE
        }
        // Scanned across the unit before anything else: owing a result is a structural fact, so it
        // outranks a transient state anywhere in the stack. Otherwise an initializing root would
        // explain an open picker as "still loading".
        if (unit.any { it.id in resultReturningIds }) return WorkspaceLimitCandidate.Blocker.AWAITING_RESULT
        return unit.firstNotNullOfOrNull { memberCloseBlocker(it) }
    }

    /**
     * The same question asked of a single workspace, with no view of the rest of its unit. Used both
     * to build [limitCloseBlocker]'s answer and to re-ask it about each member while a unit is being
     * torn down. Must be called while holding [lock].
     */
    private fun memberCloseBlocker(info: Workspace.Info): WorkspaceLimitCandidate.Blocker? {
        if (info.id in resultReturningIds) return WorkspaceLimitCandidate.Blocker.AWAITING_RESULT
        // Close() turns these into a second confirmation stacked on the limit dialog; closing them
        // silently is data loss
        if (info.hasUnsavedChanges) return WorkspaceLimitCandidate.Blocker.UNSAVED_CHANGES
        if (info.attentionCount > 0) return WorkspaceLimitCandidate.Blocker.NEEDS_ATTENTION
        if (info.operationCount > 0) return WorkspaceLimitCandidate.Blocker.BUSY
        // Zero counters while setup is still running says nothing about what would be lost
        if (info.lifecycleState is Workspace.LifecycleState.Initializing) {
            return WorkspaceLimitCandidate.Blocker.LOADING
        }
        // An open-transition in flight, exactly as pauseRefusal treats it
        if (contentClaims.values.any { it == info.id }) return WorkspaceLimitCandidate.Blocker.BUSY
        return null
    }

    /**
     * Closes a whole tab for limit recovery, re-asking each member whether it is still safe
     * immediately before that member is released. Returns true when the tab itself went, i.e. when a
     * slot was actually freed.
     *
     * [executeClose] on the root would take the stack down in one recursive sweep, and every
     * `release()` in it suspends - long enough for a sibling to pick up unsaved changes that the
     * up-front check could not have seen. So the walk goes deepest-first and stops at the first
     * member that refuses: what is already closed is what the user consented to, and the tab they
     * would have lost stays open instead.
     */
    private suspend fun closeUnitForRecovery(rootId: Workspace.Id): Boolean {
        val members = peekStacks().unitOf(rootId) ?: return false
        for (member in members.asReversed()) {
            val live = _workspaces.value.firstOrNull { it.id == member.id } ?: continue
            val blocker = memberCloseBlocker(live.info.value)
            if (blocker != null) {
                log(TAG, WARN) { "${member.id} turned $blocker mid-close, leaving ${rootId} open" }
                return false
            }
            executeClose(member.id)
        }
        return true
    }

    /**
     * Every counted tab the limit dialog may offer, oldest first - the order the user is most likely
     * to close in. Ties (identical timestamps, e.g. a batch) break on list order so the list is
     * deterministic. Blocked tabs are included, carrying their reason.
     *
     * Each entry is a whole tab: identified and aged by its ownership root, but NAMED by whatever is
     * on top of it, because that is what the user is looking at. The tab manager's cards resolve the
     * same way.
     *
     * The branch is picked without a focus hint ([WorkspaceStacks.topChainByRoot] falls back to the
     * newest), since focus lives in the UI layer. That only differs from what is on screen when one
     * tab holds two sibling branches, and both die with the tab either way.
     *
     * Restricted to what the quota counts: closing anything else would not free a slot, so listing
     * it would promise something the retry cannot deliver. Must be called while holding [lock].
     */
    private fun limitCandidates(): List<WorkspaceLimitCandidate> {
        val stacks = peekStacks()
        val titles = _customTitles.value
        val topByRoot = stacks.topChainByRoot(focusedId = null)
        return _workspaces.value
            .withIndex()
            .filter { (_, workspace) -> workspace.isCountedTab }
            .sortedWith(
                compareBy<IndexedValue<Workspace<*>>> { (_, workspace) ->
                    createdAtById[workspace.id] ?: Instant.DISTANT_PAST
                }.thenBy { it.index }
            )
            .map { (_, workspace) ->
                val root = workspace.info.value.withCustomTitle(titles)
                val chain = topByRoot[workspace.id]
                // The automatic name describes what is on top; a name the user typed belongs to the
                // tab itself and outranks it, exactly as the tab manager's cards resolve it.
                val top = chain?.leaf ?: root
                WorkspaceLimitCandidate(
                    id = workspace.id,
                    type = top.type,
                    title = root.customTitle?.toCaString() ?: top.title,
                    subtitle = top.subtitle,
                    openedAt = createdAtById[workspace.id] ?: Instant.DISTANT_PAST,
                    stackDepth = chain?.modals?.size ?: 0,
                    blocker = limitCloseBlocker(workspace, stacks),
                )
            }
    }

    /**
     * Surfaces the free-tier limit dialog. [retry] is the blocked create, replayed with the tabs the
     * user picked in the dialog; passing null (batches) offers no such action and posts a bare
     * notice.
     *
     * The recovery is only parked when closing every closable tab actually unblocks the create:
     * restore creates with `skipLimitCheck`, so the counted count can legitimately sit ABOVE the
     * limit, and freeing fewer slots than that overshoot would promise something the retry cannot
     * deliver. The tabs are still listed in that case - read-only, so the user can at least see what
     * is holding the slots - which is why `canRecover` is tracked separately from the list.
     */
    private fun postLimitDialog(
        retry: (suspend (Set<Workspace.Id>) -> Throwable?)? = null,
        heldArguments: Workspace.Arguments? = null,
    ) {
        val confirmationId = Uuid.random().toString()
        val currentCount = countedTabCount()

        val candidates = if (retry == null) emptyList() else limitCandidates()
        val canRecover = retry != null &&
            currentCount - candidates.count { it.isClosable } < FREE_TIER_WORKSPACE_LIMIT
        if (retry != null && canRecover) {
            pendingLimitRecoveries[confirmationId] = PendingLimitRecovery(heldArguments, retry)
        }

        _pendingConfirmations.update {
            it + (confirmationId to PendingWorkspaceConfirmation(
                id = confirmationId,
                sourceWorkspaceId = null,
                data = PendingWorkspaceConfirmation.ConfirmationData.WorkspaceLimitReached(
                    currentCount = currentCount,
                    limit = FREE_TIER_WORKSPACE_LIMIT,
                    candidates = candidates,
                    canRecover = canRecover,
                ),
            ))
        }
    }

    /**
     * Replays a create that the free-tier limit blocked, closing [victimIds] to make room. Reproduces
     * `createAndFocus` - including its AlreadyOpen branch - so a recovered create is indistinguishable
     * from one that was never blocked.
     *
     * Every caller that opts in ([WorkspaceAction.Create.allowLimitRecovery]) therefore gets
     * create-and-focus semantics on recovery, even the tab manager's "Add tab", whose unblocked path
     * does not focus what it creates. Landing on the tab you just freed room for is the point.
     *
     * Must be called while holding [lock]: it drives the internal paths directly instead of
     * [execute], whose non-reentrant mutex would deadlock permanently here.
     *
     * Nothing is destroyed before the replacement exists, and the order of the checks mirrors the
     * normal create path (dedup before quota): re-opening something that is meanwhile open must never
     * cost the user a tab.
     *
     * [isPro] is the value sampled when the create was made, matching [finalizeBatch]: an entitlement
     * change while the dialog was open is not re-read (reading it suspends and must not happen under
     * [lock], and the upgrade action dismisses this dialog anyway).
     *
     * Returns the failure to surface to the user, or null when there is nothing to report - the
     * recovery runs behind a dialog the user just dismissed, so a silent failure would look like the
     * create simply never happened (and, past the close, would have cost them a tab).
     */
    private suspend fun recoverFromLimit(
        action: WorkspaceAction.Create,
        isPro: Boolean,
        victimIds: Set<Workspace.Id>,
    ): Throwable? {
        log(TAG, INFO) { "recoverFromLimit($action, victims=$victimIds)" }

        (findExistingSingleton(action) ?: findExistingContentMatch(action))?.let { existingId ->
            log(TAG, INFO) { "Blocked create of ${action.type} is open as $existingId now, selecting it" }
            _events.emit(WorkspaceEvent.SelectionRequested(existingId, action.sourceWorkspaceId))
            return null
        }

        val needsClose = !canCreateWorkspace(action, isPro)
        // Only what the user picked and what is still safe to close. Dropping some of the selection
        // is fine - it is a subset of what they consented to - but substituting a tab they did not
        // pick never is, so a short selection sends them back to a fresh dialog instead.
        // isCountedTab is re-checked, not assumed from the dialog: this is reached through the public
        // resolveLimitByClosing, so the set is whatever a caller passed. Closing an uncounted
        // workspace would free no slot while the sufficiency check below believed it had.
        val victims = if (needsClose) {
            val stacks = peekStacks()
            _workspaces.value.filter {
                it.id in victimIds && it.isCountedTab && limitCloseBlocker(it, stacks) == null
            }
        } else {
            emptyList()
        }
        if (needsClose) {
            // Restore creates with skipLimitCheck, so the counted count can have grown past limit + 1
            // while the dialog was up, and tabs can have turned dirty or busy meanwhile. Committing
            // anyway would leave the user above the cap.
            if (countedTabCount() - victims.size >= FREE_TIER_WORKSPACE_LIMIT) {
                log(TAG, WARN) { "Closing ${victims.size} of ${victimIds.size} tabs no longer frees a slot, asking again" }
                val retry: suspend (Set<Workspace.Id>) -> Throwable? = { newVictimIds ->
                    recoverFromLimit(action, isPro, newVictimIds)
                }
                postLimitDialog(retry = retry, heldArguments = action.arguments)
                return null
            }
        } else {
            log(TAG, INFO) { "A slot freed up meanwhile, creating without closing anything" }
        }

        val built = try {
            buildWorkspace(action.type, action.arguments, action.replace, action.id)
        } catch (e: Exception) {
            log(TAG, ERROR) { "Limit recovery could not build ${action.type}, closing nothing: ${e.asLog()}" }
            Bugs.report(e)
            return e
        }

        var committedId: Workspace.Id? = null
        try {
            // Re-checked per member rather than once up front: buildWorkspace and every release()
            // suspend, and Workspace.info is owned by the workspace, not by [lock] - a tab or one of
            // its stacked children can pick up unsaved changes while an earlier one is closing.
            // Skipping one is recoverable, closing one that just turned dirty is not.
            var closed = 0
            for (victim in victims) {
                if (closeUnitForRecovery(victim.id)) closed++
            }
            // The live count decides, not the bookkeeping: whatever the reason fewer slots came free,
            // committing on a cap that is still full is what must not happen. The tabs the user
            // picked and that were still safe are gone - they consented to that - but the create goes
            // back to a fresh dialog.
            if (countedTabCount() >= FREE_TIER_WORKSPACE_LIMIT) {
                log(TAG, WARN) { "Only $closed of ${victims.size} tabs could be closed, asking again" }
                built.release()
                postLimitDialog(
                    retry = { newVictimIds -> recoverFromLimit(action, isPro, newVictimIds) },
                    heldArguments = action.arguments,
                )
                return null
            }
            val newId = commitWorkspace(
                newWorkspace = built,
                idToReplace = action.replace,
                createdAt = action.createdAt,
                returnsResult = action.arguments is Workspace.ArgumentsForResult,
            )
            committedId = newId
            trackUsage(action, Clock.System.now())
            _events.emit(
                WorkspaceEvent.Created(
                    workspaceId = newId,
                    replacedId = action.replace,
                    autoFocus = action.autoFocus,
                    sourceWorkspaceId = action.sourceWorkspaceId,
                )
            )
            _events.emit(WorkspaceEvent.SelectionRequested(newId, action.sourceWorkspaceId))
        } catch (e: Exception) {
            log(TAG, ERROR) { "Limit recovery failed after building ${built.id}: ${e.asLog()}" }
            if (committedId == null) {
                // Never published, so nothing will ever release it for us
                try {
                    built.release()
                } catch (releaseError: Exception) {
                    log(TAG, ERROR) { "Releasing the abandoned ${built.id} failed: ${releaseError.asLog()}" }
                }
            }
            Bugs.report(e)
            return e
        }
        return null
    }

    /**
     * Applies the free-tier limit to [pendingCreates], preserving request order. Creates that don't
     * need a limit check (quota-exempt types, sub-workspaces, replaces, restores) always pass and
     * never consume a slot; the rest fill the slots still available right now. Pro users get the full
     * list. Reads the live tab count via [countedTabCount], so it is safe to re-apply at execution
     * time rather than trusting a list captured earlier.
     */
    private fun applyFreeTierLimit(
        pendingCreates: List<WorkspaceAction.Create>,
        isPro: Boolean,
    ): List<WorkspaceAction.Create> {
        if (isPro) return pendingCreates
        var remainingSlots = (FREE_TIER_WORKSPACE_LIMIT - countedTabCount()).coerceAtLeast(0)
        return pendingCreates.filter { req ->
            when {
                !req.needsLimitCheck -> true
                remainingSlots > 0 -> {
                    remainingSlots--
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Re-applies the free-tier limit against the current tab count and runs the batch. Called both
     * for immediate execution and from the confirmation callback — re-filtering here (instead of
     * trusting a list captured at planning time) is what stops a confirmation that resolves after the
     * user opened more tabs from pushing them past [FREE_TIER_WORKSPACE_LIMIT]. Must be called while
     * holding [lock].
     *
     * [isPro] is sampled once at request time (reading it suspends, and must not happen under [lock]);
     * an entitlement change while a confirmation dialog is open is not re-read here.
     */
    private suspend fun finalizeBatch(
        pendingCreates: List<WorkspaceAction.Create>,
        preResolved: Map<WorkspaceAction.Create, Workspace.Id>,
        deferredDupes: List<WorkspaceAction.Create>,
        isPro: Boolean,
        sourceWorkspaceId: Workspace.Id?,
    ): WorkspaceAction.CreateBatch.Result.Success {
        val allowedCreates = applyFreeTierLimit(pendingCreates, isPro)
        val limitSkipped = pendingCreates.size - allowedCreates.size
        if (limitSkipped > 0) {
            log(TAG, INFO) { "Workspace limit: allowing ${allowedCreates.size} new + ${preResolved.size} already-open, skipping $limitSkipped" }
            postLimitDialog()
        }

        // Nothing left to create: surface already-open entries without running a creation pass, so
        // no BatchCreationCompleted event fires (avoids a misleading "Opened 0 tabs" banner).
        // Deferred dupes count as skipped too - their primaries were all limit-filtered.
        if (allowedCreates.isEmpty()) {
            return WorkspaceAction.CreateBatch.Result.Success(
                results = preResolved.mapValues { (_, id) ->
                    WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen(id)
                },
                skippedCount = limitSkipped + deferredDupes.size,
            )
        }

        return executeBatchCreation(allowedCreates, preResolved, deferredDupes, limitSkipped, sourceWorkspaceId)
    }

    private suspend fun executeBatchCreation(
        requests: List<WorkspaceAction.Create>,
        preResolved: Map<WorkspaceAction.Create, Workspace.Id>,
        deferredDupes: List<WorkspaceAction.Create>,
        limitSkipped: Int,
        sourceWorkspaceId: Workspace.Id?,
    ): WorkspaceAction.CreateBatch.Result.Success {
        val results = mutableMapOf<WorkspaceAction.Create, WorkspaceAction.CreateBatch.CreationResult>()

        // Seed results with pre-resolved entries (instances that existed before this batch ran)
        preResolved.forEach { (req, existingId) ->
            results[req] = WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen(existingId)
        }

        requests.forEach { createRequest ->
            // Catches the case where two requests in the same batch target the same singleton type
            // or content path: first iteration creates the instance, subsequent iterations see it
            // via this check.
            (findExistingSingleton(createRequest) ?: findExistingContentMatch(createRequest))?.let { existingId ->
                log(TAG) { "Batch: ${createRequest.type} already open as $existingId, returning AlreadyOpen" }
                results[createRequest] = WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen(existingId)
                return@forEach
            }

            try {
                log(TAG) { "Creating workspace: ${createRequest.type}" }
                val newId = create(
                    type = createRequest.type,
                    arguments = createRequest.arguments,
                    idToReplace = createRequest.replace,
                    existingId = createRequest.id,
                    createdAt = createRequest.createdAt,
                )
                // Captured before the emit below can suspend, matching the single-create path
                val usedAt = Clock.System.now()
                _events.emit(
                    WorkspaceEvent.Created(
                        workspaceId = newId,
                        replacedId = createRequest.replace,
                        sourceWorkspaceId = sourceWorkspaceId,
                    )
                )
                results[createRequest] = WorkspaceAction.CreateBatch.CreationResult.Success(newId)
                trackUsage(createRequest, usedAt)
                log(TAG) { "Batch creation succeeded for ${createRequest.type}: $newId" }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Batch creation failed for ${createRequest.type}: ${e.asLog()}" }
                results[createRequest] = WorkspaceAction.CreateBatch.CreationResult.Failure(e)
            }
        }

        // Resolve same-type singleton and same-content-path duplicates that were deduped out of
        // [requests] for accounting: each maps to the now-created (or pre-existing) instance. A dup
        // that is data-class-equal to an already-recorded request collapses onto its key — keep that
        // entry (it is the Success) instead of overwriting it with AlreadyOpen.
        var deferredSkipped = 0
        deferredDupes.forEach { dup ->
            if (results.containsKey(dup)) return@forEach
            val instanceId = dup.dedupContentPath?.let { findContentPathHolder(dup.type, it) }
                ?: _workspaces.value.firstOrNull { it.type == dup.type && !it.info.value.isSubWorkspace }
                    ?.id?.takeIf { dup.createsSingletonTab }
            if (instanceId != null) {
                results[dup] = WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen(instanceId)
            } else {
                // No instance means the dup's primary never opened (limit-filtered or failed);
                // the duplicate shares that fate instead of surfacing a bogus Failure of its own
                log(TAG, INFO) { "Batch: deferred dup ${dup.type} has no instance, counting as skipped" }
                deferredSkipped++
            }
        }
        val totalSkipped = limitSkipped + deferredSkipped

        val successCount = results.values.count { it is WorkspaceAction.CreateBatch.CreationResult.Success }
        val failureCount = results.values.count { it is WorkspaceAction.CreateBatch.CreationResult.Failure }
        val alreadyOpenCount = results.values.count { it is WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen }

        log(TAG, INFO) {
            "Batch creation completed: $successCount succeeded, $failureCount failed, $alreadyOpenCount already-open"
        }

        _events.emit(
            WorkspaceEvent.BatchCreationCompleted(
                successCount = successCount,
                failureCount = failureCount,
                skippedCount = totalSkipped,
                sourceWorkspaceId = sourceWorkspaceId,
            )
        )

        return WorkspaceAction.CreateBatch.Result.Success(
            results = results,
            skippedCount = totalSkipped,
        )
    }

    /**
     * Everything a close of [workspaceId] destroys: the workspace itself plus every descendant,
     * walking the same caller relation [executeClose] recurses over.
     *
     * The walk starts from the id rather than from a resolved workspace, because [executeClose]
     * enumerates children before it looks the target up: an id nothing holds still reaps whatever
     * names it as caller, and those orphans are exactly what a guard must not miss.
     *
     * Cycle-guarded for the same reason [executeClose] is: caller ids are not validated at creation.
     */
    private fun closingSubtreeOf(workspaceId: Workspace.Id): List<Workspace<*>> {
        val ids = closingIdsOf(workspaceId)
        return _workspaces.value.filter { it.id in ids }
    }

    /**
     * [closingSubtreeOf] as bare ids, always including [workspaceId] itself even when nothing holds
     * it. Two closes overlap exactly when either one's id set contains the other's target, which is
     * what tells a confirmation that a second one would decide the same workspaces over again.
     */
    private fun closingIdsOf(workspaceId: Workspace.Id): Set<Workspace.Id> {
        val childrenOf = _workspaces.value.groupBy { it.info.value.callerWorkspaceId }
        val ids = mutableSetOf<Workspace.Id>()
        val pending = ArrayDeque(listOf(workspaceId))
        while (pending.isNotEmpty()) {
            val nextId = pending.removeFirst()
            if (!ids.add(nextId)) continue
            childrenOf[nextId].orEmpty().forEach { pending += it.id }
        }
        return ids
    }

    private suspend fun executeClose(
        workspaceId: Workspace.Id,
        visited: MutableSet<Workspace.Id> = mutableSetOf(),
    ) {
        // Caller ids are not validated at creation time, so a cycle is reachable; without this the
        // recursion below never terminates, because a member is only removed after its children close.
        if (!visited.add(workspaceId)) {
            log(TAG, WARN) { "Cyclic ownership: $workspaceId already closing, not recursing" }
            return
        }

        // Cancel any pending confirmations for this workspace - both the ones hosted in its pane
        // and the ones asking about it. A confirmation hosted elsewhere would otherwise survive its
        // subject: a blocking dialog naming a dead tab, whose confirm re-runs this for a workspace
        // that no longer exists and emits a second Closed event.
        _pendingConfirmations.value
            .filter { (_, confirmation) ->
                val data = confirmation.data
                confirmation.sourceWorkspaceId == workspaceId ||
                    (data is PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation &&
                        data.workspaceId == workspaceId)
            }
            .forEach { (confirmationId, _) ->
                log(TAG, INFO) { "Workspace closing, cancelling confirmation $confirmationId" }
                _pendingConfirmations.update { it - confirmationId }
                pendingActions.remove(confirmationId)
            }

        // Close all child workspaces first — recursive, children may have their own sub-workspaces
        val childWorkspaces = _workspaces.value.filter { it.info.value.callerWorkspaceId == workspaceId }
        if (childWorkspaces.isNotEmpty()) {
            log(TAG) { "Auto-closing ${childWorkspaces.size} child workspace(s)" }
            childWorkspaces.forEach { executeClose(it.id, visited) }
        }

        // Get caller workspace ID before removal (for returning to caller)
        val closingWorkspace = _workspaces.value.find { it.id == workspaceId }
        val callerWorkspaceId = closingWorkspace?.info?.value?.callerWorkspaceId

        // A stuck release must not abort the close half-way: everything below still has to happen,
        // or the workspace stays listed while its instance is already (partially) torn down.
        try {
            closingWorkspace?.release()
        } catch (e: Exception) {
            log(TAG, ERROR) { "release() of $workspaceId failed, closing it anyway: ${e.asLog()}" }
        }
        closingWorkspace?.let { operationsManager.removeWorkspace(it.id) }
        // Leak guard: a claimant that closes mid-open must not block its path forever
        contentClaims.entries.removeAll { (_, owner) -> owner == workspaceId }
        // A custom name must never outlive its tab and leak onto a workspace reusing the id
        _customTitles.update { it - workspaceId }
        createdAtById = createdAtById - workspaceId
        resultReturningIds = resultReturningIds - workspaceId
        _workspaces.value = _workspaces.value.filter { it.id != workspaceId }
        _events.emit(WorkspaceEvent.Closed(workspaceId = workspaceId, callerWorkspaceId = callerWorkspaceId))
    }

    companion object {
        private val TAG = logTag("Workspace", "Repo")
        private const val CONFIRMATION_THRESHOLD = 5
        const val FREE_TIER_WORKSPACE_LIMIT = 5
    }

}
