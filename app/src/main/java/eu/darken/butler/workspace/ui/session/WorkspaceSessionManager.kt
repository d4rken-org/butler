package eu.darken.butler.workspace.ui.session

import androidx.room.withTransaction
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.core.WorkspaceFactory
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.core.session.WorkspaceSessionStorage
import eu.darken.butler.workspace.core.session.WorkspaceSessionStorage.Companion.DEFAULT_SESSION_ID
import eu.darken.butler.workspace.core.session.db.WorkspaceInstanceEntity
import eu.darken.butler.workspace.core.session.db.WorkspaceSessionEntity
import eu.darken.butler.workspace.core.session.db.WorkspaceUIState
import eu.darken.butler.workspace.ui.WorkspacePageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant

@Singleton
class WorkspaceSessionManager @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val workspaceSettings: WorkspaceSettings,
    private val workspaceRepo: WorkspaceRepo,
    private val workspacePageManager: WorkspacePageManager,
    private val storage: WorkspaceSessionStorage,
    private val json: Json,
    private val factoryMap: Map<Workspace.Type, @JvmSuppressWildcards WorkspaceFactory<*>>,
) {

    private val _state = MutableStateFlow<State>(State.Restoring)
    val state: StateFlow<State> = _state.asStateFlow()

    // Track last saved workspace state for incremental updates
    private val lastSavedWorkspaces = mutableMapOf<Workspace.Id, Pair<Int, Int>>() // ID -> (argsHash, orderIndex)

    init {
        appScope.launch {
            val currentWorkspaces = workspaceRepo.state.first()
            if (currentWorkspaces.infos.isNotEmpty()) {
                log(TAG, INFO) { "Workspaces already exist, not restoring session" }
                _state.value = State.Restored(emptyList())
                return@launch
            }

            if (!workspaceSettings.sessionRestoreEnabled.value()) {
                log(TAG, INFO) { "Session restoration disabled" }
                _state.value = State.Disabled
                return@launch
            }

            try {
                val restoredIds = restoreSession()
                _state.value = State.Restored(restoredIds)
            } catch (e: Exception) {
                log(TAG, ERROR) { "Failed to restore session: ${e.asLog()}" }
                _state.value = State.Error(e)
            }
        }

        // Auto-save session when workspace or UI state changes
        combine(
            state,
            workspaceRepo.state.map { it.infos }.debounce(500),
            workspacePageManager.state.debounce(500),
        ) { restorationState, workspaces, uiState ->
            if (restorationState == State.Restoring) {
                log(TAG) { "Session restoration in progress, skipping auto-save" }
                return@combine
            }

            if (restorationState is State.Error) {
                log(TAG, WARN) { "Session restoration failed, skipping auto-save to preserve saved session" }
                return@combine
            }

            if (!workspaceSettings.sessionRestoreEnabled.value()) {
                log(TAG) { "Session saving is disabled, skipping auto-save" }
                return@combine
            }

            log(TAG) { "Auto-saving session with ${workspaces.size} workspaces" }
            saveSession()
        }.launchIn(appScope)

        // Hydrate a dormant workspace when it gains focus. Only armed once restoration finished:
        // focus emissions during restore (e.g. the stale SavedStateHandle focus) must not hydrate
        // anything, which is what keeps the cold start down to a single workspace.
        state
            .filterIsInstance<State.Restored>()
            .take(1)
            .flatMapLatest {
                workspacePageManager.state
                    .map { it.focusedWorkspaceId }
                    .distinctUntilChanged()
            }
            .onEach { focusedId -> hydrateOnFocus(focusedId) }
            .catch { log(TAG, ERROR) { "Hydrate-on-focus observer failed: ${it.asLog()}" } }
            .launchIn(appScope)
    }

    /**
     * Instantiates the focused workspace if it is still a dormant stand-in. A dormant that already
     * failed hydration is left alone — retrying on every focus change would loop; the placeholder's
     * retry button is the way back.
     */
    private suspend fun hydrateOnFocus(focusedId: Workspace.Id?) {
        if (focusedId == null) return
        try {
            val info = workspaceRepo.state.first().infos.firstOrNull { it.id == focusedId } ?: return
            val lifecycleState = info.lifecycleState
            if (lifecycleState !is Workspace.LifecycleState.Dormant) return
            if (lifecycleState.error != null) {
                log(TAG) { "Focused workspace $focusedId failed hydration before, waiting for a manual retry" }
                return
            }
            log(TAG, INFO) { "Focused workspace $focusedId is dormant, hydrating" }
            workspaceRepo.execute(WorkspaceAction.Hydrate(focusedId))
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to hydrate focused workspace $focusedId: ${e.asLog()}" }
        }
    }

    /**
     * Save the current workspace and UI state as a session with incremental updates
     */
    suspend fun saveSession() {
        log(TAG, INFO) { "Saving session" }

        var defaultSession = storage.dao.getSession(DEFAULT_SESSION_ID)
        if (defaultSession == null) {
            defaultSession = WorkspaceSessionEntity(
                sessionId = DEFAULT_SESSION_ID,
                label = "Default Session",
                createdAt = Clock.System.now(),
            )
            log(TAG) { "Default session will be created: $defaultSession" }
        }
        storage.dao.upsertSession(defaultSession)

        // Pull workspace data
        val repoState = workspaceRepo.state.first()
        val workspacesToSave = repoState.infos.filter { !it.isSubWorkspace }

        // Pull UI state
        val uiState = workspacePageManager.state.first()
        val now = Clock.System.now()

        // Sub-workspaces (modal pickers/exports) are transient and not persisted, so a focus that
        // currently points at one must be resolved up to its owning tab — otherwise restore falls
        // back to an arbitrary tab and clobbers the wrong pane.
        val infosById = repoState.infos.associateBy { it.id }
        val focusToPersist = run {
            val id = uiState.focusedWorkspaceId ?: return@run null
            var current = infosById[id] ?: return@run id
            val visited = mutableSetOf<Workspace.Id>()
            while (current.isSubWorkspace) {
                if (!visited.add(current.id)) return@run null
                current = current.callerWorkspaceId?.let { infosById[it] } ?: return@run null
            }
            current.id
        }

        // Perform incremental save within transaction
        storage.database.withTransaction {
            // 1. Upsert session metadata (including UI state)
            storage.dao.upsertSession(
                defaultSession.copy(
                    updatedAt = now,
                    uiState = WorkspaceUIState(
                        focusedWorkspaceId = focusToPersist,
                        paneSelections = uiState.selectedWorkspaces,
                    ),
                )
            )

            // 2. Detect removed workspaces
            val existingIds = storage.dao.getWorkspaceIds(defaultSession.sessionId).toSet()
            val currentIds = workspacesToSave.map { it.id }.toSet()
            val removedIds = existingIds - currentIds
            if (removedIds.isNotEmpty()) {
                storage.dao.deleteWorkspacesByIds(removedIds.toList())
                removedIds.forEach { lastSavedWorkspaces.remove(it) }
                log(TAG) { "Removed ${removedIds.size} deleted workspaces from session" }
            }

            // 3. Upsert only changed workspaces
            var changedCount = 0
            workspacesToSave.forEachIndexed { index, info ->
                // peek() instead of retrieve(): a dormant workspace must still be saved with the
                // arguments it holds, and retrieve() reports dormant entries as absent.
                val workspace = workspaceRepo.peek(info.id)
                if (workspace == null) {
                    log(TAG, WARN) { "Workspace ${info.id} disappeared during save (likely replaced), skipping" }
                    return@forEachIndexed
                }

                try {
                    val currentArgs = workspace.createArguments()

                    @Suppress("UNCHECKED_CAST")
                    val factory = factoryMap.getValue(info.type) as WorkspaceFactory<Workspace.Arguments>
                    val serializedArgs = factory.serialize(json, currentArgs)
                    val argsHash = serializedArgs.hashCode()

                    // Check if changed (arguments or position)
                    val lastSaved = lastSavedWorkspaces[info.id]
                    if (lastSaved == null || lastSaved.first != argsHash || lastSaved.second != index) {
                        // Preserve original createdAt for existing workspaces
                        val existingEntity = storage.dao.getWorkspaceById(info.id)
                        val createdAt = existingEntity?.createdAt ?: now

                        storage.dao.upsertWorkspace(
                            WorkspaceInstanceEntity(
                                workspaceId = info.id,
                                sessionId = defaultSession.sessionId,
                                type = info.type,
                                orderIndex = index,
                                createdAt = createdAt,
                                lastModified = now,
                                arguments = serializedArgs.toString(),
                            )
                        )
                        lastSavedWorkspaces[info.id] = argsHash to index
                        changedCount++
                    }
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Failed to save workspace ${info.id}: ${e.asLog()}" }
                }
            }

            log(TAG) { "Updated $changedCount of ${workspacesToSave.size} workspaces" }
        }

        log(TAG, INFO) {
            "Saved session with ${workspacesToSave.size} workspaces, focused=${uiState.focusedWorkspaceId}"
        }
    }

    private data class RestoreCandidate(
        val id: Workspace.Id,
        val type: Workspace.Type,
        val arguments: Workspace.Arguments,
    )

    private suspend fun restoreSession(): List<Workspace.Id> {
        log(TAG, INFO) { "Restoring session" }

        val sessionEntity = storage.dao.getSession(DEFAULT_SESSION_ID)
        if (sessionEntity == null) {
            log(TAG, DEBUG) { "No saved session found" }
            return emptyList()
        }

        val workspaceEntities = storage.dao.getWorkspaces(sessionEntity.sessionId)

        log(TAG, INFO) { "Loaded session with ${workspaceEntities.size} workspaces" }

        val candidates = buildRestoreCandidates(workspaceEntities)

        // Which candidate becomes a real instance: the saved focus if it survived validation,
        // otherwise the first candidate - the same tab applyUIState() would fall back to.
        val onDemand = workspaceSettings.restoreWorkspacesOnDemand.value()
        val focusedCandidate = candidates
            .firstOrNull { it.id == sessionEntity.uiState.focusedWorkspaceId }
            ?: candidates.firstOrNull()
        log(TAG, INFO) { "Restoring ${candidates.size} candidates (onDemand=$onDemand, focused=${focusedCandidate?.id})" }

        val restoredWorkspaceIds = mutableListOf<Workspace.Id>()
        val dormantIds = mutableListOf<Workspace.Id>()
        var focusedIsLive = false

        candidates.forEach { candidate ->
            val restoreEagerly = !onDemand || candidate.id == focusedCandidate?.id
            val restored = if (restoreEagerly) {
                createEagerly(candidate)
            } else {
                registerDormant(candidate)
            }
            if (!restored) return@forEach

            restoredWorkspaceIds.add(candidate.id)
            if (restoreEagerly) {
                if (candidate.id == focusedCandidate?.id) focusedIsLive = true
            } else {
                dormantIds.add(candidate.id)
            }
        }

        // The focused slot should hold a real instance: if its creation failed, promote a dormant
        // one before the UI state is applied.
        if (!focusedIsLive) {
            dormantIds.firstOrNull()?.let { promotionId ->
                log(TAG, WARN) { "Focused workspace could not be created, hydrating $promotionId instead" }
                workspaceRepo.execute(WorkspaceAction.Hydrate(promotionId))
            }
        }

        seedSaveCache(candidates, restoredWorkspaceIds)

        // Apply saved UI state directly (IDs are preserved)
        applyUIState(
            focusedId = sessionEntity.uiState.focusedWorkspaceId,
            selectedIds = sessionEntity.uiState.paneSelections,
            actualWorkspaceIds = restoredWorkspaceIds,
        )

        log(TAG, INFO) { "Restored ${restoredWorkspaceIds.size} workspaces, ${dormantIds.size} dormant" }
        return restoredWorkspaceIds
    }

    /**
     * Deserializes and validates the saved rows into an ordered candidate list: rows without a
     * factory or usable arguments are dropped, duplicate singletons deduped.
     */
    private fun buildRestoreCandidates(entities: List<WorkspaceInstanceEntity>): List<RestoreCandidate> {
        val candidates = mutableListOf<RestoreCandidate>()
        val seenSingletonTypes = mutableSetOf<Workspace.Type>()

        entities.forEach { entity ->
            try {
                val type = entity.type

                // Defensive dedup: a singleton type that appears more than once in saved session data
                // (legacy data, or a bug in some prior version) should restore only the first instance.
                if (type.isSingleton && type in seenSingletonTypes) {
                    log(TAG, WARN) {
                        "Skipping duplicate singleton ${type} during restore (id=${entity.workspaceId}); first instance already restored"
                    }
                    return@forEach
                }

                @Suppress("UNCHECKED_CAST")
                val factory = factoryMap[type] as? WorkspaceFactory<Workspace.Arguments> ?: run {
                    log(TAG, WARN) { "No factory for $type, skipping ${entity.workspaceId}" }
                    return@forEach
                }
                val arguments: Workspace.Arguments = try {
                    factory.deserialize(json, json.parseToJsonElement(entity.arguments))
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Failed to deserialize arguments: ${e.asLog()}" }
                    type.defaultArguments ?: run {
                        log(TAG, WARN) { "No default arguments for $type, skipping" }
                        return@forEach
                    }
                }

                candidates.add(RestoreCandidate(id = entity.workspaceId, type = type, arguments = arguments))
                if (type.isSingleton) seenSingletonTypes.add(type)
            } catch (e: Exception) {
                log(TAG, ERROR) { "Failed to prepare workspace ${entity.type}: ${e.asLog()}" }
            }
        }

        return candidates
    }

    private suspend fun createEagerly(candidate: RestoreCandidate): Boolean = try {
        log(TAG) { "Restoring workspace: ${candidate.type} with id=${candidate.id}" }
        val result = workspaceRepo.execute(
            WorkspaceAction.Create(
                type = candidate.type,
                arguments = candidate.arguments,
                autoFocus = false,
                id = candidate.id,
                skipLimitCheck = true,
            )
        )
        (result is WorkspaceAction.Create.Result.Success).also {
            if (!it) log(TAG, WARN) { "Restoring ${candidate.id} did not create a workspace: $result" }
        }
    } catch (e: Exception) {
        log(TAG, ERROR) { "Failed to restore workspace ${candidate.type}: ${e.asLog()}" }
        false
    }

    private suspend fun registerDormant(candidate: RestoreCandidate): Boolean = try {
        log(TAG) { "Registering dormant workspace: ${candidate.type} with id=${candidate.id}" }
        val result = workspaceRepo.execute(
            WorkspaceAction.RegisterDormant(
                id = candidate.id,
                type = candidate.type,
                arguments = candidate.arguments,
            )
        )
        (result is WorkspaceAction.RegisterDormant.Result.Success).also {
            if (!it) log(TAG, WARN) { "Registering dormant ${candidate.id} failed: $result" }
        }
    } catch (e: Exception) {
        log(TAG, ERROR) { "Failed to register dormant workspace ${candidate.type}: ${e.asLog()}" }
        false
    }

    /**
     * Primes the incremental-save cache with what was just restored, so the first auto-save only
     * writes rows that actually changed instead of rewriting the whole session.
     */
    private fun seedSaveCache(candidates: List<RestoreCandidate>, restoredIds: List<Workspace.Id>) {
        val byId = candidates.associateBy { it.id }
        restoredIds.forEachIndexed { index, id ->
            val candidate = byId[id] ?: return@forEachIndexed
            try {
                @Suppress("UNCHECKED_CAST")
                val factory = factoryMap.getValue(candidate.type) as WorkspaceFactory<Workspace.Arguments>
                val argsHash = factory.serialize(json, candidate.arguments).hashCode()
                lastSavedWorkspaces[id] = argsHash to index
            } catch (e: Exception) {
                log(TAG, WARN) { "Failed to seed save cache for $id: ${e.asLog()}" }
            }
        }
    }

    internal suspend fun applyUIState(
        focusedId: Workspace.Id?,
        selectedIds: Map<Int, Workspace.Id>,
        actualWorkspaceIds: List<Workspace.Id>,
    ) {
        val validIds = actualWorkspaceIds.toSet()

        // Validate focused workspace ID
        val validFocusedId = when {
            focusedId != null && focusedId in validIds -> {
                log(TAG) { "Restoring focused workspace: $focusedId" }
                focusedId
            }

            actualWorkspaceIds.isNotEmpty() -> {
                log(TAG, WARN) { "Focused workspace $focusedId not found, falling back to first workspace" }
                actualWorkspaceIds.first()
            }

            else -> {
                log(TAG, WARN) { "No workspaces available for focus" }
                null
            }
        }

        // Validate and apply selected workspaces (pane assignments)
        val selectedWorkspaces = mutableMapOf<Int, Workspace.Id>()
        val usedIds = mutableSetOf<Workspace.Id>()

        // Sort by pane number to process in order
        selectedIds.entries.sortedBy { it.key }.forEach { (pane, id) ->
            when {
                id in validIds && id !in usedIds -> {
                    log(TAG) { "Restoring pane $pane: $id" }
                    selectedWorkspaces[pane] = id
                    usedIds.add(id)
                }

                else -> {
                    // Try to find replacement using MRU
                    val replacement = findReplacementWorkspace(actualWorkspaceIds, usedIds)
                    if (replacement != null) {
                        log(TAG, WARN) { "Pane $pane workspace $id not found, using replacement: $replacement" }
                        selectedWorkspaces[pane] = replacement
                        usedIds.add(replacement)
                    } else {
                        log(TAG, WARN) { "Pane $pane workspace $id not found, no replacement available" }
                    }
                }
            }
        }

        // Ensure focused workspace is visible in at least one pane
        // This handles: pane count reduction (landscape→portrait), inconsistent saved state
        // Skip if already in any pane (prevents duplicates) or if it's a sub-workspace (modals aren't in panes)
        if (validFocusedId != null && validFocusedId !in selectedWorkspaces.values) {
            log(TAG, WARN) { "Focused workspace $validFocusedId not in any pane, assigning to pane 0" }
            selectedWorkspaces[0] = validFocusedId
        }

        // Apply to PageManager atomically (avoids double focus change)
        workspacePageManager.applyRestoredUIState(validFocusedId, selectedWorkspaces)

        log(TAG, INFO) { "Applied UI state: focused=$validFocusedId, selected=${selectedWorkspaces.size} panes" }
    }

    /**
     * Clears the session data, typically called when restoration fails
     * and the user wants to start fresh.
     */
    suspend fun clearSession() {
        log(TAG, INFO) { "Clearing session due to restoration error" }
        storage.dao.clearAllSessionData(DEFAULT_SESSION_ID)
        _state.value = State.Restored(emptyList())
    }

    /**
     * Find a replacement workspace for a missing pane assignment
     * Uses MRU (Most Recently Used) logic from PageManager
     */
    private suspend fun findReplacementWorkspace(
        availableIds: List<Workspace.Id>,
        excludeIds: Set<Workspace.Id>,
    ): Workspace.Id? {
        val currentState = workspacePageManager.state.first()

        return availableIds
            .filter { it !in excludeIds }
            .maxByOrNull { id ->
                currentState.workspaceAccessTimes[id] ?: Instant.DISTANT_PAST
            }
    }

    sealed class State {
        data object Disabled : State()
        data object Restoring : State()
        data class Restored(val ids: List<Workspace.Id>) : State()
        data class Error(val exception: Exception) : State()
    }

    companion object {
        private val TAG = logTag("Workspace", "Session", "Manager")
    }
}
