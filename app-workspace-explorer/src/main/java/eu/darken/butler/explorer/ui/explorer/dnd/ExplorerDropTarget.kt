package eu.darken.butler.explorer.ui.explorer.dnd

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import eu.darken.butler.common.compose.autoscroll.EdgeAutoScroller
import eu.darken.butler.common.compose.autoscroll.LazyGridAutoScrollTarget
import eu.darken.butler.common.compose.autoscroll.LazyListAutoScrollTarget
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.ui.explorer.ExplorerWorkspaceViewModel
import eu.darken.butler.workspace.contracts.dnd.WorkspaceDragPayload
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import eu.darken.butler.workspace.ui.dnd.DropHit
import eu.darken.butler.workspace.ui.dnd.DropZoneRegistry
import eu.darken.butler.workspace.ui.dnd.positionInRoot
import eu.darken.butler.workspace.ui.dnd.resolveDropHit
import eu.darken.butler.workspace.ui.dnd.workspaceDragPayload
import eu.darken.butler.workspace.ui.modal.LocalLayerOnTopPath

/**
 * What the page's single drop target needs to know about the content below it: where the folder
 * zones are, where the content band is and whether the pane itself is being hovered.
 */
class ExplorerDropState {

    val registry = DropZoneRegistry()

    /** Root-relative bounds of the ready content, floating bars included. */
    var contentBounds: Rect = Rect.Zero

    var topBarPaddingPx: Float = 0f
    var bottomBarPaddingPx: Float = 0f

    var isPaneHovered by mutableStateOf(false)

    /** The content minus the areas the floating bars cover, where a drop means "this listing". */
    val contentBand: Rect
        get() {
            val top = contentBounds.top + topBarPaddingPx
            val bottom = contentBounds.bottom - bottomBarPaddingPx
            if (bottom <= top) return Rect.Zero
            return Rect(left = contentBounds.left, top = top, right = contentBounds.right, bottom = bottom)
        }
}

@Composable
fun rememberExplorerDropState(): ExplorerDropState = remember { ExplorerDropState() }

/**
 * The Explorer page's only drop target, covering content and floating bars alike.
 *
 * A drag session is handed to the targets that exist when it starts, so a row scrolled in while
 * the drag is running could never own one. Folder rows, favorites and crumbs publish their bounds
 * to [ExplorerDropState.registry] instead and this target hit-tests them on every move.
 *
 * @param onDrop a null destination means the content background, i.e. the current listing.
 */
@Composable
fun Modifier.explorerDropTarget(
    dropState: ExplorerDropState,
    workspaceId: Workspace.Id,
    state: ExplorerWorkspaceViewModel.State,
    listState: LazyListState,
    gridState: LazyGridState,
    onDrop: (WorkspaceDragPayload, APath<*>?) -> Unit,
): Modifier {
    val currentState = rememberUpdatedState(state)
    val currentOnDrop = rememberUpdatedState(onDrop)
    // Same focus request AdaptiveWorkspaceLayout wires to WorkspaceScreenAction.Focus(info.id),
    // republished by WorkspacePane. Focusing the target pane before the drop opens the confirmation
    // dialog in an already-focused pane, so its first tap confirms instead of only focusing.
    val currentFocusRequest = rememberUpdatedState(LocalWorkspaceFocusRequest.current)
    val currentOnTopPath = rememberUpdatedState(LocalLayerOnTopPath.current)

    val session = remember(dropState, workspaceId) {
        ExplorerDropSession(
            dropState = dropState,
            workspaceId = workspaceId,
            state = currentState,
            onDropped = currentOnDrop,
            focusRequest = currentFocusRequest,
        )
    }

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scroller = remember(session, state.viewStyle, listState, gridState, scope, density) {
        val target = when (state.viewStyle) {
            is ExplorerViewStyle.Grid -> LazyGridAutoScrollTarget(gridState)
            is ExplorerViewStyle.List -> LazyListAutoScrollTarget(listState)
        }
        EdgeAutoScroller(
            scope = scope,
            target = target,
            edgePx = with(density) { EdgeAutoScroller.DefaultEdge.toPx() },
            maxSpeedPx = with(density) { EdgeAutoScroller.DefaultMaxSpeed.toPx() },
            // A pointer resting at the edge produces no events while rows keep moving under it.
            onScrolled = {
                session.retestLastPosition()
                true
            },
        )
    }
    // Navigation re-keys the list state and a view style switch swaps the whole container, so a
    // replaced scroller must never be left driving the detached one.
    DisposableEffect(session, scroller) {
        session.scroller = scroller
        onDispose {
            scroller.stop()
            if (session.scroller === scroller) session.scroller = null
        }
    }

    val shouldStartDragAndDrop = remember(session) {
        { event: DragAndDropEvent ->
            event.workspaceDragPayload() != null &&
                currentState.value.pickerConfig == null &&
                currentOnTopPath.value
        }
    }

    return this.dragAndDropTarget(
        shouldStartDragAndDrop = shouldStartDragAndDrop,
        target = session,
    )
}

