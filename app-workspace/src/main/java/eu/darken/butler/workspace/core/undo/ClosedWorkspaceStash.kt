package eu.darken.butler.workspace.core.undo

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one close a user can still take back, assembled from two independent halves.
 *
 * The repo contributes WHAT was closed once the close committed; the UI layer contributes WHERE it
 * was and what view state it held, per member, before it tears that state down. Neither half can be
 * produced by the other side, and the two arrive in no fixed order, so the bar is offered only once
 * three conditions hold - identity half committed, UI half complete, and the close's own
 * destructive handling finished. The last one is not implied by the first two: the UI half is
 * contributed BEFORE each teardown, so without it an immediate undo could restore ids that the
 * still-running teardown then strips again.
 *
 * At most one entry, latest wins. Anything that changes which workspaces exist drops it (see
 * [onWorkspaceIdSetChanged]) - an undo is only honest while the world it was captured in still
 * exists.
 */
@Singleton
class ClosedWorkspaceStash @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
) {

    private val lock = Any()
    private val tokens = AtomicLong(0L)

    /**
     * Current logical incarnation of every workspace that exists, stamped by the repo when one is
     * created or replaced in place and preserved across pause/resume.
     *
     * The authority for "is this close still the truth": a consumer that would destroy something
     * belonging to the id can tell a close that stands from one an undo has already taken back.
     */
    private val incarnations = mutableMapOf<Workspace.Id, Long>()

    private var pending: Assembly? = null

    /** Token of the close operation currently in flight; only ITS OWN publications may not invalidate. */
    private var armedToken: Long? = null

    /** Set while an undo restore is publishing, for the same reason as [armedToken]. */
    private var restoring = false

    private val tickets = mutableMapOf<Workspace.Id, ClosedWorkspaceRestoreTicket>()

    private val _feedback = MutableStateFlow<ClosedWorkspaceFeedback?>(null)

    /** The bar to show, or null. Non-null only once the entry is complete and safe to act on. */
    val feedback: StateFlow<ClosedWorkspaceFeedback?> = _feedback.asStateFlow()

    private class Assembly(
        val closeToken: Long,
        val rootId: Workspace.Id,
        val memberIds: Set<Workspace.Id>,
    ) {
        var snapshot: ClosedWorkspaceSnapshot? = null
        var placement: ClosedWorkspacePlacement? = null
        var placementCaptured: Boolean = false
        val slots: MutableMap<Workspace.Id, ClosedWorkspaceMemberSlots> = mutableMapOf()
        var destructionComplete: Boolean = false
        var timeoutJob: Job? = null
        var published: Boolean = false

        val uiHalfComplete: Boolean
            get() = placementCaptured && slots.keys.containsAll(memberIds)

        val isComplete: Boolean
            get() = snapshot != null && uiHalfComplete && destructionComplete
    }

    /** Monotonic token for a close operation, a restore, or a workspace incarnation. */
    fun nextToken(): Long = tokens.incrementAndGet()

    /**
     * Stamps a fresh incarnation for [id]. Called by the repo for a logical creation and for a
     * replacement that keeps the id; a pause or resume keeps the existing stamp, because the tab the
     * user sees is the same one.
     */
    fun stampIncarnation(id: Workspace.Id): Long = synchronized(lock) {
        val token = tokens.incrementAndGet()
        incarnations[id] = token
        token
    }

    fun dropIncarnation(id: Workspace.Id) = synchronized(lock) {
        incarnations.remove(id)
        Unit
    }

    fun clearIncarnations() = synchronized(lock) {
        incarnations.clear()
    }

    /**
     * Incarnation currently living under [id], or null when nothing does.
     *
     * A consumer of [eu.darken.butler.workspace.core.WorkspaceEvent.Closed] compares this against
     * the token it saw: a differing (or newly present) token means an undo restored the id and the
     * close it is processing no longer describes anything.
     */
    fun currentTokenOf(id: Workspace.Id): Long? = synchronized(lock) { incarnations[id] }

    /**
     * Opens the window for a new undoable close. Supersedes whatever was stashed before, ONCE and
     * before the close itself begins - so the close's own publications cannot erase the entry it is
     * about to build.
     */
    fun armClose(closeToken: Long, rootId: Workspace.Id, memberIds: Set<Workspace.Id>) = synchronized(lock) {
        log(TAG) { "armClose($closeToken, root=$rootId, members=${memberIds.size})" }
        dropPendingLocked("superseded by close $closeToken")
        pending = Assembly(closeToken, rootId, memberIds)
        armedToken = closeToken
    }

    /** The close is not going to be undoable after all (revalidation failed, or it was cancelled). */
    fun abortClose(closeToken: Long) = synchronized(lock) {
        if (armedToken == closeToken) armedToken = null
        if (pending?.closeToken != closeToken) return@synchronized
        log(TAG) { "abortClose($closeToken)" }
        dropPendingLocked("aborted")
    }

    /** Identity half, committed by the repo in the same critical section as the close itself. */
    fun commitIdentity(snapshot: ClosedWorkspaceSnapshot) = synchronized(lock) {
        val assembly = pending
        if (assembly == null || assembly.closeToken != snapshot.closeToken) {
            log(TAG, WARN) { "commitIdentity(${snapshot.closeToken}): no matching pending close" }
            return@synchronized
        }
        assembly.snapshot = snapshot
        publishIfReadyLocked(assembly)
    }

    /** The close operation is over; from here its publications are ordinary mutations again. */
    fun disarm(closeToken: Long) = synchronized(lock) {
        if (armedToken == closeToken) armedToken = null
    }

    /**
     * Drops the entry whenever the set of open workspace ids changes.
     *
     * The id SET, not any publication: pause and resume swap instances in place without changing it,
     * and closing a tab next to a paused one resumes that neighbour on focus - under a coarser rule
     * the bar would appear and vanish again in the same breath, on the default settings.
     *
     * [closeToken] names the close operation the publication belongs to, and only a publication
     * carrying the pending entry's own token is exempt. Being armed is not enough on its own: the
     * capture window runs without the repo mutex, so an unrelated create or close can publish while
     * it is open, and that is exactly the kind of change the entry may not survive.
     *
     * Called synchronously from every publish site while the repo holds its lock, so an entry can
     * never be committed against a list that already moved on.
     */
    fun onWorkspaceIdSetChanged(closeToken: Long? = null) = synchronized(lock) {
        if (restoring) return@synchronized
        val assembly = pending ?: return@synchronized
        if (closeToken != null && closeToken == assembly.closeToken && armedToken == assembly.closeToken) {
            return@synchronized
        }
        log(TAG) { "Workspace set changed, dropping undo entry ${assembly.closeToken}" }
        dropPendingLocked("workspace set changed")
    }

    /** Close token [id] is closing under, for the capture points that see ids rather than events. */
    fun closeTokenFor(id: Workspace.Id): Long? = synchronized(lock) {
        pending?.takeIf { id in it.memberIds }?.closeToken
    }

    /** Root and members of the close [closeToken] belongs to, or null when it is no longer pending. */
    fun pendingUnitOf(closeToken: Long): Pair<Workspace.Id, Set<Workspace.Id>>? = synchronized(lock) {
        pending?.takeIf { it.closeToken == closeToken }?.let { it.rootId to it.memberIds }
    }

    /** Per-member view state, contributed before that member's own state is torn down. */
    fun contributeSlots(
        closeToken: Long,
        memberId: Workspace.Id,
        slots: ClosedWorkspaceMemberSlots,
    ) = synchronized(lock) {
        val assembly = pending?.takeIf { it.closeToken == closeToken } ?: return@synchronized
        assembly.slots[memberId] = slots
        publishIfReadyLocked(assembly)
    }

    /**
     * Pane and focus, from whichever capture point observes them first.
     *
     * First writer wins: both points read state that the other one is about to destroy, so a later
     * writer would only ever record the damage.
     */
    fun capturePlacement(closeToken: Long, placement: ClosedWorkspacePlacement) = synchronized(lock) {
        val assembly = pending?.takeIf { it.closeToken == closeToken } ?: return@synchronized
        if (assembly.placementCaptured) return@synchronized
        log(TAG) { "capturePlacement($closeToken, $placement)" }
        assembly.placement = placement
        assembly.placementCaptured = true
        publishIfReadyLocked(assembly)
    }

    /** The close's destructive handling is done; an undo can no longer be undone by it. */
    fun markDestructionComplete(closeToken: Long) = synchronized(lock) {
        val assembly = pending?.takeIf { it.closeToken == closeToken } ?: return@synchronized
        assembly.destructionComplete = true
        publishIfReadyLocked(assembly)
    }

    /** The complete entry an undo would restore, or null while there is nothing to offer. */
    fun peekEntry(): ClosedWorkspaceEntry? = synchronized(lock) {
        val assembly = pending ?: return@synchronized null
        val snapshot = assembly.snapshot
        if (!assembly.published || snapshot == null) return@synchronized null
        ClosedWorkspaceEntry(
            snapshot = snapshot,
            slots = assembly.slots.toMap(),
            placement = assembly.placement ?: ClosedWorkspacePlacement(null, null),
        )
    }

    /**
     * Arguments held by anything stashed, including an entry that is still assembling.
     *
     * Whatever a stashed workspace pointed at is still reachable - the user can bring it back - so
     * resource reclamation has to count the stash as a holder.
     */
    fun peekStashedArguments(): List<Workspace.Arguments> = synchronized(lock) {
        pending?.snapshot?.members?.map { it.arguments }.orEmpty()
    }

    /** Drops the entry, after its restore was published. */
    fun consume(closeToken: Long) = synchronized(lock) {
        if (pending?.closeToken != closeToken) return@synchronized
        dropPendingLocked("restored")
    }

    /** The user dismissed the bar, or the feature was turned off. */
    fun dismiss() = synchronized(lock) {
        dropPendingLocked("dismissed")
    }

    /** Publications made while restoring are the restore itself and must not invalidate anything. */
    fun beginRestore() = synchronized(lock) {
        restoring = true
    }

    fun endRestore() = synchronized(lock) {
        restoring = false
    }

    fun armRestoreTicket(ticket: ClosedWorkspaceRestoreTicket) = synchronized(lock) {
        log(TAG) { "armRestoreTicket(${ticket.rootId}, ${ticket.restoreToken})" }
        tickets[ticket.rootId] = ticket
    }

    /**
     * Hands the ticket for [rootId] to the first caller that asks for it and forgets it.
     *
     * Two paths apply tickets - the event the restore emits and the caller that requested it - so
     * that a cancelled caller cannot strand a restored tab. Handing it out once is what makes
     * applying it twice a no-op.
     */
    fun takeRestoreTicket(rootId: Workspace.Id, restoreToken: Long?): ClosedWorkspaceRestoreTicket? =
        synchronized(lock) {
            val ticket = tickets[rootId] ?: return@synchronized null
            if (restoreToken != null && ticket.restoreToken != restoreToken) return@synchronized null
            // A ticket only ever applies to the incarnation it was issued for; a same-id
            // replacement stamps a new one and must not inherit the closed tab's placement.
            if (incarnations[rootId] != ticket.restoreToken) {
                log(TAG, WARN) { "Dropping stale restore ticket for $rootId" }
                tickets.remove(rootId)
                return@synchronized null
            }
            tickets.remove(rootId)
            ticket
        }

    private fun publishIfReadyLocked(assembly: Assembly) {
        if (assembly.published) return
        val snapshot = assembly.snapshot
        if (snapshot == null || !assembly.isComplete) {
            // Fail-safe: an identity half whose UI half never lands must not sit here forever, and
            // it must not be offered either - it would restore a tab with no pane and no view state.
            if (snapshot != null && assembly.timeoutJob == null) {
                assembly.timeoutJob = appScope.launch {
                    delay(ASSEMBLY_TIMEOUT)
                    synchronized(lock) {
                        val stuck = pending?.takeIf { it.closeToken == assembly.closeToken && !it.published }
                        if (stuck != null) {
                            log(TAG, WARN) { "Undo entry ${assembly.closeToken} never completed, dropping it" }
                            dropPendingLocked("assembly timed out")
                        }
                    }
                }
            }
            return
        }

        assembly.published = true
        assembly.timeoutJob?.cancel()
        assembly.timeoutJob = appScope.launch {
            delay(FEEDBACK_TIMEOUT)
            synchronized(lock) {
                if (pending?.closeToken == assembly.closeToken) dropPendingLocked("timed out")
            }
        }
        _feedback.value = ClosedWorkspaceFeedback(
            closeToken = snapshot.closeToken,
            customTitle = snapshot.root.customTitle,
            // The tab is named by what was on top of it, which is the last member of the unit.
            automaticTitle = snapshot.members.last().automaticTitle,
        )
        log(TAG, INFO) { "Undo entry ${snapshot.closeToken} is complete and offered" }
    }

    private fun dropPendingLocked(reason: String) {
        val assembly = pending ?: return
        log(TAG) { "Dropping undo entry ${assembly.closeToken}: $reason" }
        assembly.timeoutJob?.cancel()
        pending = null
        if (armedToken == assembly.closeToken) armedToken = null
        _feedback.value = null
    }

    companion object {
        private val TAG = logTag("Workspace", "ClosedStash")

        /** How long the undo bar stays up once the entry is complete. */
        val FEEDBACK_TIMEOUT: Duration = 5.seconds

        /** How long an identity half waits for its UI half before it is dropped unoffered. */
        val ASSEMBLY_TIMEOUT: Duration = 5.seconds
    }
}
