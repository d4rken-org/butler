package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.EmphasizedAccelerate
import eu.darken.butler.common.compose.EmphasizedDecelerate
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.circularReveal
import eu.darken.butler.workspace.ui.manager.WorkspaceRevealOrigin

/**
 * Transition state of the tab manager overlay, hoisted out of [ManagerRevealOverlay] because the
 * back handler and the pane focus suppression have to follow [layerPresent] too.
 */
@Stable
internal class ManagerRevealState internal constructor(
    /** Composition lifetime of the overlay: true from the frame `visible` flips true until the exit settles. */
    val layerPresent: Boolean,
    internal val progress: State<Float>,
    internal val contentScale: State<Float>,
    private val settled: State<Boolean>,
) {
    val revealSettled: Boolean get() = settled.value
}

@Composable
internal fun rememberManagerRevealState(visible: Boolean): ManagerRevealState {
    // `currentState` is seeded from the initial `visible`, so a process restore with the manager
    // already open snaps instead of replaying the reveal.
    val transition = updateTransition(targetState = visible, label = "ManagerReveal")
    // Kept as the State object rather than `by`-delegated: the layer blocks below read it in the
    // draw phase, so nothing recomposes per frame.
    val progress = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(320, easing = EmphasizedDecelerate)
            } else {
                tween(220, easing = EmphasizedAccelerate)
            }
        },
        label = "reveal",
    ) { if (it) 1f else 0f }
    val contentScale = transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow)
            } else {
                tween(220, easing = EmphasizedAccelerate)
            }
        },
        label = "revealScale",
    ) { if (it) 1f else 0.85f }
    // Derived, not a plain getter: a reader in composition wakes up when the boolean flips instead
    // of on every animation frame.
    val settled = remember(progress) { derivedStateOf { progress.value >= 1f } }
    return ManagerRevealState(
        layerPresent = transition.currentState || transition.targetState,
        progress = progress,
        contentScale = contentScale,
        settled = settled,
    )
}

/**
 * Grows [content] out of the button recorded in [revealOrigin] and shrinks it back into it.
 */
@Composable
internal fun ManagerRevealOverlay(
    modifier: Modifier = Modifier,
    state: ManagerRevealState,
    revealOrigin: WorkspaceRevealOrigin,
    content: @Composable () -> Unit,
) {
    if (!state.layerPresent) return

    var boxOriginInRoot by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            // The origin is recorded in root coordinates while the clip shape is built in this
            // Box's own ones, so subtract wherever the Box landed.
            .onGloballyPositioned { boxOriginInRoot = it.positionInRoot() }
            // Before the scaling layer on purpose: the clip stays the outer layer, so the circle
            // keeps its anchor in unscaled coordinates while the content scales inside it.
            .circularReveal(
                progress = { state.progress.value },
                origin = { revealOrigin.offset?.minus(boxOriginInRoot) },
            )
            // The manager is laid out and hit-testable from progress 0, so without this a tap lands
            // on a card the clip has not uncovered yet, and a tap during the exit acts on an
            // overlay the user already dismissed. The Initial pass is required: Compose dispatches
            // it parent-to-child, while a barrier on the default Main pass would only see a card's
            // down/up after that card's `clickable` had already fired.
            .then(
                if (state.revealSettled) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        }
                    }
                },
            )
            .graphicsLayer {
                alpha = (state.progress.value / 0.4f).coerceIn(0f, 1f)
                scaleX = state.contentScale.value
                scaleY = state.contentScale.value
                val local = revealOrigin.offset?.minus(boxOriginInRoot)
                transformOrigin = if (local != null && size.width > 0f && size.height > 0f) {
                    TransformOrigin(
                        pivotFractionX = (local.x / size.width).coerceIn(0f, 1f),
                        pivotFractionY = (local.y / size.height).coerceIn(0f, 1f),
                    )
                } else {
                    TransformOrigin.Center
                }
            },
    ) {
        content()
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ManagerRevealOverlayPreview() {
    ManagerRevealOverlay(
        state = rememberManagerRevealState(visible = true),
        revealOrigin = remember { WorkspaceRevealOrigin() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
