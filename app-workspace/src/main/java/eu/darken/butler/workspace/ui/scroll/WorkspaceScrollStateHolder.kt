package eu.darken.butler.workspace.ui.scroll

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

const val DEFAULT_SCROLL_SLOT = "default"

/**
 * A [LazyListState] whose position is remembered for [workspaceId]/[slot] across compositions.
 *
 * A new [slot] yields a fresh state, so per-directory slots keep Explorer's "clean slate on
 * navigation" behaviour while making the previous directory restorable.
 */
@Composable
fun rememberWorkspaceLazyListState(
    workspaceId: Workspace.Id?,
    slot: String = DEFAULT_SCROLL_SLOT,
): LazyListState {
    if (workspaceId == null) return rememberLazyListState()

    val registry = LocalWorkspaceScrollPositions.current
    val lease = remember(registry, workspaceId, slot) { registry.positionFor(workspaceId, slot) }
    val state = remember(lease) { LazyListState(lease.saved?.index ?: 0, lease.saved?.offset ?: 0) }

    RestoreAndRecord(
        registry = registry,
        lease = lease,
        target = remember(state) { state.asScrollTarget() },
    )
    return state
}

@Composable
fun rememberWorkspaceLazyGridState(
    workspaceId: Workspace.Id?,
    slot: String = DEFAULT_SCROLL_SLOT,
): LazyGridState {
    if (workspaceId == null) return rememberLazyGridState()

    val registry = LocalWorkspaceScrollPositions.current
    val lease = remember(registry, workspaceId, slot) { registry.positionFor(workspaceId, slot) }
    val state = remember(lease) { LazyGridState(lease.saved?.index ?: 0, lease.saved?.offset ?: 0) }

    RestoreAndRecord(
        registry = registry,
        lease = lease,
        target = remember(state) { state.asScrollTarget() },
    )
    return state
}

/**
 * Recording is continuous while enabled - every position change, and deliberately no write on
 * disposal. During a pane move both call sites exist briefly and dispose/create ordering is not
 * defined, so the registry has to already hold the position as of the outgoing pane's last composed
 * frame. The only thing lost is a fling's remaining momentum, which disposal cancels anyway.
 */
@Composable
private fun RestoreAndRecord(
    registry: WorkspaceScrollPositions,
    lease: WorkspaceScrollPositions.Lease,
    target: ScrollTarget,
) {
    LaunchedEffect(target, lease) {
        if (target.restore(lease.saved) == Outcome.TIMED_OUT) {
            // A timeout must never license writing a layout-clamped position over a good saved one.
            // The safe failure mode is "not restored", never "destroyed".
            target.interactions.first { it is DragInteraction.Start }
        }
        snapshotFlow { target.position }.collect { registry.record(lease, it) }
    }
}

private fun LazyListState.asScrollTarget(): ScrollTarget = object : ScrollTarget {
    override val totalItemsCount: Int
        get() = layoutInfo.totalItemsCount
    override val position: WorkspaceScrollPosition
        get() = WorkspaceScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)
    override val isScrollInProgress: Boolean
        get() = this@asScrollTarget.isScrollInProgress
    override val interactions: Flow<Interaction>
        get() = interactionSource.interactions

    override suspend fun scrollTo(position: WorkspaceScrollPosition) =
        scrollToItem(position.index, position.offset)
}

private fun LazyGridState.asScrollTarget(): ScrollTarget = object : ScrollTarget {
    override val totalItemsCount: Int
        get() = layoutInfo.totalItemsCount
    override val position: WorkspaceScrollPosition
        get() = WorkspaceScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)
    override val isScrollInProgress: Boolean
        get() = this@asScrollTarget.isScrollInProgress
    override val interactions: Flow<Interaction>
        get() = interactionSource.interactions

    override suspend fun scrollTo(position: WorkspaceScrollPosition) =
        scrollToItem(position.index, position.offset)
}
