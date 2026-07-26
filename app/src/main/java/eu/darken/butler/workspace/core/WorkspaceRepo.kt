package eu.darken.butler.workspace.core

import eu.darken.butler.common.ca.CaString
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
import eu.darken.butler.upgrade.isPro
import eu.darken.butler.workspace.core.operations.OperationsManager
import kotlinx.coroutines.CoroutineScope
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
import kotlin.uuid.Uuid

@Singleton
class WorkspaceRepo @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val factoryMap: Map<Workspace.Type, @JvmSuppressWildcards WorkspaceFactory<*>>,
    private val workspaceSettings: WorkspaceSettings,
    private val operationsManager: OperationsManager,
    private val upgradeRepo: UpgradeRepo,
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

    private val _pendingConfirmations = MutableStateFlow<Map<String, PendingWorkspaceConfirmation>>(emptyMap())
    val pendingConfirmations: Flow<Map<String, PendingWorkspaceConfirmation>> = _pendingConfirmations
        .setupCommonEventHandlers(TAG, enabled = Bugs.isDebug) { "PendingConfirmations" }
        .replayingShare(appScope)

    private val pendingActions = ConcurrentHashMap<String, suspend () -> Unit>()

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
     * latency let focus land on a dormant stand-in after restore had finished, which then hydrated
     * a tab that was supposed to stay dormant.
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
        .setupCommonEventHandlers(TAG, enabled = Bugs.isTrace) { "WorkspaceState" }
        .replayingShare(appScope)

    override val events: Flow<WorkspaceEvent> = _events
        .setupCommonEventHandlers(TAG, enabled = Bugs.isDebug) { "WorkspaceEvents" }
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
    ): Workspace.Id {
        log(TAG) { "create($type, $arguments, $idToReplace, existingId=$existingId)" }
        val wip = _workspaces.value.toMutableList()

        // Honoring a caller-supplied id (single create and batch) must never append a duplicate id —
        // that would break retrieve/close/reorder and event targeting. Reusing the id of the tab being
        // replaced is the one legitimate collision.
        if (existingId != null && existingId != idToReplace && wip.any { it.id == existingId }) {
            throw IllegalStateException("Cannot create workspace with id $existingId: already in use")
        }

        @Suppress("UNCHECKED_CAST")
        val factory = factoryMap[type] as? WorkspaceFactory<Workspace.Arguments>
            ?: throw IllegalArgumentException("No factory found for workspace type: $type")
        val newWorkspace = factory.create(
            id = existingId ?: Workspace.Id(),
            arguments = arguments
        ) as Workspace<out Workspace.Arguments>
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

        _workspaces.value = wip

        if (idToReplace != null && newWorkspace.id != idToReplace) {
            // Close sub-workspaces orphaned by the replace — their parent instance is gone
            _workspaces.value
                .filter { it.info.value.callerWorkspaceId == idToReplace }
                .forEach { executeClose(it.id) }
        }

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

        return newWorkspace.id
    }

    /**
     * Identity a dormant stand-in shows, from the type's own [WorkspaceFactory.deriveDisplay].
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
     * Dormant stand-ins are reported as absent: typed consumers cast the result to their concrete
     * workspace type, so a dormant id must behave exactly like an id that doesn't exist yet — the
     * flow emits the instance once [WorkspaceAction.Hydrate] has swapped it in.
     */
    override fun retrieve(id: Workspace.Id): Flow<Workspace<out Workspace.Arguments>?> {
        return _workspaces.flatMapLatest { wss ->
            flowOf(wss.singleOrNull { it.id == id }?.takeIf { it !is DormantWorkspace })
        }
    }

    /**
     * Current instance for [id] INCLUDING dormant stand-ins. Only for session saving, which must
     * serialize the held arguments of workspaces that were never hydrated; everything else uses
     * [retrieve], which hides dormant entries.
     */
    fun peek(id: Workspace.Id): Workspace<out Workspace.Arguments>? = _workspaces.value.singleOrNull { it.id == id }

    fun resolveConfirmation(confirmationId: String, confirmed: Boolean) {
        log(TAG, INFO) { "resolveConfirmation($confirmationId, confirmed=$confirmed)" }
        _pendingConfirmations.update { it - confirmationId }
        val action = pendingActions.remove(confirmationId)
        if (confirmed && action != null) {
            appScope.launch {
                lock.withLock { action() }
            }
        }
    }

    override suspend fun execute(action: WorkspaceAction): WorkspaceAction.Result {
        // Read outside the lock: isPro() suspends on the upgrade info flow and must not stall the repo.
        val isPro = when (action) {
            is WorkspaceAction.Create -> action.needsLimitCheck && upgradeRepo.isPro()
            is WorkspaceAction.CreateBatch -> upgradeRepo.isPro()
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
                    postLimitDialog()
                    return@withLock WorkspaceAction.Create.Result.LimitReached
                }

                val newId = create(
                    type = action.type,
                    arguments = action.arguments,
                    idToReplace = action.replace,
                    existingId = action.id,
                )
                log(TAG) { "New workspace created with ID $newId, emitting event" }
                _events.emit(
                    WorkspaceEvent.Created(
                        workspaceId = newId,
                        replacedId = action.replace,
                        autoFocus = action.autoFocus,
                    )
                )

                WorkspaceAction.Create.Result.Success(newId)
            }

            is WorkspaceAction.RegisterDormant -> {
                log(TAG, INFO) { "Registering dormant workspace ${action.id} (${action.type})" }
                try {
                    if (_workspaces.value.any { it.id == action.id }) {
                        throw IllegalStateException("Cannot register dormant workspace ${action.id}: id already in use")
                    }
                    // A stand-in displaying one type while holding another's arguments would also
                    // fail hydration permanently: the factory picked by type gets the wrong arguments
                    if (action.type != action.arguments.type) {
                        throw IllegalArgumentException(
                            "Cannot register dormant workspace ${action.id}: type ${action.type} " +
                                "does not match arguments type ${action.arguments.type}"
                        )
                    }
                    val display = deriveDisplay(action.type, action.arguments)
                    val dormant = DormantWorkspace(
                        id = action.id,
                        type = action.type,
                        heldArguments = action.arguments,
                        title = display?.title ?: action.type.label,
                        subtitle = display?.subtitle,
                    )
                    _workspaces.value = _workspaces.value + dormant
                    _events.emit(
                        WorkspaceEvent.Created(
                            workspaceId = dormant.id,
                            replacedId = null,
                            autoFocus = false,
                        )
                    )
                    WorkspaceAction.RegisterDormant.Result.Success(dormant.id)
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Failed to register dormant workspace ${action.id}: ${e.asLog()}" }
                    WorkspaceAction.RegisterDormant.Result.Failed(e)
                }
            }

            is WorkspaceAction.Hydrate -> {
                // Not dormant (already hydrated by a concurrent call, or never dormant) is a no-op,
                // which is what keeps double hydration from running the factory twice.
                val dormant = _workspaces.value.firstOrNull { it.id == action.id } as? DormantWorkspace
                if (dormant == null) {
                    log(TAG) { "Hydrate(${action.id}): unknown or not dormant, nothing to do" }
                    WorkspaceAction.Hydrate.Result.NoOp
                } else {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val factory = factoryMap[dormant.type] as? WorkspaceFactory<Workspace.Arguments>
                            ?: throw IllegalArgumentException("No factory found for workspace type: ${dormant.type}")
                        val hydrated = factory.create(
                            id = dormant.id,
                            arguments = dormant.heldArguments,
                        ) as Workspace<out Workspace.Arguments>

                        val wip = _workspaces.value.toMutableList()
                        val index = wip.indexOfFirst { it.id == action.id }
                        wip[index] = hydrated
                        _workspaces.value = wip

                        log(TAG, INFO) { "Hydrated workspace ${action.id} (${dormant.type})" }
                        WorkspaceAction.Hydrate.Result.Success(action.id)
                    } catch (e: Exception) {
                        log(TAG, ERROR) { "Failed to hydrate workspace ${action.id}: ${e.asLog()}" }
                        dormant.markHydrationError(e)
                        WorkspaceAction.Hydrate.Result.Failed(e)
                    }
                }
            }

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

                // Confirm when explicitly requested OR when the workspace has unsaved changes.
                val needsConfirmation = action.requireConfirmation ||
                    workspace?.info?.value?.hasUnsavedChanges == true

                if (needsConfirmation) {
                    if (workspace == null) {
                        log(TAG, WARN) { "Cannot request close confirmation - workspace ${action.id} not found" }
                        return@withLock WorkspaceAction.Close.Result
                    }

                    // De-dupe: don't queue a second close confirmation for the same workspace.
                    val alreadyPending = _pendingConfirmations.value.values.any {
                        val data = it.data
                        data is PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation &&
                            data.workspaceId == action.id
                    }
                    if (alreadyPending) {
                        log(TAG) { "Close confirmation already pending for ${action.id}, ignoring duplicate" }
                        return@withLock WorkspaceAction.Close.Result
                    }

                    val workspaceInfo = workspace.info.value.withCustomTitle(_customTitles.value)
                    val confirmationId = Uuid.random().toString()

                    log(TAG, INFO) { "Requesting confirmation to close workspace: ${workspaceInfo.displayTitle}" }

                    pendingActions[confirmationId] = {
                        executeClose(action.id)
                    }

                    _pendingConfirmations.update {
                        it + (confirmationId to PendingWorkspaceConfirmation(
                            id = confirmationId,
                            sourceWorkspaceId = action.id,
                            data = PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation(
                                workspaceId = action.id,
                                workspaceTitle = workspaceInfo.displayTitle,
                                hasUnsavedChanges = workspaceInfo.hasUnsavedChanges,
                            ),
                        ))
                    }

                    return@withLock WorkspaceAction.Close.Result
                }

                executeClose(action.id)

                WorkspaceAction.Close.Result
            }
            is WorkspaceAction.Reorder -> {
                log(TAG, INFO) { "Reordering workspaces: ${action.workspaceIds}" }

                val current = _workspaces.value
                log(TAG) { "BEFORE re-order:\n${current.joinToString("\n")}" }
                val reordered = action.workspaceIds.mapNotNull { id ->
                    current.find { it.id == id }
                }
                log(TAG) { "AFTER re-order:\n${reordered.joinToString("\n")}" }

                if (reordered.size != current.size) {
                    log(TAG, ERROR) { "Reorder failed: size mismatch. Expected ${current.size}, got ${reordered.size}" }
                    return WorkspaceAction.Reorder.Result(false)
                }

                _workspaces.value = reordered
                _events.emit(WorkspaceEvent.Reordered(workspaceIds = action.workspaceIds))

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
            WorkspaceAction.CloseAll -> {
                log(TAG, INFO) { "Closing all workspaces" }
                _workspaces.value.forEach {
                    it.release()
                    operationsManager.removeWorkspace(it.id)
                }
                _workspaces.value = emptyList()
                contentClaims.clear()
                _customTitles.value = emptyMap()
                _events.emit(WorkspaceEvent.AllClosed)

                WorkspaceAction.CloseAll.Result
            }
        }
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
     * Number of open tab workspaces that count toward [FREE_TIER_WORKSPACE_LIMIT]: excludes modal
     * sub-workspaces and quota-exempt types ([Workspace.Type.isQuotaExempt]).
     */
    private fun countedTabCount(): Int =
        _workspaces.value.count { !it.info.value.isSubWorkspace && !it.type.isQuotaExempt }

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
     * [findExistingSingleton]'s gating: sub-workspace creates and session restoration
     * ([WorkspaceAction.Create.skipLimitCheck]) never dedup.
     */
    private val WorkspaceAction.Create.dedupContentPath: APath<*>?
        get() {
            if (arguments.isForSubWorkspace) return null
            if (skipLimitCheck) return null
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

    private fun postLimitDialog() {
        val confirmationId = Uuid.random().toString()
        val currentCount = countedTabCount()

        _pendingConfirmations.update {
            it + (confirmationId to PendingWorkspaceConfirmation(
                id = confirmationId,
                sourceWorkspaceId = null,
                data = PendingWorkspaceConfirmation.ConfirmationData.WorkspaceLimitReached(
                    currentCount = currentCount,
                    limit = FREE_TIER_WORKSPACE_LIMIT,
                ),
            ))
        }
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
                )
                _events.emit(
                    WorkspaceEvent.Created(
                        workspaceId = newId,
                        replacedId = createRequest.replace,
                    )
                )
                results[createRequest] = WorkspaceAction.CreateBatch.CreationResult.Success(newId)
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

    private suspend fun executeClose(workspaceId: Workspace.Id) {
        // Cancel any pending confirmations for this workspace
        _pendingConfirmations.value
            .filter { (_, confirmation) -> confirmation.sourceWorkspaceId == workspaceId }
            .forEach { (confirmationId, _) ->
                log(TAG, INFO) { "Workspace closing, cancelling confirmation $confirmationId" }
                _pendingConfirmations.update { it - confirmationId }
                pendingActions.remove(confirmationId)
            }

        // Close all child workspaces first — recursive, children may have their own sub-workspaces
        val childWorkspaces = _workspaces.value.filter { it.info.value.callerWorkspaceId == workspaceId }
        if (childWorkspaces.isNotEmpty()) {
            log(TAG) { "Auto-closing ${childWorkspaces.size} child workspace(s)" }
            childWorkspaces.forEach { executeClose(it.id) }
        }

        // Get caller workspace ID before removal (for returning to caller)
        val closingWorkspace = _workspaces.value.find { it.id == workspaceId }
        val callerWorkspaceId = closingWorkspace?.info?.value?.callerWorkspaceId

        closingWorkspace?.release()
        closingWorkspace?.let { operationsManager.removeWorkspace(it.id) }
        // Leak guard: a claimant that closes mid-open must not block its path forever
        contentClaims.entries.removeAll { (_, owner) -> owner == workspaceId }
        // A custom name must never outlive its tab and leak onto a workspace reusing the id
        _customTitles.update { it - workspaceId }
        _workspaces.value = _workspaces.value.filter { it.id != workspaceId }
        _events.emit(WorkspaceEvent.Closed(workspaceId = workspaceId, callerWorkspaceId = callerWorkspaceId))
    }

    companion object {
        private val TAG = logTag("Workspace", "Repo")
        private const val CONFIRMATION_THRESHOLD = 5
        const val FREE_TIER_WORKSPACE_LIMIT = 5
    }

}
