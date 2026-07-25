package eu.darken.butler.workspace.core

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.ui.WorkspacePageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Pauses tab workspaces the user hasn't looked at for a while, freeing their engines, scopes and
 * buffers ([WorkspaceAction.Pause]). Focusing or resuming a tab brings it back.
 *
 * "Last used" is tracked privately here as a visible -> hidden transition timestamp, deliberately
 * NOT as state on [WorkspacePageManager]: [eu.darken.butler.workspace.ui.session.WorkspaceSessionManager]
 * observes that whole state, so a periodic timestamp write would trigger a full session save (Room
 * transaction plus createArguments() on every workspace) once a minute - a battery-saving feature
 * burning battery.
 *
 * Timestamps are wall clock, so background and Doze time count: the first evaluation after the
 * process wakes up pauses everything that went stale meanwhile.
 */
@Singleton
class WorkspaceAutoPauseManager(
    private val appScope: CoroutineScope,
    private val workspaceSettings: WorkspaceSettings,
    private val workspaceRepo: WorkspaceRepo,
    private val workspacePageManager: WorkspacePageManager,
    private val clock: Clock,
) {

    @Inject constructor(
        @AppScope appScope: CoroutineScope,
        workspaceSettings: WorkspaceSettings,
        workspaceRepo: WorkspaceRepo,
        workspacePageManager: WorkspacePageManager,
    ) : this(appScope, workspaceSettings, workspaceRepo, workspacePageManager, Clock.System)

    /** When each hidden-but-live workspace last left the screen. Only touched by the eval loop. */
    private val idleSince = mutableMapOf<Workspace.Id, Instant>()

    // Conflated: the ticker and onAppForegrounded() both only offer work, so the single consumer
    // below can never run two evaluations concurrently and bursts collapse into one pass.
    private val trigger = Channel<Unit>(Channel.CONFLATED)

    init {
        appScope.launch {
            for (ignored in trigger) {
                try {
                    evaluate()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Never let one bad pass kill the loop for the rest of the process lifetime
                    log(TAG, ERROR) { "Auto-pause evaluation failed: ${e.asLog()}" }
                }
            }
        }

        appScope.launch {
            while (true) {
                delay(TICK_INTERVAL)
                trigger.trySend(Unit)
            }
        }
    }

    /** Called when the app returns to the foreground; everything stale in the background goes now. */
    fun onAppForegrounded() {
        log(TAG) { "onAppForegrounded()" }
        trigger.trySend(Unit)
    }

    internal suspend fun evaluate() {
        if (!workspaceSettings.autoPauseEnabled.value()) {
            // Re-enabling should restart every idle clock instead of pausing on stale timestamps
            idleSince.clear()
            return
        }

        val pageState = workspacePageManager.state.value
        if (pageState.isManagerOverlayVisible) {
            // The tab manager is an unfocused interaction surface (reorder, pause/resume) and drives
            // live preview capture, which composes a workspace offscreen - releasing one mid-capture
            // would pull the instance out from under it.
            log(TAG) { "Tab manager is open, skipping evaluation" }
            return
        }

        val timeout = WorkspaceSettings.clampIdleTimeout(workspaceSettings.autoPauseIdleTimeout.value())
        val infos = workspaceRepo.state.first().infos
        val now = clock.now()

        idleSince.keys.retainAll(infos.map { it.id }.toSet())

        val visibleIds = pageState.visibleWorkspaceIds()
        val parentIds = infos.mapNotNull { it.callerWorkspaceId }.toSet()

        val candidates = mutableListOf<Workspace.Id>()
        infos.forEach { info ->
            val idleClockRuns = !info.isPaused &&
                info.lifecycleState is Workspace.LifecycleState.Ready &&
                info.id !in visibleIds
            if (!idleClockRuns) {
                idleSince.remove(info.id)
                return@forEach
            }

            // First sighting is stamped now, so a workspace is never treated as infinitely old
            val since = idleSince.getOrPut(info.id) { now }
            if (now - since < timeout) return@forEach

            // Cheap pre-filter only; WorkspaceRepo re-checks all of this under its lock
            if (info.isSubWorkspace || info.id in parentIds) return@forEach
            if (info.operationCount > 0 || info.attentionCount > 0) return@forEach
            if (info.hasUnsavedChanges || !info.isPausable) return@forEach

            candidates += info.id
        }

        if (candidates.isEmpty()) return

        log(TAG, INFO) { "Auto-pausing ${candidates.size} idle workspace(s)" }
        // Strictly sequential: WorkspaceRepo.execute() holds one global lock across the release, so
        // firing these in parallel would queue up behind each other anyway and stall other actions.
        candidates.forEach { candidate ->
            // Re-checked per candidate, not once per pass: pausing suspends, so the user can open
            // the tab manager (and its offscreen preview capture) between two pauses.
            if (workspacePageManager.state.value.isManagerOverlayVisible) {
                log(TAG, INFO) { "Tab manager opened mid-pass, skipping the remaining candidates" }
                return
            }
            pause(candidate)
        }
    }

    private suspend fun pause(id: Workspace.Id) {
        when (val result = workspaceRepo.execute(WorkspaceAction.Pause(id))) {
            is WorkspaceAction.Pause.Result.Success -> {
                idleSince.remove(id)
                log(TAG, INFO) { "Auto-paused $id" }
                // Backstop for a focus/selection change or a tab manager opening while we paused
                val pageState = workspacePageManager.state.value
                if (id in pageState.visibleWorkspaceIds()) {
                    log(TAG, INFO) { "$id became visible while pausing, resuming it right away" }
                    workspaceRepo.execute(WorkspaceAction.Resume(id))
                } else if (pageState.isManagerOverlayVisible) {
                    log(TAG, INFO) { "Tab manager opened while pausing $id, resuming it right away" }
                    workspaceRepo.execute(WorkspaceAction.Resume(id))
                }
            }
            // The repo guards are the authority; a refusal just means we retry next pass
            is WorkspaceAction.Pause.Result.Refused -> log(TAG) { "Auto-pause of $id refused: ${result.reason}" }
            is WorkspaceAction.Pause.Result.Failed -> log(TAG, WARN) {
                "Auto-pause of $id failed: ${result.error.asLog()}"
            }
            is WorkspaceAction.Pause.Result.NoOp -> log(TAG) { "Auto-pause of $id was a no-op" }
            else -> log(TAG, ERROR) { "Unexpected Pause result for $id: $result" }
        }
    }

    /**
     * Workspaces the user can actually see: pane selections whose pane index is still within the
     * current pane count, plus the focused one. The raw selection map is not enough — setPaneCount()
     * lowers currentPaneCount without pruning out-of-range entries, so after a quad -> single
     * collapse three hidden tabs would look visible forever and never auto-pause.
     */
    private fun WorkspacePageManager.State.visibleWorkspaceIds(): Set<Workspace.Id> =
        selectedWorkspaces.filterKeys { it in 0 until currentPaneCount }.values.toSet() +
            setOfNotNull(focusedWorkspaceId)

    companion object {
        private val TAG = logTag("Workspace", "AutoPause")
        private val TICK_INTERVAL = 1.minutes
    }
}
