package eu.darken.butler.viewer.ui.viewer

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.files.APath
import eu.darken.butler.viewer.core.ViewerContent
import eu.darken.butler.viewer.core.ViewerSource
import eu.darken.butler.workspace.ui.insets.LocalPaneEdges
import eu.darken.butler.workspace.ui.insets.backGestureStripWidths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.time.Duration.Companion.seconds

/** Test tag of the box that carries the content's swipe offset. */
internal const val VIEWER_SWIPE_CONTENT_TAG = "viewer-swipe-content"

/** A slow release steps once the drag has covered this fraction of the content's width. */
private const val COMMIT_DISTANCE_FRACTION = 0.3f

/** A release at least this fast, per second, steps toward where it was flung. */
private val COMMIT_FLING_VELOCITY = 400.dp

/** How much of the finger's travel the content follows toward a side with no file to step to. */
private const val RUBBER_BAND_FACTOR = 0.2f

/** How long a committed step may take to show its file before the content returns regardless. */
internal val SWIPE_STEP_ARRIVAL_TIMEOUT = 2.seconds

private val swipeStepMotion = spring<Float>(stiffness = Spring.StiffnessMediumLow)

/**
 * Where the viewer's content sits while a swipe steps through the listing, and the step itself.
 *
 * A step is dispatched once, from the release, and then followed by identity: the content leaves,
 * stays out until the file it stepped to is on display and past [ViewerContent.Loading], then comes
 * in from the other side. Any other file turning up instead, or [SWIPE_STEP_ARRIVAL_TIMEOUT]
 * passing, brings the content back to rest, and nothing that arrives after that animates again.
 */
@Stable
internal class ViewerSwipeStepState(private val scope: CoroutineScope) {

    /** What the gesture needs to know about the page, refreshed on every composition. */
    internal data class Inputs(
        /** Null for streamed content, which has no listing to step through. */
        val source: APath<*>?,
        val isLoading: Boolean,
        /** What is on screen; a drag is dropped when this changes under the finger. */
        val surface: Any?,
        val isZoomed: Boolean,
        val neighbours: ViewerNeighbours?,
        val actions: List<ViewerActionBarItem>,
        val layoutDirection: LayoutDirection,
    )

    /** One drag that moves the content. [base] is where a drag caught mid-spring picked it up. */
    internal class Drag(val origin: APath<*>, val surface: Any?, val base: Float) {
        var travel = 0f
    }

    /** Horizontal placement of the content, in pixels. */
    var offsetX by mutableFloatStateOf(0f)
        private set

    internal var edgeStripsPx: Pair<Int, Int> = 0 to 0
        private set

    /** From the release of a committed swipe until the content is back at rest. */
    var isStepping by mutableStateOf(false)
        private set

    private var phase = Phase.IDLE
        set(value) {
            field = value
            isStepping = value == Phase.STEPPING
        }

    private var inputs by mutableStateOf<Inputs?>(null)
    private var onAction: (ViewerActionBarItem) -> Unit = {}
    private var motion: Job? = null
    private var drag: Drag? = null

    internal fun update(inputs: Inputs, edgeStripsPx: Pair<Int, Int>, onAction: (ViewerActionBarItem) -> Unit) {
        this.inputs = inputs
        this.edgeStripsPx = edgeStripsPx
        this.onAction = onAction
        val active = drag ?: return
        if (inputs.surface != active.surface) cancel(active)
    }

    /** Null when the drag is only swallowed: during a step, or on a zoomed picture at its pan edge. */
    internal fun claim(): Drag? {
        val now = inputs ?: return null
        if (phase == Phase.STEPPING || now.isZoomed) return null
        val origin = now.source ?: return null
        // A spring-back still running is picked up where it is, so a quick second swipe does not jump.
        motion?.cancel()
        val base = if (now.resists(offsetX)) offsetX / RUBBER_BAND_FACTOR else offsetX
        phase = Phase.DRAGGING
        return Drag(origin = origin, surface = now.surface, base = base).also { drag = it }
    }

    internal fun dragTo(drag: Drag, travel: Float) {
        if (this.drag !== drag) return
        drag.travel = travel
        val raw = drag.base + travel
        offsetX = if (inputs?.resists(raw) == false) raw else raw * RUBBER_BAND_FACTOR
    }

    internal fun release(drag: Drag, velocityX: Float, width: Int, flingVelocityPx: Float) {
        if (this.drag !== drag) return
        this.drag = null
        val raw = drag.base + drag.travel
        val toward = when {
            abs(velocityX) >= flingVelocityPx -> sign(velocityX)
            abs(raw) >= width * COMMIT_DISTANCE_FRACTION -> sign(raw)
            else -> 0f
        }
        val now = inputs
        if (toward == 0f || sign(raw) != toward || now == null) {
            settle(velocityX)
            return
        }
        val next = now.isNext(toward)
        val target = now.neighbour(next)
        if (target == null || !now.canStep(next)) {
            settle(velocityX)
            return
        }
        step(origin = drag.origin, next = next, target = target, exitTo = toward * width, velocityX = velocityX)
    }