private class ExplorerDropSession(
    private val dropState: ExplorerDropState,
    private val workspaceId: Workspace.Id,
    private val state: State<ExplorerWorkspaceViewModel.State>,
    private val onDropped: State<(WorkspaceDragPayload, APath<*>?) -> Unit>,
    private val focusRequest: State<(() -> Unit)?>,
) : DragAndDropTarget {

    var scroller: EdgeAutoScroller? = null

    private var payload: WorkspaceDragPayload? = null
    private var lastRootPointer: Offset = Offset.Unspecified

    override fun onEntered(event: DragAndDropEvent) = onMoved(event)

    override fun onMoved(event: DragAndDropEvent) {
        val payload = event.workspaceDragPayload() ?: return
        this.payload = payload
        lastRootPointer = event.positionInRoot()
        hitTest(payload, lastRootPointer)
        scroller?.update(
            pointer = lastRootPointer - dropState.contentBounds.topLeft,
            startInset = dropState.topBarPaddingPx,
            endInset = dropState.bottomBarPaddingPx,
        )
    }

    override fun onExited(event: DragAndDropEvent) = reset()

    override fun onEnded(event: DragAndDropEvent) = reset()

    override fun onDrop(event: DragAndDropEvent): Boolean {
        val payload = event.workspaceDragPayload()
        if (payload == null) {
            reset()
            return false
        }
        val hit = hitTest(payload, event.positionInRoot())
        reset()
        return when (hit) {
            is DropHit.Explicit -> {
                focusRequest.value?.invoke()
                onDropped.value(payload, hit.destination)
                true
            }
            DropHit.Pane -> {
                focusRequest.value?.invoke()
                onDropped.value(payload, null)
                true
            }
            // A zone that refuses the payload swallows the drop instead of letting it through to
            // the listing behind it, which is a different destination than the user aimed at.
            DropHit.Blocked, DropHit.None -> false
        }
    }

    /** Re-resolves the drop under a pointer that hasn't moved while auto-scroll shifted the rows. */
    fun retestLastPosition() {
        val payload = payload ?: return
        if (lastRootPointer == Offset.Unspecified) return
        hitTest(payload, lastRootPointer)
    }

    private fun hitTest(payload: WorkspaceDragPayload, position: Offset): DropHit {
        val contentBand = dropState.contentBand
        val eligible = { zone: DropZoneRegistry.Zone ->
            zone.allowOutsideContentBand || contentBand.contains(position)
        }
        val zone = dropState.registry.zoneAt(position, eligible)
        val current = state.value
        val hit = resolveDropHit(
            positionInRoot = position,
            zones = { _, _ -> zone },
            contentBand = contentBand,
            isValidExplicit = { validateFolderDrop(current, payload, it) != null },
        )
        dropState.registry.setHovered(if (hit is DropHit.Explicit) zone?.key else null)
        dropState.isPaneHovered = hit is DropHit.Pane && (
            validateDropDestination(current, workspaceId, payload) != null ||
                validateTrashDrop(current, workspaceId, payload)
            )
        return hit
    }

    private fun reset() {
        payload = null
        lastRootPointer = Offset.Unspecified
        dropState.registry.setHovered(null)
        dropState.isPaneHovered = false
        scroller?.stop()
    }
}
