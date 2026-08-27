package eu.darken.butler.workspace.ui.session

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import eu.darken.butler.workspace.core.WorkspaceRemote
import eu.darken.butler.workspace.core.WorkspaceRepo
import eu.darken.butler.workspace.core.WorkspaceSettings
import eu.darken.butler.workspace.core.defaultArguments
import eu.darken.butler.workspace.core.isForSubWorkspace
import eu.darken.butler.workspace.core.session.WorkspaceSessionStorage
import eu.darken.butler.workspace.core.session.WorkspaceSessionStorage.Companion.DEFAULT_SESSION_ID
import eu.darken.butler.workspace.core.session.db.WorkspaceInstanceEntity
import eu.darken.butler.workspace.core.session.db.WorkspaceSessionEntity
import eu.darken.butler.workspace.core.session.db.WorkspaceUIState
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.floatingbar.WorkspaceBarCollapseStates
import eu.darken.butler.workspace.ui.restore.WorkspaceViewPrefs
import eu.darken.butler.workspace.ui.scroll.WorkspaceScrollPositions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val scrollPositions: WorkspaceScrollPositions,
    private val barCollapseStates: WorkspaceBarCollapseStates,
    private val viewPrefs: WorkspaceViewPrefs,
    @ProcessLifecycle private val processLifecycle: Lifecycle,
) {

    private val _state = MutableStateFlow<State>(State.Restoring)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Everything a saved row is derived from. Compared as a whole for incremental saves: a hash of
     * the arguments alone missed title-only changes and, being a hash, could collide; [type] matters
     * because a same-id replace changes the type in place.
     */
    private data class SaveKey(
        val type: Workspace.Type,
        val serializedArguments: String,
        val orderIndex: Int,
        val customTitle: String?,
    )

    // Track last saved workspace state for incremental updates
    private val lastSavedWorkspaces = mutableMapOf<Workspace.Id, SaveKey>()

    // Serializes the two writers: the full save and the lightweight UI-state save both touch the
    // session row, and the full save additionally races lastSavedWorkspaces and the transaction.
    private val saveLock = Mutex()

    /**
     * Which workspaces occupied a rendered pane on the previous emission. Null until the observer
     * below sees its first state after both arming gates opened, whose occupants are the baseline
     * rather than new arrivals. Only touched by that observer.
     */
    private var panedWorkspaces: Set<Workspace.Id>? = null

    /**
     * Opened once the startup pane layout has been applied, see [onInitialPaneLayoutApplied].
     */
    private val initialPaneLayoutApplied = CompletableDeferred<Unit>()

    /**
     * Called by the UI when the first pane count the layout resolves has been applied. Everything
     * that layout pass placed - including whatever its auto-fill picked - belongs to startup, so the
     * pane observer below only starts counting arrivals afterwards.
     *
     * Idempotent: a later layout pass or an Activity recreation calls this again and must not reset
     * the baseline that has been established by then.
     */
    fun onInitialPaneLayoutApplied() {
        initialPaneLayoutApplied.complete(Unit)
    }

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

        // Scroll, bar-collapse and view-pref changes get their own lightweight writer instead of a fourth
        // source in the combine above: they must not re-run createArguments() + serialize() for
        // every workspace only to discover that no workspace row changed. The initial counter values
        // are dropped, they only reflect "nothing recorded yet".
        merge(
            scrollPositions.changes.drop(1),
            barCollapseStates.changes.drop(1),
            viewPrefs.changes.drop(1),
        )
            .debounce(UI_STATE_SAVE_DEBOUNCE_MS)
            .onEach {
                if (!isSavingAllowed()) return@onEach
                log(TAG) { "View state changed, saving UI state" }
                saveUiState()
            }
            .catch { log(TAG, ERROR) { "View state save observer failed: ${it.asLog()}" } }
            .launchIn(appScope)

        // The debounce alone loses the last scroll when the app is stopped inside its window. A hard
        // kill without ON_STOP can still lose up to the debounce window.
        processLifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event != Lifecycle.Event.ON_STOP) return@LifecycleEventObserver
                appScope.launch {
                    if (!isSavingAllowed()) return@launch
                    log(TAG) { "App stopped, flushing UI state" }
                    saveUiState()
                }
            }
        )

        // Resume a paused workspace while it holds focus. Tracks the focused id TOGETHER with that
        // workspace's lifecycle state: a plain focus observer would miss a tab that gets paused
        // while already focused (auto-pause landing right after the user switched to it), leaving
        // the user staring at a paused foreground tab that nothing ever wakes up.
        // Only armed once restoration reached a terminal state: focus emissions during restore
        // (e.g. the stale SavedStateHandle focus) must not resume anything, which keeps the cold
        // start to one tab. Disabled and Error are terminal too - auto-pause creates paused
        // stand-ins without session restore, so those tabs need waking up just the same.
        state
            .filter { it !is State.Restoring }
            .take(1)
            .flatMapLatest {
                combine(
                    workspacePageManager.state.map { it.focusedWorkspaceId }.distinctUntilChanged(),
                    workspaceRepo.state.map { it.infos },
                ) { focusedId, infos ->
                    focusedId?.let { id -> infos.firstOrNull { it.id == id } }
                }
            }
            .distinctUntilChanged { old, new -> old?.id == new?.id && old?.lifecycleState == new?.lifecycleState }
            .onEach { focusedInfo -> resumeOnFocus(focusedInfo) }
            .catch { log(TAG, ERROR) { "Resume-on-focus observer failed: ${it.asLog()}" } }
            .launchIn(appScope)

        // Resume a paused tab that is newly placed into a rendered pane. Focus has its own observer
        // above; this covers the placements that never move focus - the rail's "assign to pane" menu,
        // the auto-fill when the pane count grows, and a widening layout that starts rendering a pane a
        // tab was already parked on.
        // Edge-triggered on the occupant set rather than level-triggered: a tab the user pauses from the
        // tab manager while it sits in an unfocused pane has to stay paused.
        // Armed by two gates, because everything that startup places is baseline, not an arrival: a
        // terminal restoration state (the assignments restore applies), and the startup pane layout
        // having been applied (the panes its auto-fill filled in). Waiting for both makes the cold
        // start instantiate the focused tab only, whichever of the two finishes first.
        state
            .filter { it !is State.Restoring }
            .take(1)
            .onEach { initialPaneLayoutApplied.await() }
            .flatMapLatest { workspacePageManager.state }
            .map { it.visiblePaneAssignments.values.toSet() }
            .distinctUntilChanged()
            .onEach { occupants -> resumeNewPaneOccupants(occupants) }
            .catch { log(TAG, ERROR) { "Resume-on-pane-assignment observer failed: ${it.asLog()}" } }
            .launchIn(appScope)
    }

    /**
     * Instantiates the focused workspace if it is a paused stand-in. A paused workspace that already
     * failed to resume is left alone — retrying on every emission would loop; the placeholder's
     * retry button is the way back.
     */
    private suspend fun resumeOnFocus(focusedInfo: Workspace.Info?) {
        if (focusedInfo == null) return
        try {
            val lifecycleState = focusedInfo.lifecycleState
            if (lifecycleState !is Workspace.LifecycleState.Paused) return
            if (lifecycleState.error != null) {
                log(TAG) { "Focused workspace ${focusedInfo.id} failed resume before, waiting for a manual retry" }
                return
            }
            log(TAG, INFO) { "Focused workspace ${focusedInfo.id} is paused, resuming" }
            workspaceRepo.execute(WorkspaceAction.Resume(focusedInfo.id))
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to resume focused workspace ${focusedInfo.id}: ${e.asLog()}" }
        }
    }

    /**
     * Instantiates the paused stand-ins that just entered a rendered pane. One that already failed to
     * resume is left alone for the same reason as in [resumeOnFocus] - retrying on every layout change
     * would loop; the placeholder's retry button is the way back.
     *
     * The first call after the observer is armed only records the baseline: by then restoration has
     * reached a terminal state and [onInitialPaneLayoutApplied] has fired, so those occupants are
     * what startup put there rather than arrivals.
     *
     * State comes from [WorkspaceRepo.peek], not from the [WorkspaceRepo.state] share, because nothing
     * re-triggers this observer: the occupant set does not change again when the share catches up. A
     * lagging "Ready" would drop the resume for good - exactly the case this observer exists for - and
     * a lagging "Paused(error=null)" would retry one that already failed. The focus observer can read
     * the share safely only because it re-evaluates on every repo emission.
     */
    private suspend fun resumeNewPaneOccupants(occupants: Set<Workspace.Id>) {
        val previous = panedWorkspaces
        panedWorkspaces = occupants
        if (previous == null) return
        (occupants - previous).forEach { id ->
            try {
                val lifecycleState = workspaceRepo.peek(id)?.info?.value?.lifecycleState
                if (lifecycleState !is Workspace.LifecycleState.Paused) return@forEach
                if (lifecycleState.error != null) {
                    log(TAG) { "Workspace $id failed resume before, waiting for a manual retry" }
                    return@forEach
                }
                log(TAG, INFO) { "Paused workspace $id was assigned to a pane, resuming" }
                workspaceRepo.execute(WorkspaceAction.Resume(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Per arrival, not per batch: the occupant set stays unchanged after a failure, so a
                // batch-wide catch would strand every later arrival with nothing to retrigger them.
                log(TAG, ERROR) { "Failed to resume newly paned workspace $id: ${e.asLog()}" }
            }
        }
    }

    /**
     * Save the current workspace and UI state as a session with incremental updates
     */
    suspend fun saveSession() = saveLock.withLock { doSaveSession() }

    /**
     * Writes only the session row (focus, panes, scroll positions), leaving workspace rows alone.
     * Scroll changes go through here so they don't amplify into a full session rewrite.
     */
    suspend fun saveUiState() = saveLock.withLock { doSaveUiState() }

    private suspend fun doSaveUiState() {
        val session = storage.dao.getSession(DEFAULT_SESSION_ID) ?: newDefaultSession()
        val uiState = buildUiState(workspaceRepo.state.first())
        storage.dao.upsertSession(session.copy(updatedAt = Clock.System.now(), uiState = uiState))

        // This writer persists focus and panes too, and the ON_STOP flush runs it without a
        // following full save - so without this the last layout before an app kill, the one that
        // decides which tabs come back, would never reach the log.
        if (layoutMoved(session.uiState, uiState)) {
            log(TAG, INFO) {
                "Session layout: focus=${uiState.focusedWorkspaceId?.shortTag}," +
                    " panes=${uiState.paneSelections.size}"
            }
        }
        log(TAG) {
            "Saved UI state: focused=${uiState.focusedWorkspaceId}," +
                " scroll=${uiState.scrollPositions.size}, bars=${uiState.barCollapse.size}," +
                " viewPrefs=${uiState.viewPrefs.size}"
        }
    }

    private fun newDefaultSession() = WorkspaceSessionEntity(
        sessionId = DEFAULT_SESSION_ID,
        label = "Default Session",
        createdAt = Clock.System.now(),
    )

    /**
     * The session row's UI payload, shared by both writers so the focus/pane resolution exists once.
     *
     * Sub-workspaces (modal pickers/exports) are transient and not persisted, so a focus that
     * currently points at one must be resolved up to its owning tab — otherwise restore falls back
     * to an arbitrary tab and clobbers the wrong pane.
     *
     * Scroll slots are written as-is: [repoState] comes from a `replayingShare` cache that lags the
     * repo's actual workspace list, so filtering the snapshot against it would drop slots of
     * workspaces that do exist. Pruning happens authoritatively instead — on close/replace events
     * and at restore for candidates that did not come back.
     */
    private suspend fun buildUiState(repoState: WorkspaceRemote.State): WorkspaceUIState {
        val pageState = workspacePageManager.state.first()
        val infosById = repoState.infos.associateBy { it.id }

        val focusToPersist = run {
            val id = pageState.focusedWorkspaceId ?: return@run null
            var current = infosById[id] ?: return@run id
            val visited = mutableSetOf<Workspace.Id>()
            while (current.isSubWorkspace) {
                if (!visited.add(current.id)) return@run null
                current = current.callerWorkspaceId?.let { infosById[it] } ?: return@run null
            }
            // A workspace that is never saved has no row to focus on the next start, and storing its
            // id would make the restore fall back to the FIRST tab rather than leaving focus alone.
            if (!current.isPersistable) return@run null
            current.id
        }

        return WorkspaceUIState(
            focusedWorkspaceId = focusToPersist,
            paneSelections = pageState.selectedWorkspaces,
            scrollPositions = scrollPositions.snapshot(),
            barCollapse = barCollapseStates.snapshot(),
            viewPrefs = viewPrefs.snapshot(),
        )
    }

    private suspend fun isSavingAllowed(): Boolean {
        val restorationState = _state.value
        if (restorationState == State.Restoring) {
            log(TAG) { "Session restoration in progress, skipping save" }
            return false
        }
        if (restorationState is State.Error) {
            log(TAG, WARN) { "Session restoration failed, skipping save to preserve saved session" }
            return false
        }
        return workspaceSettings.sessionRestoreEnabled.value()
    }

    private suspend fun doSaveSession() {
        log(TAG) { "Saving session" }

        // Every mutation below lands here first and is published to [lastSavedWorkspaces] only once
        // the transaction has committed. Mutating the live cache inside the transaction meant a
        // failed commit rolled the database back while the cache kept the new keys, so the next save
        // saw "already saved" and skipped an upsert the database never received.
        val staged = lastSavedWorkspaces.toMutableMap()

        var defaultSession = storage.dao.getSession(DEFAULT_SESSION_ID)
        if (defaultSession == null) {
            defaultSession = newDefaultSession()
            log(TAG) { "Default session will be created: $defaultSession" }
        }
        // The layout as the database currently holds it. Both writers compare against this rather
        // than a process-local field, so whichever of them commits a focus change first is the one
        // that reports it and the other sees no movement.
        val persistedUiState = defaultSession.uiState
        storage.dao.upsertSession(defaultSession)

        // Pull workspace data
        val repoState = workspaceRepo.state.first()
        // Sub-workspaces are overlays their owner rebuilds; non-persistable ones hold something that
        // only means anything in this process (a URI grant another app gave us), so a saved row
        // would restore as a tab that can never load.
        val workspacesToSave = repoState.infos.filter { !it.isSubWorkspace && it.isPersistable }

        val uiState = buildUiState(repoState)
        val now = Clock.System.now()

        // Perform incremental save within transaction
        var counts = SaveCounts()
        storage.database.withTransaction {
            // 1. Upsert session metadata (including UI state)
            storage.dao.upsertSession(
                defaultSession.copy(
                    updatedAt = now,
                    uiState = uiState,
                )
            )

            // 2. Detect removed workspaces.
            // Absence from [workspacesToSave] is NOT proof a workspace is gone: it comes from the
            // asynchronously replayed state flow, which during a slow restore can still hold a
            // partial (or empty) snapshot. Deleting on that basis wipes the saved rows of
            // workspaces that still exist, and a process death before the next settled save makes
            // that permanent. So each candidate is confirmed against the repo's authoritative
            // in-memory list, read synchronously via peek(), which also reports paused instances
            // and so protects their rows too.
            // Only the delete pass needs this: a partial snapshot that merely upserts fewer rows
            // loses nothing and the next save catches up.
            val existingIds = storage.dao.getWorkspaceIds(defaultSession.sessionId).toSet()
            val currentIds = workspacesToSave.map { it.id }.toSet()
            val removedIds = (existingIds - currentIds).filter { workspaceRepo.peek(it) == null }
            if (removedIds.isNotEmpty()) {
                storage.dao.deleteWorkspacesByIds(removedIds)
                removedIds.forEach { staged.remove(it) }
                log(TAG) { "Removed ${removedIds.size} deleted workspaces from session" }
            }

            // 3. Upsert only changed workspaces
            var changedCount = 0
            var added = 0
            var replaced = 0
            var retitled = 0
            var reordered = 0
            workspacesToSave.forEachIndexed { index, info ->
                // peek() instead of retrieve(): a paused workspace must still be saved with the
                // arguments it holds, and retrieve() reports paused entries as absent.
                val workspace = workspaceRepo.peek(info.id)
                if (workspace == null) {
                    log(TAG, WARN) { "Workspace ${info.id} disappeared during save (likely replaced), skipping" }
                    return@forEachIndexed
                }

                try {
                    val currentArgs = workspace.createArguments()

                    @Suppress("UNCHECKED_CAST")
                    val factory = factoryMap.getValue(info.type) as WorkspaceFactory<Workspace.Arguments>
                    val serializedArgs = factory.serialize(json, currentArgs).toString()

                    val saveKey = SaveKey(
                        type = info.type,
                        serializedArguments = serializedArgs,
                        orderIndex = index,
                        customTitle = info.customTitle,
                    )

                    if (staged[info.id] != saveKey) {
                        // The repo knows when the tab was actually created; the stored value is only
                        // the instant of its FIRST save, which collapses every tab created between
                        // two debounced saves onto one timestamp. Pre-existing rows keep theirs
                        // (still monotonic in save order), so no migration is needed.
                        val existingEntity = storage.dao.getWorkspaceById(info.id)
                        val createdAt = workspaceRepo.peekCreatedAt(info.id) ?: existingEntity?.createdAt ?: now

                        // Counted against the stored row, not against the in-memory cache: a cache
                        // that was never seeded (or was seeded and lost) would otherwise make an
                        // existing row look newly added.
                        if (existingEntity == null) {
                            added++
                        } else {
                            if (existingEntity.type != info.type) replaced++
                            if (existingEntity.customTitle != info.customTitle) retitled++
                            if (existingEntity.orderIndex != index) reordered++
                        }

                        storage.dao.upsertWorkspace(
                            WorkspaceInstanceEntity(
                                workspaceId = info.id,
                                sessionId = defaultSession.sessionId,
                                type = info.type,
                                orderIndex = index,
                                createdAt = createdAt,
                                lastModified = now,
                                arguments = serializedArgs,
                                customTitle = info.customTitle,
                            )
                        )
                        staged[info.id] = saveKey
                        changedCount++
                    }
                } catch (e: CancellationException) {
                    // Must not be absorbed by the catch below: swallowing it here would let a
                    // cancelled save run on to commit a partial transaction.
                    throw e
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Failed to save workspace ${info.id}: ${e.asLog()}" }
                }
            }

            log(TAG) { "Updated $changedCount of ${workspacesToSave.size} workspaces" }

            // Rows the database holds once this transaction commits: what it had, minus what this
            // pass deleted, plus what this pass inserted. Deliberately not derived from
            // [workspacesToSave] - that snapshot is allowed to be partial (see the delete pass
            // above), so a row missing from it is not a row missing from the database.
            counts = SaveCounts(
                tabs = existingIds.size - removedIds.size + added,
                added = added,
                removed = removedIds.size,
                replaced = replaced,
                retitled = retitled,
                reordered = reordered,
            )
        }

        // Past the commit: safe to publish.
        lastSavedWorkspaces.clear()
        lastSavedWorkspaces.putAll(staged)

        log(TAG) { "Saved session with ${workspacesToSave.size} workspaces" }

        if (counts.moved || layoutMoved(persistedUiState, uiState)) {
            log(TAG, INFO) {
                "Session layout: tabs=${counts.tabs}, added=${counts.added}, removed=${counts.removed}," +
                    " replaced=${counts.replaced}, retitled=${counts.retitled}," +
                    " reordered=${counts.reordered}, focus=${uiState.focusedWorkspaceId?.shortTag}," +
                    " panes=${uiState.paneSelections.size}"
            }
        }
    }

    /**
     * What a committed save changed about the session's durable shape.
     *
     * Deliberately not the incremental-save counter: that also ticks for serialized-argument
     * changes, so it fires on ordinary directory navigation while missing both removals and
     * focus-only moves - wrong in both directions for the bug class these counts serve, which is
     * "my tabs came back wrong".
     */
    private data class SaveCounts(
        val tabs: Int = 0,
        val added: Int = 0,
        val removed: Int = 0,
        val replaced: Int = 0,
        val retitled: Int = 0,
        val reordered: Int = 0,
    ) {
        val moved: Boolean
            get() = added > 0 || removed > 0 || replaced > 0 || retitled > 0 || reordered > 0
    }

    /**
     * Whether focus or pane assignment differs between the stored layout and the one about to
     * replace it. Scroll offsets, bar fractions and view prefs are excluded: they change constantly
     * and say nothing about which tabs the user gets back.
     */
    private fun layoutMoved(before: WorkspaceUIState, after: WorkspaceUIState): Boolean =
        before.focusedWorkspaceId != after.focusedWorkspaceId ||
            before.paneSelections != after.paneSelections

    private data class RestoreCandidate(
        val id: Workspace.Id,
        val type: Workspace.Type,
        val arguments: Workspace.Arguments,
        val customTitle: String?,
        /**
         * Persisted creation time, handed back to the repo so tab age survives a restart instead of
         * collapsing onto the restore instant for every tab.
         */
        val createdAt: Instant,
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

        // Seed view state BEFORE any workspace is registered - paused stand-ins included, since a
        // resume composes the page later without going through here again. Registering makes a
        // workspace visible to WorkspacesViewModel and the pager composes its page right away; with
        // an empty registry that page would resolve "nothing saved", record a zero, and restore() -
        // which refuses to clobber live slots - would lose to it permanently.
        scrollPositions.restore(sessionEntity.uiState.scrollPositions)
        barCollapseStates.restore(sessionEntity.uiState.barCollapse)
        viewPrefs.restore(sessionEntity.uiState.viewPrefs)

        // Only one candidate becomes a real instance: the saved focus if it survived validation,
        // otherwise the first candidate - the same tab applyUIState() would fall back to. Every
        // other tab starts paused and wakes up when it is focused or resumed.
        val focusedCandidate = candidates
            .firstOrNull { it.id == sessionEntity.uiState.focusedWorkspaceId }
            ?: candidates.firstOrNull()
        log(TAG, INFO) { "Restoring ${candidates.size} candidates (focused=${focusedCandidate?.id})" }

        val restoredWorkspaceIds = mutableListOf<Workspace.Id>()
        val pausedIds = mutableListOf<Workspace.Id>()
        var focusedIsLive = false

        candidates.forEach { candidate ->
            val restoreEagerly = candidate.id == focusedCandidate?.id
            val restored = if (restoreEagerly) {
                createEagerly(candidate)
            } else {
                registerPaused(candidate)
            }
            if (!restored) return@forEach

            // Before paused promotion, cache seeding and applyUIState: a candidate whose creation
            // failed must never have its name applied to whatever takes its slot.
            candidate.customTitle?.let { customTitle ->
                workspaceRepo.execute(WorkspaceAction.Rename(candidate.id, customTitle))
            }

            restoredWorkspaceIds.add(candidate.id)
            if (restoreEagerly) {
                if (candidate.id == focusedCandidate.id) focusedIsLive = true
            } else {
                pausedIds.add(candidate.id)
            }
        }

        // The focused slot should hold a real instance: if its creation failed, promote a paused
        // one before the UI state is applied.
        if (!focusedIsLive) {
            pausedIds.firstOrNull()?.let { promotionId ->
                log(TAG, WARN) { "Focused workspace could not be created, resuming $promotionId instead" }
                workspaceRepo.execute(WorkspaceAction.Resume(promotionId))
            }
        }

        seedSaveCache(candidates, restoredWorkspaceIds)

        // Prune the seed for anything that did not make it back, so the next save doesn't carry
        // slots of workspaces that no longer exist.
        (
            sessionEntity.uiState.scrollPositions.keys +
                sessionEntity.uiState.barCollapse.keys +
                sessionEntity.uiState.viewPrefs.keys
            )
            .filter { it !in restoredWorkspaceIds }
            .forEach {
                scrollPositions.forget(it)
                barCollapseStates.forget(it)
                viewPrefs.forget(it)
            }

        // Apply saved UI state directly (IDs are preserved)
        applyUIState(
            focusedId = sessionEntity.uiState.focusedWorkspaceId,
            selectedIds = sessionEntity.uiState.paneSelections,
            actualWorkspaceIds = restoredWorkspaceIds,
        )

        log(TAG, INFO) { "Restored ${restoredWorkspaceIds.size} workspaces, ${pausedIds.size} paused" }
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

                // Mirrors the save-side filter: sub-workspaces are transient modals and are never
                // written, so a row carrying a caller is stale or foreign data. Dropped HERE rather
                // than at registration because the eagerly restored candidate goes through Create,
                // which legitimately builds sub-workspaces - a stale row picked as the focus would
                // otherwise return as a modal overlay covering the UI at launch.
                if (arguments.isForSubWorkspace) {
                    log(TAG, WARN) {
                        "Skipping sub-workspace row during restore (id=${entity.workspaceId}, type=$type)"
                    }
                    return@forEach
                }

                // Same reasoning for arguments that were never meant to outlive their process: the
                // save side skips them, so a row like this is stale data from before that rule or a
                // bug, and restoring it can only produce a tab that fails to load.
                if (!arguments.isPersistable) {
                    log(TAG, WARN) {
                        "Skipping non-persistable row during restore (id=${entity.workspaceId}, type=$type)"
                    }
                    return@forEach
                }

                candidates.add(
                    RestoreCandidate(
                        id = entity.workspaceId,
                        type = type,
                        arguments = arguments,
                        customTitle = entity.customTitle,
                        createdAt = entity.createdAt,
                    )
                )
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
                createdAt = candidate.createdAt,
            )
        )
        (result is WorkspaceAction.Create.Result.Success).also {
            if (!it) log(TAG, WARN) { "Restoring ${candidate.id} did not create a workspace: $result" }
        }
    } catch (e: Exception) {
        log(TAG, ERROR) { "Failed to restore workspace ${candidate.type}: ${e.asLog()}" }
        false
    }

    private suspend fun registerPaused(candidate: RestoreCandidate): Boolean = try {
        log(TAG) { "Registering paused workspace: ${candidate.type} with id=${candidate.id}" }
        val result = workspaceRepo.execute(
            WorkspaceAction.RegisterPaused(
                id = candidate.id,
                type = candidate.type,
                arguments = candidate.arguments,
                createdAt = candidate.createdAt,
            )
        )
        (result is WorkspaceAction.RegisterPaused.Result.Success).also {
            if (!it) log(TAG, WARN) { "Registering paused ${candidate.id} failed: $result" }
        }
    } catch (e: Exception) {
        log(TAG, ERROR) { "Failed to register paused workspace ${candidate.type}: ${e.asLog()}" }
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
                lastSavedWorkspaces[id] = SaveKey(
                    type = candidate.type,
                    serializedArguments = factory.serialize(json, candidate.arguments).toString(),
                    orderIndex = index,
                    customTitle = candidate.customTitle,
                )
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
        private const val UI_STATE_SAVE_DEBOUNCE_MS = 2_000L
        private val TAG = logTag("Workspace", "Session", "Manager")
    }
}
