package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.restoreWhenReady
import kotlinx.coroutines.flow.filter
import kotlin.time.Duration

private val TAG = logTag("Workspace", "BarCollapse")

/**
 * Carries a bar stack's collapse state across compositions, so a workspace that comes back in a
 * different pane (or after a restart) reserves the same content padding it had when it left.
 *
 * A [workspaceId] of null keeps the stack ephemeral - previews and offscreen preview capture render
 * real pages and must neither read nor write the live state.
 */
@Composable
internal fun PersistBarCollapse(
    workspaceId: Workspace.Id?,
    position: BarPosition,
    state: FloatingBarStackState,
) {
    if (workspaceId == null) return

    val registry = LocalWorkspaceBarCollapseStates.current
    val lease = remember(registry, workspaceId, position) {
        registry.collapseFor(workspaceId, position).also {
            log(TAG, VERBOSE) { "Lease taken: workspace=$workspaceId, position=$position, saved=${it.saved}" }
        }
    }

    LaunchedEffect(state, lease) {
        // No supersede arm, unlike the scroll restore: bars register within the first composition
        // pass, so there is no multi-second window in which the user could act first. What can
        // change a fraction meanwhile - a scroll, resetScrollCollapse(), a revealOn change - cannot
        // be told apart from the restore by the fraction alone, so racing it would be guesswork.
        //
        // The wait is unbounded, unlike the scroll restore's deliberate 5s bound: a page can compose
        // its stack states long before it renders any bar (Searcher builds them, then returns early
        // until it is Ready), and a timeout there would permanently disarm both the restore and the
        // recording for that composition. Waiting costs nothing - the effect dies with the page.
        val outcome = restoreWhenReady(
            saved = lease.saved,
            isNoOp = { targets -> targets.isEmpty() || targets.values.all { it == 0f } },
            isReady = { state.hasRegisteredBars },
            timeout = Duration.INFINITE,
            apply = { state.applyCollapse(it) },
        )
        log(TAG) { "restore(${lease.saved}) -> $outcome" }

        // Continuous while enabled, and deliberately no write on disposal: during a pane move both
        // call sites exist briefly and dispose/create ordering is not defined, so the registry has to
        // already hold the value as of the outgoing pane's last composed frame.
        //
        // An empty map is "no bars registered", not "everything expanded" - recording it would let a
        // page that is still initializing, or one that is being torn down, wipe a good saved value.
        snapshotFlow { state.collapseTargets }
            .filter { it.isNotEmpty() }
            .collect { registry.record(lease, it) }
    }
}
