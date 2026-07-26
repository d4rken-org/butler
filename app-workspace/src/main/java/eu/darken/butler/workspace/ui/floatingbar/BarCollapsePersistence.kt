package eu.darken.butler.workspace.ui.floatingbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.restore.Outcome
import eu.darken.butler.workspace.ui.restore.restoreWhenReady

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
        val outcome = restoreWhenReady(
            saved = lease.saved,
            isNoOp = { it == 0f },
            isReady = { state.hasRegisteredBars },
            apply = { state.applyCollapse(it) },
        )
        log(TAG) { "restore(${lease.saved}) -> $outcome" }

        // A timeout means no bar ever registered. Recording the resulting default would overwrite a
        // good saved fraction with "expanded", so this stack simply stops persisting.
        if (outcome == Outcome.TIMED_OUT) return@LaunchedEffect

        // Continuous while enabled, and deliberately no write on disposal: during a pane move both
        // call sites exist briefly and dispose/create ordering is not defined, so the registry has to
        // already hold the value as of the outgoing pane's last composed frame.
        snapshotFlow { state.collapseTarget }.collect { registry.record(lease, it) }
    }
}
