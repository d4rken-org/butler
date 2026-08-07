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
    val id: Workspace.Id,
    val type: Workspace.Type,
    val title: CaString,
    val subtitle: CaString?,
    val openedAt: Instant,
    /** Why this tab may not be closed, or null when it may. */
    val blocker: Blocker? = null,
) {
    val isClosable: Boolean get() = blocker == null

    /**
     * Reasons a tab is off limits. Each mirrors one rule of the repo's victim check and is stricter
     * than what a manual close would refuse: closing here is not something the user can undo.
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

        /** Owns an open modal; closing it would take that modal - possibly the current screen - with it. */
        HAS_MODAL,
    }
}
