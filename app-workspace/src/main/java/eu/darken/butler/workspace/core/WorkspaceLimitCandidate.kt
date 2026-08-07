package eu.darken.butler.workspace.core

import eu.darken.butler.common.ca.CaString
import kotlin.time.Instant

/**
 * An open tab as the free-tier limit dialog sees it: enough to tell tabs apart, plus whether closing
 * it to make room is allowed.
 *
 * Lives in the domain layer because the dialog has to name *specific* tabs. A blocked candidate is
 * still listed - a list that silently omits the tabs the user can see would read as a bug, and the
 * [blocker] is the answer to "why can't I close that one".
 */
data class WorkspaceLimitCandidate(
    /** The tab itself - the ownership root. Closing this takes the whole stack down. */
    val id: Workspace.Id,
    /**
     * [type], [title] and [subtitle] name what is on TOP of the tab's stack, not its root: an Apps
     * tab with an app's details open is "Ad privacy" to the user, and naming the root would point at
     * something they cannot see. Mirrors the tab manager's cards.
     *
     * "Top" is resolved without a focus hint, so for the rare tab holding two sibling branches this
     * names the newest rather than whichever one is composed on screen. Both belong to the tab that
     * gets closed either way.
     */
    val type: Workspace.Type,
    val title: CaString,
    val subtitle: CaString?,
    val openedAt: Instant,
    /** How many workspaces are stacked on the tab; 0 for a plain tab. Drives the stack badge. */
    val stackDepth: Int = 0,
    /** Why this tab may not be closed, or null when it may. */
    val blocker: Blocker? = null,
) {
    val isClosable: Boolean get() = blocker == null

    /**
     * Reasons a tab is off limits. Each mirrors one rule of the repo's victim check and is stricter
     * than what a manual close would refuse: closing here is not something the user can undo.
     *
     * Every reason answers for the whole stack, not just the tab's root: closing a tab closes what
     * is stacked on it, so a busy drill-down makes the tab it sits on busy too.
     */
    enum class Blocker {
        /** In-memory changes that a close would discard. */
        UNSAVED_CHANGES,

        /** File operations in flight, or an open-transition claiming this tab. */
        BUSY,

        /** Something is waiting on the user inside that tab. */
        NEEDS_ATTENTION,

        /** Still setting up, so its zero counters say nothing about what would be lost. */
        LOADING,

        /**
         * A picker in this tab's stack owes its caller a result. Closing the tab cancels it silently,
         * and the collector waiting for it lives in a workspace that is not being closed. A plain
         * drill-down (app details, a viewer) is NOT this: it owes nobody anything.
         */
        AWAITING_RESULT,

        /**
         * The tab's ownership chain could not be resolved, so there is no safe set of workspaces to
         * close and no honest reason to name. Should not happen for a counted tab; refusing beats
         * guessing, and beats blaming a picker that may not exist.
         */
        UNAVAILABLE,
    }
}