    internal fun cancel(drag: Drag) {
        if (this.drag !== drag) return
        this.drag = null
        settle(0f)
    }

    private fun settle(velocityX: Float) {
        phase = Phase.SETTLING
        motion = scope.launch {
            animateOffset(to = 0f, velocityX = velocityX)
            phase = Phase.IDLE
        }
    }

    private fun step(origin: APath<*>, next: Boolean, target: APath<*>, exitTo: Float, velocityX: Float) {
        phase = Phase.STEPPING
        motion = scope.launch {
            val now = inputs
            val action = now?.stepAction(next)
            if (now == null || now.source != origin || now.neighbour(next) != target || action == null) {
                animateOffset(to = 0f, velocityX = velocityX)
                phase = Phase.IDLE
                return@launch
            }
            onAction(action)
            val slidIn = coroutineScope {
                val exit = launch { animateOffset(to = exitTo, velocityX = velocityX) }
                val arrival = withTimeoutOrNull(SWIPE_STEP_ARRIVAL_TIMEOUT) {
                    snapshotFlow { inputs?.arrivalOf(origin, target) ?: Arrival.PENDING }
                        .first { it != Arrival.PENDING }
                } ?: Arrival.LOST
                if (arrival != Arrival.ARRIVED) {
                    exit.cancel()
                    return@coroutineScope false
                }
                val entry = launch {
                    exit.join()
                    offsetX = -exitTo
                    animateOffset(to = 0f, velocityX = 0f)
                }
                // Another file landing before the content is back at rest ends the transition too.
                val replaced = async { snapshotFlow { inputs?.source }.first { it != target } }
                select<Boolean> {
                    entry.onJoin { true }
                    replaced.onAwait { false }
                }.also {
                    exit.cancel()
                    entry.cancel()
                    replaced.cancel()
                }
            }
            if (!slidIn) animateOffset(to = 0f, velocityX = 0f)
            phase = Phase.IDLE
        }
    }

    private suspend fun animateOffset(to: Float, velocityX: Float) {
        animate(
            initialValue = offsetX,
            targetValue = to,
            initialVelocity = velocityX,
            animationSpec = swipeStepMotion,
        ) { value, _ -> offsetX = value }
    }
}

private enum class Phase { IDLE, DRAGGING, SETTLING, STEPPING }

private enum class Arrival { PENDING, ARRIVED, LOST }

/** Like [androidx.compose.foundation.pager.HorizontalPager]: the start side holds the previous file. */
private fun ViewerSwipeStepState.Inputs.isNext(offset: Float): Boolean =
    (offset < 0f) == (layoutDirection == LayoutDirection.Ltr)

private fun ViewerSwipeStepState.Inputs.neighbour(next: Boolean): APath<*>? =
    if (next) neighbours?.next else neighbours?.previous

private fun ViewerSwipeStepState.Inputs.stepAction(next: Boolean): ViewerActionBarItem? = actions.firstOrNull {
    it.isEnabled && if (next) it is ViewerActionBarItem.NextFile else it is ViewerActionBarItem.PreviousFile
}

private fun ViewerSwipeStepState.Inputs.canStep(next: Boolean): Boolean =
    neighbour(next) != null && stepAction(next) != null

private fun ViewerSwipeStepState.Inputs.resists(offset: Float): Boolean = offset != 0f && !canStep(isNext(offset))

private fun ViewerSwipeStepState.Inputs.arrivalOf(origin: APath<*>, target: APath<*>): Arrival = when {
    source == target && !isLoading -> Arrival.ARRIVED
    source == origin || source == target -> Arrival.PENDING
    else -> Arrival.LOST
}

/**
 * Telephoto keeps its transformation after the zoomable surface leaves, so a zoom only counts while
 * one is on screen. A spinner, an error or a text file that inherited it would refuse every swipe.
 */
internal fun showsZoomableSurface(state: ViewerWorkspaceViewModel.State.Ready): Boolean = when (state.content) {
    is ViewerContent.Image -> state.imageSource != null
    is ViewerContent.PdfPreview -> state.pdfPage?.bitmap != null
    else -> false
}

