package eu.darken.butler.workspace.core

import eu.darken.butler.apps.core.AppsWorkspace
import eu.darken.butler.apps.core.details.AppDetailsWorkspace
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.flow.replayingShare
import eu.darken.butler.common.flow.setupCommonEventHandlers
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.explorer.core.ExplorerWorkspace
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.templates.core.TemplatesWorkspace
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.session.AppArgumentsSerializer
import eu.darken.butler.workspace.core.session.AppWorkspaceStateExtractor
import eu.darken.butler.workspace.core.session.WorkspaceSessionData
import eu.darken.butler.workspace.core.session.WorkspaceSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class WorkspaceRepo @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val templatesWorkspaceFactory: TemplatesWorkspace.Factory,
    private val explorerWorkspaceFactory: ExplorerWorkspace.Factory,
    private val searcherWorkspaceFactory: SearcherWorkspace.Factory,
    private val editorWorkspaceFactory: EditorWorkspace.Factory,
    private val appsWorkspaceFactory: AppsWorkspace.Factory,
    private val appDetailsWorkspaceFactory: AppDetailsWorkspace.Factory,
    private val workspaceSettings: WorkspaceSettings,
    private val operationsManager: OperationsManager,
    private val sessionManager: WorkspaceSessionManager,
    private val argumentsSerializer: AppArgumentsSerializer,
    private val stateExtractor: AppWorkspaceStateExtractor,
) : WorkspaceProvider, WorkspaceRemote {

    private val lock = Mutex()
    private val _workspaces = MutableStateFlow<List<Workspace>>(emptyList())
    private val _events = MutableSharedFlow<WorkspaceEvent>()

    // Generic confirmation system
    data class PendingConfirmation(
        val id: String,
        val sourceWorkspaceId: Workspace.Id?,
        val data: ConfirmationData,
    )

    sealed interface ConfirmationData {
        /**
         * Confirmation for creating multiple workspaces at once
         */
        data class BatchWorkspaceCreation(
            val totalCount: Int,
            val skippedCount: Int = 0,
        ) : ConfirmationData

        /**
         * Confirmation for closing a workspace
         */
        data class WorkspaceCloseConfirmation(
            val workspaceId: Workspace.Id,
            val workspaceTitle: CaString,
        ) : ConfirmationData

        // Future confirmation types can be added here:
        // data class BulkDelete(val itemCount: Int, val itemType: String) : ConfirmationData
        // data class DangerousOperation(val message: String) : ConfirmationData
    }

    private val _pendingConfirmations = MutableStateFlow<Map<String, PendingConfirmation>>(emptyMap())
    val pendingConfirmations: Flow<Map<String, PendingConfirmation>> = _pendingConfirmations
        .setupCommonEventHandlers(TAG, enabled = Bugs.isDebug) { "PendingConfirmations" }
        .replayingShare(appScope)

    private val confirmationContinuations = mutableMapOf<String, kotlin.coroutines.Continuation<Boolean>>()
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
        .setupCommonEventHandlers(TAG, enabled = Bugs.isTrace) { "WorkspaceState" }
        .replayingShare(appScope)

    override val events: Flow<WorkspaceEvent> = _events
        .setupCommonEventHandlers(TAG, enabled = Bugs.isDebug) { "WorkspaceEvents" }
        .replayingShare(appScope)

    init {
        // Auto-save session when workspace state changes
        state
            .map { it.infos }
            .distinctUntilChanged()
            .debounce(500.milliseconds)
            .onEach {
                if (workspaceSettings.sessionRestoreEnabled.value()) {
                    saveCurrentSession()
                }
            }
            .catch { e -> log(TAG, ERROR) { "Auto-save session failed: ${e.asLog()}" } }
            .launchIn(appScope)
    }

    override suspend fun emitEvent(event: WorkspaceEvent) {
        log(TAG) { "emitEvent($event)" }
        _events.emit(event)
    }

    private fun create(
        type: Workspace.Type,
        arguments: Workspace.Arguments? = null,
        idToReplace: Workspace.Id? = null,
    ): Workspace.Id {
        log(TAG) { "create($type, $arguments, $idToReplace)" }
        val wip = _workspaces.value.toMutableList()

        val newWorkspace = when (type) {
            Workspace.Type.TEMPLATES -> templatesWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments as TemplatesWorkspace.Arguments?
            )
            Workspace.Type.EXPLORER -> explorerWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments
            )
            Workspace.Type.SEARCHER -> searcherWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments as SearcherWorkspace.Arguments?
            )
            Workspace.Type.EDITOR -> editorWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments as EditorWorkspace.Arguments?
            )
            Workspace.Type.APPS -> appsWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments as AppsWorkspace.Arguments?
            )
            Workspace.Type.APP_DETAILS -> appDetailsWorkspaceFactory.create(
                id = Workspace.Id(),
                arguments = arguments
            )
        }
        if (idToReplace != null) {
            val index = wip.indexOfFirst { it.id == idToReplace }
            if (index == -1) throw IllegalStateException("Tab not found")
            log(TAG) { "Replacing workspace at index $index" }
            wip[index] = newWorkspace
        } else {
            wip.add(newWorkspace)
        }

        _workspaces.value = wip

        // Track parent-child relationship for sub-workspaces
        if (arguments is Workspace.ArgumentsForResult) {
            val callerId = arguments.callerWorkspaceId
            if (callerId != null) {
                log(TAG) { "Created sub-workspace: ${newWorkspace.id} -> caller: $callerId" }
            }
        }

        return newWorkspace.id
    }

    override fun retrieve(id: Workspace.Id): Flow<Workspace?> {
        return _workspaces.map { wss -> wss.singleOrNull { it.id == id } }
    }

    fun resolveConfirmation(confirmationId: String, confirmed: Boolean) {
        log(TAG, INFO) { "resolveConfirmation($confirmationId, confirmed=$confirmed)" }
        confirmationContinuations.remove(confirmationId)?.let { continuation ->
            continuation.resumeWith(Result.success(confirmed))
        } ?: log(TAG, WARN) { "No continuation found for confirmation $confirmationId" }
        _pendingConfirmations.update { it - confirmationId }
    }

    override suspend fun execute(action: WorkspaceAction): WorkspaceAction.Result = lock.withLock {
        log(TAG, INFO) { "execute($action)" }
        when (action) {
            is WorkspaceAction.Create -> {
                log(TAG, INFO) { "Creating new workspace with $action" }
                val newId = create(
                    type = action.type,
                    arguments = action.arguments,
                    idToReplace = action.replace
                )
                log(TAG) { "New workspace created with ID $newId, emitting event" }
                _events.emit(
                    WorkspaceEvent.Created(
                        workspaceId = newId,
                        replacedId = action.replace,
                        autoFocus = action.autoFocus,
                    )
                )

                // Update session with new workspace
                saveCurrentSession()

                WorkspaceAction.Create.Result(newId)
            }

            is WorkspaceAction.CreateBatch -> {
                log(TAG, INFO) { "Creating batch of ${action.requests.size} workspaces" }

                // Check if confirmation is needed
                val needsConfirmation = action.requests.size >= CONFIRMATION_THRESHOLD

                if (needsConfirmation) {
                    log(TAG, INFO) { "Batch size (${action.requests.size}) >= threshold ($CONFIRMATION_THRESHOLD), requesting confirmation" }
                    val confirmationId = kotlin.uuid.Uuid.random().toString()

                    val confirmed = suspendCancellableCoroutine { continuation ->
                        confirmationContinuations[confirmationId] = continuation
                        _pendingConfirmations.update {
                            it + (confirmationId to PendingConfirmation(
                                id = confirmationId,
                                sourceWorkspaceId = action.sourceWorkspaceId,
                                data = ConfirmationData.BatchWorkspaceCreation(
                                    totalCount = action.requests.size,
                                    skippedCount = 0, // Could be passed in action if needed
                                ),
                            ))
                        }
                    }

                    if (!confirmed) {
                        log(TAG, INFO) { "Confirmation cancelled by user" }
                        return@withLock WorkspaceAction.CreateBatch.Result.Cancelled
                    }
                    log(TAG, INFO) { "Confirmation approved by user" }
                }

                // Execute batch creation
                val results = mutableMapOf<WorkspaceAction.Create, WorkspaceAction.CreateBatch.CreationResult>()

                action.requests.forEach { createRequest ->
                    try {
                        log(TAG) { "Creating workspace: ${createRequest.type}" }
                        val newId = create(
                            type = createRequest.type,
                            arguments = createRequest.arguments,
                            idToReplace = createRequest.replace
                        )
                        _events.emit(
                            WorkspaceEvent.Created(
                                workspaceId = newId,
                                replacedId = createRequest.replace
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

                log(TAG, INFO) { "Batch creation completed: $successCount succeeded, $failureCount failed" }

                // Emit event for banner feedback
                _events.emit(
                    WorkspaceEvent.BatchCreationCompleted(
                        successCount = successCount,
                        failureCount = failureCount,
                        skippedCount = 0,
                        sourceWorkspaceId = action.sourceWorkspaceId,
                    )
                )

                // Update session with newly created workspaces
                if (successCount > 0) saveCurrentSession()

                WorkspaceAction.CreateBatch.Result.Success(
                    results = results,
                    skippedCount = 0,
                )
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
                    val confirmationId = kotlin.uuid.Uuid.random().toString()

                    log(TAG, INFO) { "Requesting confirmation to close workspace: ${workspaceInfo.title}" }

                    val confirmed = suspendCancellableCoroutine { continuation ->
                        confirmationContinuations[confirmationId] = continuation
                        _pendingConfirmations.update {
                            it + (confirmationId to PendingConfirmation(
                                id = confirmationId,
                                sourceWorkspaceId = action.id,
                                data = ConfirmationData.WorkspaceCloseConfirmation(
                                    workspaceId = action.id,
                                    workspaceTitle = workspaceInfo.title,
                                ),
                            ))
                        }
                    }

                    if (!confirmed) {
                        log(TAG, INFO) { "Close confirmation cancelled by user" }
                        return@withLock WorkspaceAction.Close.Result
                    }
                    log(TAG, INFO) { "Close confirmation approved by user" }
                }

                // Cancel any pending confirmations for this workspace
                _pendingConfirmations.value
                    .filter { (_, confirmation) -> confirmation.sourceWorkspaceId == action.id }
                    .forEach { (confirmationId, _) ->
                        log(TAG, INFO) { "Workspace closing, cancelling confirmation $confirmationId" }
                        resolveConfirmation(confirmationId, confirmed = false)
                    }

                // Find and close all child workspaces owned by this workspace
                val childWorkspaces = _workspaces.value.filter { ws ->
                    val info = ws.info.first()
                    info.callerWorkspaceId == action.id
                }
                if (childWorkspaces.isNotEmpty()) {
                    log(TAG) { "Auto-closing ${childWorkspaces.size} child workspace(s)" }
                    childWorkspaces.forEach { childWs ->
                        _workspaces.value = _workspaces.value.filter { it.id != childWs.id }
                        _events.emit(WorkspaceEvent.Closed(workspaceId = childWs.id))
                    }
                }

                _workspaces.value = _workspaces.value.filter { it.id != action.id }
                _events.emit(WorkspaceEvent.Closed(workspaceId = action.id))

                // Update session to remove closed workspace
                saveCurrentSession()

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

                // Update session to reflect new workspace order
                saveCurrentSession()

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

                // Clear session when all workspaces are closed
                sessionManager.clearSession()

                WorkspaceAction.CloseAll.Result
            }
        }
    }

    /**
     * Save the current workspace session
     */
    private suspend fun saveCurrentSession() {
        try {
            val currentWorkspaces = _workspaces.value
            if (currentWorkspaces.isEmpty()) {
                log(TAG, DEBUG) { "No workspaces to save" }
                return
            }

            // Don't save sub-workspaces (modal pickers)
            val workspacesToSave = currentWorkspaces.filter { workspace ->
                val info = workspace.info.first()
                info.callerWorkspaceId == null // Only save top-level workspaces
            }

            val sessionData = workspacesToSave.mapIndexed { index, workspace ->
                val info = workspace.info.first()

                // Extract current state as arguments for restoration
                val extractedArguments = stateExtractor.extractArguments(workspace)

                WorkspaceSessionData(
                    id = workspace.id.toString(),
                    type = info.type,
                    arguments = extractedArguments?.let {
                        argumentsSerializer.serialize(info.type, it)
                    },
                    customState = null, // Future: custom state for complex workspaces
                    order = index,
                )
            }

            sessionManager.saveSession(sessionData)
            log(TAG, INFO) { "Saved session with ${sessionData.size} workspaces" }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to save session: ${e.asLog()}" }
        }
    }

    /**
     * Restore workspaces from a saved session
     */
    suspend fun restoreSession(): List<Workspace.Id> {
        try {
            val session = sessionManager.loadSession()
            if (session == null) {
                log(TAG, DEBUG) { "No session to restore" }
                return emptyList()
            }

            sessionManager.setRestorationState(WorkspaceSessionManager.RestorationState.RESTORING)
            val restoredIds = mutableListOf<Workspace.Id>()

            // Sort by order and restore
            session.workspaces.sortedBy { it.order }.forEach { workspaceData ->
                try {
                    log(TAG) { "Restoring workspace: ${workspaceData.type}" }

                    // Deserialize arguments from saved data
                    val arguments: Workspace.Arguments? = workspaceData.arguments?.let {
                        argumentsSerializer.deserialize(workspaceData.type, it)
                    }

                    val newId = create(
                        type = workspaceData.type,
                        arguments = arguments,
                    )

                    restoredIds.add(newId)

                    // TODO: If workspace implements WorkspaceSerializable, restore custom state

                    _events.emit(
                        WorkspaceEvent.Created(
                            workspaceId = newId,
                            replacedId = null,
                            autoFocus = false, // Don't auto-focus during restoration
                        )
                    )
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Failed to restore workspace ${workspaceData.type}: ${e.asLog()}" }
                }
            }

            sessionManager.setRestorationState(WorkspaceSessionManager.RestorationState.RESTORED)
            log(TAG, INFO) { "Restored ${restoredIds.size} workspaces" }
            return restoredIds
        } catch (e: Exception) {
            log(TAG, ERROR) { "Session restoration failed: ${e.asLog()}" }
            sessionManager.setRestorationState(WorkspaceSessionManager.RestorationState.FAILED)
            return emptyList()
        }
    }


    companion object {
        private val TAG = logTag("Workspace", "Repo")
        private const val CONFIRMATION_THRESHOLD = 5
    }

}
