package eu.darken.butler.workspace.core

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.ui.WorkspacePageManager
import eu.darken.butler.workspace.ui.WorkspaceVisibilityTracker
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
 * A tab and its opted-in modal children are one unit: they are only ever paused together, and a unit
 * counts as seen while ANY of its members is on screen.
 *
 * "Last used" is tracked privately here as a visible -> hidden transition timestamp, deliberately
 * NOT as state on [WorkspacePageManager]: [eu.darken.butler.workspace.ui.session.WorkspaceSessionManager]
 * observes that whole state, so a periodic timestamp write would trigger a full session save (Room
 * transaction plus createArguments() on every workspace) once a minute - a battery-saving feature
 * burning battery.
 *
 * Timestamps are wall clock, so background and Doze time count: the first evaluation after the
 * process wakes up pauses everything that went stale meanwhile.
 *
 * On a single-pane layout what the user sees is decided by the pager, not by pane assignments, so
 * [WorkspaceVisibilityTracker] is consulted too - both for what is on screen right now and for
 * sightings that happened between two evaluations.
 */
@Singleton
class WorkspaceAutoPauseManager(
    private val appScope: CoroutineScope,
    private val workspaceSettings: WorkspaceSettings,
    private val workspaceRepo: WorkspaceRepo,
    private val workspacePageManager: WorkspacePageManager,
    private val workspacePauseGate: WorkspacePauseGate,
    private val pagerVisibility: WorkspaceVisibilityTracker,
    private val clock: Clock,
) {

    @Inject constructor(
        @AppScope appScope: CoroutineScope,
        workspaceSettings: WorkspaceSettings,
        workspaceRepo: WorkspaceRepo,
        workspacePageManager: WorkspacePageManager,
        workspacePauseGate: WorkspacePauseGate,
        pagerVisibility: WorkspaceVisibilityTracker,
    ) : this(
        appScope,
        workspaceSettings,
        workspaceRepo,
        workspacePageManager,
        workspacePauseGate,
        pagerVisibility,
        Clock.System,
    )

    /** When each hidden-but-live workspace last left the screen. Only touched by the eval loop. */
    private val idleSince = mutableMapOf<Workspace.Id, Instant>()

    /** Pager sighting stamps as of the previous pass, so this one can spot what happened between. */
    private var lastSeenStamps: Map<Workspace.Id, Long> = emptyMap()

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

        // The last line of defence for the swipe case: a page becoming visible in the frames right
        // after a release finished - too late for the backstop in pause() to have seen it - would
        // otherwise leave the user settling on a paused placeholder, since a settle only selects.
        appScope.launch {
            pagerVisibility.reappeared.collect { id ->
                if (workspaceRepo.state.first().infos.none { it.id == id && it.isPaused }) return@collect
                log(TAG, INFO) { "$id came back on screen right after being paused, resuming it" }
                workspaceRepo.execute(WorkspaceAction.Resume(id))
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

        val liveIds = infos.map { it.id }.toSet()
        idleSince.keys.retainAll(liveIds)
        // A just-paused record only has to survive until the unit is evaluated again; keeping it
        // longer would let a much later publication resume a unit that was legitimately released.
        pagerVisibility.forget(liveIds)
        pagerVisibility.retirePaused(liveIds)

        // Sightings since the previous pass, not just what is on screen right now. Evaluation is
        // sampled (a one-minute ticker plus foreground events) while swipes are not: a page that
        // came in and went out again between two passes was looked at, and no set-valued snapshot
        // can say so afterwards. Unseen ids stamp 0, so the first pass spares whatever the pager
        // has ever published - conservative in exactly the right direction.
        val seenStamps = pagerVisibility.seenStamps(liveIds)
        val seenSinceLastPass = seenStamps
            .filter { (id, stamp) -> stamp != 0L && lastSeenStamps[id] != stamp }
            .keys
        lastSeenStamps = seenStamps

        val stacks = WorkspaceStacks(infos)
        val visibleIds = stacks.visibleUnitIds(pageState, extraSeeds = seenSinceLastPass)

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

            // Cheap pre-filter only; WorkspaceRepo re-checks all of this under its lock.
            // A unit is always paused through its root, so children are never candidates themselves -
            // they go along with the tab that owns them.
            if (info.isSubWorkspace) return@forEach
            val members = stacks.unitOf(info.id) ?: return@forEach
            if (members.any { !it.canPauseWithUnit(isRoot = it.id == info.id) }) return@forEach

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

    /**
     * A member of an ownership unit may go down with it when nothing would be lost. Children
     * additionally have to opt into it ([Workspace.Info.pausableAsChild]), which pickers never do -
     * their result collector lives in the caller they would be released with.
     */
    private fun Workspace.Info.canPauseWithUnit(isRoot: Boolean): Boolean = when {
        !isRoot && !pausableAsChild -> false
        operationCount > 0 || attentionCount > 0 -> false
        hasUnsavedChanges || !isPausable -> false
        lifecycleState !is Workspace.LifecycleState.Ready -> false
        else -> true
    }

    /**
     * Held under [WorkspacePauseGate] for this unit's ownership root only, never for the whole pass:
     * a slow release must not stall preview captures of unrelated tabs. One lease per unit (not one
     * per member) is what makes the set atomic - a child created while we wait for the lease is
     * covered by the same key. The lease covers the backstop resume too, so a capture waiting on it
     * never observes the brief paused window of a unit we are about to wake up again.
     */
    private suspend fun pause(id: Workspace.Id) =
        workspacePauseGate.withLease(workspaceRepo.peekOwnershipRoot(id)) {
            // Taken before the release, so a page that becomes visible and hidden again while the
            // release runs is still caught: the tracker conflates its visible SET, never the
            // per-tab generation these stamps compare against.
            val unitIds = workspaceRepo.peekStacks().unitOf(id)?.map { it.id } ?: listOf(id)
            val stampsBeforeRelease = pagerVisibility.seenStamps(unitIds)

            when (val result = workspaceRepo.execute(WorkspaceAction.Pause(id))) {
                is WorkspaceAction.Pause.Result.Success -> {
                    result.pausedIds.forEach { idleSince.remove(it) }
                    log(TAG, INFO) { "Auto-paused ${result.pausedIds}" }
                    // Armed before the checks below, not after: a publication landing in between
                    // would fall into exactly the gap the record exists to close.
                    pagerVisibility.guardPaused(result.id)
                    // Backstop for anything that put this unit back on screen while we paused: a
                    // focus/selection change, a swipe bringing its page in, or a tab manager
                    // opening. Visibility is judged exactly like in evaluate(), transitively over
                    // whole units and including the rendered fallback - the raw selection/focus map
                    // would miss both. Both the member list and the topology come from post-swap
                    // sources: the repo resolved them under its own lock, while workspaceRepo.state
                    // can still lag the swap.
                    val pageState = workspacePageManager.state.value
                    val visibleIds = workspaceRepo.peekStacks().visibleUnitIds(pageState)
                    val reason = when {
                        result.pausedIds.any { it in visibleIds } -> "became visible while pausing"
                        pagerVisibility.wasSeenSince(stampsBeforeRelease) -> "was on screen while pausing"
                        pageState.isManagerOverlayVisible -> "tab manager opened while pausing"
                        else -> null
                    }
                    if (reason != null) {
                        log(TAG, INFO) { "${result.id} $reason, resuming it right away" }
                        pagerVisibility.retirePaused(listOf(result.id))
                        workspaceRepo.execute(WorkspaceAction.Resume(result.id))
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
     * Workspaces the user can actually see: the occupants of panes the layout still renders, plus
     * the focused one. Reads [WorkspacePageManager.State.visiblePaneAssignments] rather than the raw
     * selection map, which retains out-of-range indices by design.
     */
    private fun WorkspacePageManager.State.visibleWorkspaceIds(): Set<Workspace.Id> =
        visiblePaneAssignments.values.toSet() + setOfNotNull(focusedWorkspaceId)

    /**
     * Everything on screen, expanded to whole ownership units: a tab whose modal is up counts as
     * visible, and so does every member of a visible tab's stack.
     *
     * Selections and focus alone are NOT enough. The renderer falls back to the newest full-screen
     * chain when focus points at no chain at all, so a full-screen modal can be the thing the user
     * is looking at while neither it nor its tab is selected or focused. Classifying that stack as
     * idle would pause a workspace out from under the user, which is why the rendered chains are
     * seeded here rather than approximated.
     */
    private fun WorkspaceStacks.visibleUnitIds(
        pageState: WorkspacePageManager.State,
        extraSeeds: Set<Workspace.Id> = emptySet(),
    ): Set<Workspace.Id> {
        val rendered = renderedChains(focusedId = pageState.focusedWorkspaceId)
        // Pane-local chains need no seed of their own: they only render inside their own tab's pane,
        // and a tab occupying a pane is already a selection seed. The pager's published pages are a
        // seed of their own though - mid-swipe they name tabs that no assignment does yet.
        val seeds = pageState.visibleWorkspaceIds() +
            rendered.fullScreen?.memberIds.orEmpty() +
            pagerVisibility.visibleIds() +
            extraSeeds
        return seeds.flatMapTo(mutableSetOf()) { seed ->
            unitOf(seed)?.map { it.id } ?: listOf(seed)
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "AutoPause")
        private val TICK_INTERVAL = 1.minutes
    }
}
