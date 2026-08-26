package eu.darken.butler.workspace.ui.modal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf

/**
 * Ordered stack of the modal layers currently present in one workspace pane.
 *
 * Exactly one layer is the topmost one at any time; that layer owns back dispatch, keyboard focus
 * and accessibility traversal for the whole pane. Everything else derives from that single answer
 * instead of each mechanism guessing on its own.
 *
 * Ordering is by [PaneLayerRank] first and registration order second, so the stack stays correct
 * even when a layer appears out of composition order (e.g. a parent dialog opening while a
 * pane-local child modal is already up).
 */
@Stable
class PaneLayerState {

    private data class Entry(val token: Any, val rank: Int, val parent: Any?)

    private val entries = mutableStateListOf<Entry>()

    /** Topmost layer, or `null` while the pane holds no layer at all. */
    val topLayer: Any? by derivedStateOf { entries.lastOrNull()?.token }

    /**
     * The topmost layer plus every layer enclosing it.
     *
     * A layer that *contains* the top one must not be deactivated: hiding it from accessibility or
     * refusing focus entry would take the layer on top down with it. It still isn't the active
     * layer, so its own back handlers stay disabled.
     */
    private val topPath: Set<Any> by derivedStateOf {
        val byToken = entries.associateByTo(mutableMapOf()) { it.token }
        val path = mutableSetOf<Any>()
        var current = entries.lastOrNull()
        while (current != null && path.add(current.token)) {
            current = current.parent?.let { byToken[it] }
        }
        path
    }

    val layerCount: Int
        get() = entries.size

    fun push(token: Any, rank: Int, parent: Any? = null) {
        if (entries.any { it.token === token }) return
        val insertAt = entries.indexOfFirst { it.rank > rank }.let { if (it == -1) entries.size else it }
        entries.add(insertAt, Entry(token, rank, parent))
    }

    fun pop(token: Any) {
        entries.removeAll { it.token === token }
    }

    fun isTop(token: Any): Boolean = topLayer === token

    /** True when [token] is the top layer or encloses it. */
    fun isOnTopPath(token: Any): Boolean = token in topPath
}

/**
 * Stacking ranks for the layers of a single pane, bottom to top.
 *
 * A pane holds one tier of layers per modal depth: `rank = base + depth * TIER`, where depth 0 is
 * the pane's own workspace and every pane-local modal stacked on it adds one.
 *
 * Dialogs and sheets register at the ambient [LocalPaneLayerRank] of the region they are composed
 * in, so a dialog in one workspace's overlay slot always sits above that workspace's content but
 * below the pane-local modal stacked on top of it.
 */
object PaneLayerRank {
    /** Rank span reserved for one modal depth tier. */
    const val TIER = 300
    const val CONTENT = 0
    const val OVERLAY = 100
    const val MANAGER = 200

    fun contentAt(depth: Int): Int = CONTENT + tierOffset(depth)
    fun overlayAt(depth: Int): Int = OVERLAY + tierOffset(depth)
    fun managerAt(depth: Int): Int = MANAGER + tierOffset(depth)

    private fun tierOffset(depth: Int): Int {
        require(depth >= 0) { "Layer depth must be >= 0, was $depth" }
        return depth * TIER
    }

    const val CHILD_CONTENT = 300
    const val CHILD_OVERLAY = 400
    const val CHILD_MANAGER = 500
}

/**
 * True when this composition is the topmost layer of a focused pane.
 *
 * Back handlers, focus requests and any other "am I the one the user is talking to" decision should
 * read this instead of [eu.darken.butler.workspace.ui.LocalWorkspaceFocused], which only answers
 * which pane is focused and cannot see modal layers within a pane.
 */
val LocalLayerActive = compositionLocalOf { true }

/** The pane's layer stack, or `null` outside a pane (previews, offscreen capture). */
val LocalPaneLayerState = compositionLocalOf<PaneLayerState?> { null }

/** Whether the enclosing pane is the focused one; independent of which layer within it is on top. */
val LocalPaneFocused = compositionLocalOf { true }

/**
 * Whether this pane is the one system Back should be dispatched to.
 *
 * Distinct from [LocalPaneFocused]/[LocalLayerActive] on purpose: a pane can legitimately be the
 * focused one while not being on screen — the classic pager parks on its trailing new-tab
 * placeholder without focus moving off the last tab — and a back handler armed for a page the user
 * is not looking at consumes Back and acts on the wrong workspace.
 *
 * Defaults to true: outside a pager-driven pane there is nothing to withhold.
 */
val LocalPaneBackActive = compositionLocalOf { true }

/**
 * Whether a press arriving in this pane may act on it.
 *
 * A lambda rather than a boolean because it is read at event time: a pager-driven layout answers
 * false for a page it is not resting on, which flips several times per swipe and must take effect
 * without waiting for the subtree to recompose.
 *
 * Withholds taps and press-to-focus-the-pane, NOT interaction as such: a withheld down is consumed,
 * and scroll, drag, sheet-drag and drag-and-drop detectors all accept a consumed down — the pager's
 * own swipe depends on that. A pane whose gate is closed can still be scrolled and dragged.
 *
 * Defaults to `{ true }`: outside a pager-driven pane there is nothing to withhold.
 */
val LocalPanePressesAllowed = compositionLocalOf<() -> Boolean> { { true } }

/** Rank a layer registers at when it doesn't pick one explicitly. */
val LocalPaneLayerRank = compositionLocalOf { PaneLayerRank.CONTENT }

/** Token of the enclosing layer, so nesting can be distinguished from stacking. */
val LocalPaneLayerParent = compositionLocalOf<Any?> { null }

/**
 * True when the enclosing workspace is stacked as a pane-local modal rather than being the pane's
 * own workspace. Known at composition time, unlike anything a page collects from its ViewModel, so
 * a page can gate modal-only behaviour from the very first frame.
 */
val LocalWorkspaceIsPaneModal = compositionLocalOf { false }
