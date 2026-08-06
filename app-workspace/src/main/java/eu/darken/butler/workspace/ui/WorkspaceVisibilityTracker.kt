package eu.darken.butler.workspace.ui

import androidx.compose.runtime.staticCompositionLocalOf
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which tabs the single-pane pager actually has on screen, published by the classic container.
 *
 * Pane assignments and focus cannot answer this during a swipe: they still name the outgoing page
 * while the incoming one is already most of the screen. Auto-pause reading them alone can release a
 * workspace the user is looking at, and settling on it afterwards lands on a paused placeholder,
 * because a settle only selects - it never resumes.
 *
 * Deliberately NOT part of [WorkspacePageManager.State]: that state is parcelled into the
 * SavedStateHandle and observed by the session manager, so per-frame visibility there would persist
 * ephemeral data and fire a full session save (a `createArguments()` across every workspace) all the
 * way through a swipe, cancelled ones included.
 *
 * ## Why this is not just a `StateFlow<Set<Id>>`
 *
 * Auto-pause consumes transitions, not samples: it evaluates on a one-minute ticker and on
 * foreground events, so a conflated "latest value" would silently swallow a visible -> hidden pair
 * that happened between two ticks, and the glimpsed workspace would be paused as if it had never
 * been seen. Two mechanisms close that:
 *
 * - a per-tab generation stamp bumped *before* the new set is exposed, so a release can capture
 *   stamps beforehand and detect any sighting that happened while it ran ([wasSeenSince]);
 * - a just-paused record ([guardPaused]) that turns a publication landing right after the release's
 *   own backstop into a [reappeared] signal instead of a lost one.
 *
 * All state is guarded by one monitor and every operation is synchronous, so a publication from the
 * UI thread and an evaluation on the app scope can never interleave halfway.
 */
@Singleton
class WorkspaceVisibilityTracker @Inject constructor() {

    private val lock = Any()

    /**
     * The composition currently allowed to publish. A container that is disposed after its
     * replacement already claimed must not clear the newer one's state, which is what this
     * ownership token prevents.
     */
    private var publisher: Any? = null
    private var visible: Set<Workspace.Id> = emptySet()

    private var generation: Long = 0L
    private val lastSeen = mutableMapOf<Workspace.Id, Long>()
    private val justPaused = mutableSetOf<Workspace.Id>()

    private val _reappeared = Channel<Workspace.Id>(Channel.UNLIMITED)

    /**
     * Units that were published as visible again while still carrying a just-paused record. Single
     * consumer by design - auto-pause owns the resume.
     */
    val reappeared: Flow<Workspace.Id> = _reappeared.receiveAsFlow()

    /** Takes over publishing and returns the token every later call has to present. */
    fun claim(): Any = synchronized(lock) {
        val token = Any()
        publisher = token
        visible = emptySet()
        token
    }

    /** Gives up publishing. A stale token is ignored, so a late disposal cannot clear a new owner. */
    fun release(token: Any) {
        synchronized(lock) {
            if (publisher !== token) return
            publisher = null
            visible = emptySet()
        }
    }

    /**
     * Publishes the tabs on screen right now. Stamps come first: an observer that reads the set
     * afterwards must never be able to see it without the sighting having been recorded.
     */
    fun publish(token: Any, ids: Set<Workspace.Id>) {
        var woken: Set<Workspace.Id> = emptySet()
        synchronized(lock) {
            if (publisher !== token) return
            generation++
            ids.forEach { lastSeen[it] = generation }
            visible = ids
            if (justPaused.isNotEmpty()) {
                woken = justPaused.intersect(ids)
                justPaused -= woken
            }
        }
        woken.forEach { _reappeared.trySend(it) }
    }

    fun visibleIds(): Set<Workspace.Id> = synchronized(lock) { visible }

    /** Generation stamps for [ids] as of now, to be handed to [wasSeenSince] after a suspending step. */
    fun seenStamps(ids: Collection<Workspace.Id>): Map<Workspace.Id, Long> = synchronized(lock) {
        ids.associateWith { lastSeen[it] ?: 0L }
    }

    /** True when any of the stamped ids has been published again since [seenStamps] was taken. */
    fun wasSeenSince(stamps: Map<Workspace.Id, Long>): Boolean = synchronized(lock) {
        stamps.any { (id, stamp) -> (lastSeen[id] ?: 0L) != stamp }
    }

    /** Arms the just-paused record for a unit's root; a later publication turns into [reappeared]. */
    fun guardPaused(id: Workspace.Id) {
        synchronized(lock) { justPaused += id }
    }

    /** Retires the record, either because the unit was resumed or because it has been re-evaluated. */
    fun retirePaused(ids: Collection<Workspace.Id>) {
        synchronized(lock) { justPaused -= ids.toSet() }
    }

    /** Drops bookkeeping for workspaces that no longer exist. */
    fun forget(liveIds: Set<Workspace.Id>) {
        synchronized(lock) {
            lastSeen.keys.retainAll(liveIds)
            justPaused.retainAll(liveIds)
        }
    }
}

/**
 * The tracker the classic container publishes to.
 *
 * The default is a detached instance so previews, screenshot tests and any composition without the
 * app graph work unprovided - and cannot feed the live auto-pause bookkeeping.
 */
val LocalWorkspacePagerVisibility = staticCompositionLocalOf { WorkspaceVisibilityTracker() }