/** [edgeStripsPx] overrides the window's back-gesture strips; tests need it, Robolectric reports none. */
@Composable
internal fun rememberViewerSwipeStep(
    state: ViewerWorkspaceViewModel.State.Ready,
    zoomedIn: Boolean,
    edgeStripsPx: Pair<Int, Int>?,
    onAction: (ViewerActionBarItem) -> Unit,
): ViewerSwipeStepState {
    val scope = rememberCoroutineScope()
    val swipeStep = remember(scope) { ViewerSwipeStepState(scope) }
    val strips = edgeStripsPx ?: LocalPaneEdges.current.backGestureStripWidths()
    val inputs = ViewerSwipeStepState.Inputs(
        source = (state.source as? ViewerSource.Stored)?.path,
        isLoading = state.content is ViewerContent.Loading,
        surface = state.source to when (val content = state.content) {
            is ViewerContent.Image -> state.imageSource
            is ViewerContent.PdfPreview -> state.pdfPage?.bitmap
            else -> content::class
        },
        isZoomed = zoomedIn && showsZoomableSurface(state),
        neighbours = state.neighbours,
        actions = state.actions,
        layoutDirection = LocalLayoutDirection.current,
    )
    SideEffect { swipeStep.update(inputs, strips, onAction) }
    return swipeStep
}

/** The stationary layer around the content: it reads the gesture and clips what slides out of it. */
internal fun Modifier.viewerSwipeStep(state: ViewerSwipeStepState, active: Boolean): Modifier = this
    // Drawing only, and only while moved: content sliding out must not draw over a neighbouring pane.
    .drawWithContent {
        if (state.offsetX == 0f) drawContent() else clipRect { this@drawWithContent.drawContent() }
    }
    .then(if (active) Modifier.pointerInput(state) { detectSwipeStep(state) } else Modifier)

/** The moving content inside [viewerSwipeStep]. A placement offset, so its bounds move with it. */
internal fun Modifier.viewerSwipeStepOffset(state: ViewerSwipeStepState): Modifier = this
    .absoluteOffset { IntOffset(state.offsetX.roundToInt(), 0) }
    .testTag(VIEWER_SWIPE_CONTENT_TAG)

/**
 * The pointer passes are split on purpose:
 * - The down is read on Initial, which runs parent-first. A down consumed there was taken by an
 *   ancestor (the pane's click-to-focus gate) and starts nothing. A descendant consuming it later
 *   does not count: the picture's and the placeholders' tap detectors take every down.
 * - Moves are read on Main, which runs child-first, so a zoomed picture's pan, an overflowing line's
 *   scroller and a list's vertical scroll see each move first and win it by consuming it.
 * - The claim needs only the horizontal distance to reach the touch slop, whatever the vertical one:
 *   the tab pager claims on that same rule, so anything stricter would hand steep diagonal drags to
 *   it. From the claim until the last finger lifts every change is consumed, even after a second
 *   finger abandons the drag: a move reaching the pager unconsumed lets it take the gesture over
 *   (pinned by `ViewerSwipeStepTest`).
 *
 * On a zoomed picture, a drag telephoto refuses because it is at its pan edge is still claimed, and
 * swallowed: nothing moves and nothing steps. Telephoto gives up on a gesture whose move was consumed
 * before it crossed its own slop, so reversing that drag does not pan until the finger lifts. Leaving
 * the drag unclaimed instead would hand it to the pager, which claims at the same slop.
 */
private suspend fun PointerInputScope.detectSwipeStep(state: ViewerSwipeStepState) {
    val flingVelocityPx = COMMIT_FLING_VELOCITY.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.isConsumed) return@awaitEachGesture
        val (leftStrip, rightStrip) = state.edgeStripsPx
        if (down.position.x < leftStrip || down.position.x >= size.width - rightStrip) return@awaitEachGesture

        val slop = viewConfiguration.touchSlop
        val velocity = VelocityTracker().apply { addPointerInputChange(down) }
        var claimed = false
        var travelOffset = 0f
        var drag: ViewerSwipeStepState.Drag? = null
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id }
                val secondPointer = event.changes.any { it.id != down.id && it.pressed }
                if (!claimed) {
                    // A second finger before the claim makes a pinch, which is the zoom's.
                    if (change == null || secondPointer) return@awaitEachGesture
                    // The down itself, now on its Main pass.
                    if (change.changedToDownIgnoreConsumed()) continue
                    if (!change.pressed || change.isConsumed) return@awaitEachGesture
                    velocity.addPointerInputChange(change)
                    val dx = change.position.x - down.position.x
                    if (abs(dx) < slop) continue
                    claimed = true
                    travelOffset = -sign(dx) * slop
                    drag = state.claim()
                } else if (change != null) {
                    velocity.addPointerInputChange(change)
                }
                event.changes.forEach { it.consume() }

                val active = drag
                // A second finger after the claim abandons the drag, and the content springs back.
                if (active != null && (change == null || secondPointer)) {
                    state.cancel(active)
                    drag = null
                }
                if (event.changes.none { it.pressed }) {
                    val released = drag
                    if (released != null) {
                        val velocityX = velocity.calculateVelocity().x.takeIf { it.isFinite() } ?: 0f
                        state.release(released, velocityX, size.width, flingVelocityPx)
                    }
                    return@awaitEachGesture
                }
                val dragging = drag
                if (dragging != null && change != null) {
                    state.dragTo(dragging, travelOffset + change.position.x - down.position.x)
                }
            }
        } finally {
            drag?.let { state.cancel(it) }
        }
    }
}
