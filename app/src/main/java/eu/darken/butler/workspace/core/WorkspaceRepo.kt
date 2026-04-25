package eu.darken.butler.workspace.core

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.Bugs
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
import kotlinx.coroutines.flow.first
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

    private val _pendingConfirmations = MutableStateFlow<Map<String, PendingWorkspaceConfirmation>>(emptyMap())
    val pendingConfirmations: Flow<Map<String, PendingWorkspaceConfirmation>> = _pendingConfirmations
        .setupCommonEventHandlers(TAG, enabled = Bugs.isDebug) { "PendingConfirmations" }
        .replayingShare(appScope)

    private val pendingActions = ConcurrentHashMap<String, suspend () -> Unit>()
    private val infos: Flow<List<Workspace.Info>> = _workspaces.flatMapLatest { workspaces ->
        if (workspaces.isEmpty()) {
            flowOf(emptyList())
        } else {
            val infoFlows = workspaces.map { it.info }
            combine(infoFlows) { infos -> infos.toList() }
        }
    }

    override val state: Flow<WorkspaceRemote.State> = combine(
        infos,
        workspaceSettings.layoutModePortrait.flow,
        workspaceSettings.layoutModeLandscape.flow,
    ) { workspaceInfos, layoutModePortrait, layoutModeLandscape ->
        WorkspaceRemote.State(
            infos = workspaceInfos,
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

    private fun create(
        type: Workspace.Type,
        arguments: Workspace.Arguments,
        idToReplace: Workspace.Id? = null,
        existingId: Workspace.Id? = null,
    ): Workspace.Id {
        log(TAG) { "create($type, $arguments, $idToReplace, existingId=$existingId)" }
        val wip = _workspaces.value.toMutableList()

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
            log(TAG) { "Replacing workspace at index $index" }
            wip[index] = newWorkspace
        } else {
            wip.add(newWorkspace)
        }

        _workspaces.value = wip

        return newWorkspace.id
    }

    override fun retrieve(id: Workspace.Id): Flow<Workspace<out Workspace.Arguments>?> {
        return _workspaces.flatMapLatest { wss ->
            flowOf(wss.singleOrNull { it.id == id })
        }
    }

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

    override suspend fun execute(action: WorkspaceAction): WorkspaceAction.Result = lock.withLock {
        log(TAG, INFO) { "execute($action)" }
        when (action) {
            is WorkspaceAction.Create -> {
                log(TAG, INFO) { "Creating new workspace with $action" }

                // Singleton enforcement: refuse duplicates of singleton workspace types
                findExistingSingleton(action)?.let { existingId ->
                    log(TAG, INFO) { "Singleton ${action.type} already open as $existingId, returning AlreadyOpen" }
                    return@withLock WorkspaceAction.Create.Result.AlreadyOpen(existingId)
                }

                // Check workspace limit for non-pro users
                if (!canCreateWorkspace(action)) {
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

            is WorkspaceAction.CreateBatch -> {
                log(TAG, INFO) { "Creating batch of ${action.requests.size} workspaces" }

                if (action.requests.size != action.requests.toSet().size) {
                    log(TAG, WARN) {
                        "Batch contains duplicate Create requests; equal entries will collapse in result map"
                    }
                }

                // Pre-resolve singletons that already exist BEFORE applying the free-tier limit.
                // AlreadyOpen entries do not consume tab slots — they just refocus an existing tab.
                val preResolvedSingletons = mutableMapOf<WorkspaceAction.Create, Workspace.Id>()
                val pendingCreates = mutableListOf<WorkspaceAction.Create>()
                action.requests.forEach { req ->
                    val existingId = findExistingSingleton(req)
                    if (existingId != null) {
                        preResolvedSingletons[req] = existingId
                    } else {
                        pendingCreates += req
                    }
                }

                // Check workspace limit for free users — counted only against pending creates
                val isPro = upgradeRepo.isPro()
                val allowedCreates = if (isPro) {
                    pendingCreates
                } else {
                    // Count current tab workspaces (exclude sub-workspaces)
                    val currentTabCount = _workspaces.value.count { ws ->
                        val info = ws.info.first()
                        !info.isSubWorkspace
                    }
                    val remainingSlots = (FREE_TIER_WORKSPACE_LIMIT - currentTabCount).coerceAtLeast(0)

                    if (remainingSlots == 0 && pendingCreates.isNotEmpty()) {
                        log(TAG, INFO) { "Workspace limit reached, no slots available for batch creation" }
                        postLimitDialog()
                        return@withLock WorkspaceAction.CreateBatch.Result.Success(
                            results = preResolvedSingletons.mapValues { (_, id) ->
                                WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen(id)
                            },
                            skippedCount = pendingCreates.size,
                        )
                    }

                    pendingCreates.take(remainingSlots)
                }

                val limitSkipped = pendingCreates.size - allowedCreates.size
                if (limitSkipped > 0) {
                    log(TAG, INFO) { "Workspace limit: allowing ${allowedCreates.size} new + ${preResolvedSingletons.size} singleton-already-open, skipping $limitSkipped" }
                    postLimitDialog()
                }

                // Check if confirmation is needed (based on real new tabs only)
                val needsConfirmation = allowedCreates.size >= CONFIRMATION_THRESHOLD

                if (needsConfirmation) {
                    log(TAG, INFO) {
                        "Batch size (${allowedCreates.size}) >= threshold ($CONFIRMATION_THRESHOLD), requesting confirmation"
                    }
                    val confirmationId = Uuid.random().toString()

                    pendingActions[confirmationId] = {
                        executeBatchCreation(allowedCreates, preResolvedSingletons, limitSkipped, action.sourceWorkspaceId)
                    }

                    _pendingConfirmations.update {
                        it + (confirmationId to PendingWorkspaceConfirmation(
                            id = confirmationId,
                            sourceWorkspaceId = action.sourceWorkspaceId,
                            data = PendingWorkspaceConfirmation.ConfirmationData.BatchWorkspaceCreation(
                                totalCount = allowedCreates.size,
                                skippedCount = limitSkipped,
                            ),
                        ))
                    }

                    return@withLock WorkspaceAction.CreateBatch.Result.AwaitingConfirmation
                }

                executeBatchCreation(allowedCreates, preResolvedSingletons, limitSkipped, action.sourceWorkspaceId)
            }

            is WorkspaceAction.Close -> {
                log(TAG, INFO) { "Closing workspace with id ${action.id}" }

                // Request confirmation if required
                if (action.requireConfirmation) {
                    val workspace = _workspaces.value.firstOrNull { it.id == action.id }
                    if (workspace == null) {
                        log(TAG, WARN) { "Cannot request close confirmation - workspace ${action.id} not found" }
                        return@withLock WorkspaceAction.Close.Result
                    }

                    val workspaceInfo = workspace.info.first()
                    val confirmationId = Uuid.random().toString()

                    log(TAG, INFO) { "Requesting confirmation to close workspace: ${workspaceInfo.title}" }

                    pendingActions[confirmationId] = {
                        executeClose(action.id)
                    }

                    _pendingConfirmations.update {
                        it + (confirmationId to PendingWorkspaceConfirmation(
                            id = confirmationId,
                            sourceWorkspaceId = action.id,
                            data = PendingWorkspaceConfirmation.ConfirmationData.WorkspaceCloseConfirmation(
                                workspaceId = action.id,
                                workspaceTitle = workspaceInfo.title,
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
            WorkspaceAction.CloseAll -> {
                log(TAG, INFO) { "Closing all workspaces" }
                _workspaces.value.forEach {
                    it.release()
                    operationsManager.removeWorkspace(it.id)
                }
                _workspaces.value = emptyList()
                _events.emit(WorkspaceEvent.AllClosed)

                WorkspaceAction.CloseAll.Result
            }
        }
    }

    private suspend fun canCreateWorkspace(action: WorkspaceAction.Create): Boolean {
        // Skip check if explicitly requested (e.g., session restoration)
        if (action.skipLimitCheck) return true

        // Sub-workspaces (modals/pickers) don't count toward limit
        if (action.arguments.isForSubWorkspace) return true

        // Replace operations don't increase count
        if (action.replace != null) return true

        // Pro users have no limit
        if (upgradeRepo.isPro()) return true

        // Check count of tab workspaces (exclude sub-workspaces)
        val currentTabCount = _workspaces.value.count { ws ->
            val info = ws.info.first()
            !info.isSubWorkspace
        }

        return currentTabCount < FREE_TIER_WORKSPACE_LIMIT
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
    private suspend fun findExistingSingleton(action: WorkspaceAction.Create): Workspace.Id? {
        if (!action.type.isSingleton) return null
        if (action.arguments.isForSubWorkspace) return null
        if (action.skipLimitCheck) return null

        val existingId = _workspaces.value.firstOrNull { ws ->
            if (ws.type != action.type) return@firstOrNull false
            val info = ws.info.first()
            !info.isSubWorkspace
        }?.id ?: return null

        // Replacing the existing singleton itself is legitimate (templates "morph in place")
        if (action.replace == existingId) return null

        return existingId
    }

    private suspend fun postLimitDialog() {
        val confirmationId = Uuid.random().toString()
        val currentCount = _workspaces.value.count { ws ->
            val info = ws.info.first()
            !info.isSubWorkspace
        }

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

    private suspend fun executeBatchCreation(
        requests: List<WorkspaceAction.Create>,
        preResolvedSingletons: Map<WorkspaceAction.Create, Workspace.Id>,
        limitSkipped: Int,
        sourceWorkspaceId: Workspace.Id?,
    ): WorkspaceAction.CreateBatch.Result.Success {
        val results = mutableMapOf<WorkspaceAction.Create, WorkspaceAction.CreateBatch.CreationResult>()

        // Seed results with pre-resolved singletons (instances that existed before this batch ran)
        preResolvedSingletons.forEach { (req, existingId) ->
            results[req] = WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen(existingId)
        }

        requests.forEach { createRequest ->
            // Catches the case where two requests in the same batch target the same singleton type:
            // first iteration creates the instance, subsequent iterations see it via this check.
            findExistingSingleton(createRequest)?.let { existingId ->
                log(TAG) { "Batch: ${createRequest.type} singleton already open as $existingId, returning AlreadyOpen" }
                results[createRequest] = WorkspaceAction.CreateBatch.CreationResult.AlreadyOpen(existingId)
                return@forEach
            }

            try {
                log(TAG) { "Creating workspace: ${createRequest.type}" }
                val newId = create(
                    type = createRequest.type,
                    arguments = createRequest.arguments,
                    idToReplace = createRequest.replace,
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
                skippedCount = limitSkipped,
                sourceWorkspaceId = sourceWorkspaceId,
            )
        )

        return WorkspaceAction.CreateBatch.Result.Success(
            results = results,
            skippedCount = limitSkipped,
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

        // Find and close all child workspaces owned by this workspace
        val childWorkspaces = _workspaces.value.filter { ws ->
            val info = ws.info.first()
            info.callerWorkspaceId == workspaceId
        }
        if (childWorkspaces.isNotEmpty()) {
            log(TAG) { "Auto-closing ${childWorkspaces.size} child workspace(s)" }
            childWorkspaces.forEach { childWs ->
                childWs.release()
                operationsManager.removeWorkspace(childWs.id)
                _workspaces.value = _workspaces.value.filter { it.id != childWs.id }
                _events.emit(WorkspaceEvent.Closed(workspaceId = childWs.id))
            }
        }

        // Get caller workspace ID before removal (for returning to caller)
        val closingWorkspace = _workspaces.value.find { it.id == workspaceId }
        val callerWorkspaceId = closingWorkspace?.info?.first()?.callerWorkspaceId

        closingWorkspace?.release()
        closingWorkspace?.let { operationsManager.removeWorkspace(it.id) }
        _workspaces.value = _workspaces.value.filter { it.id != workspaceId }
        _events.emit(WorkspaceEvent.Closed(workspaceId = workspaceId, callerWorkspaceId = callerWorkspaceId))
    }

    companion object {
        private val TAG = logTag("Workspace", "Repo")
        private const val CONFIRMATION_THRESHOLD = 5
        const val FREE_TIER_WORKSPACE_LIMIT = 5
    }

}
