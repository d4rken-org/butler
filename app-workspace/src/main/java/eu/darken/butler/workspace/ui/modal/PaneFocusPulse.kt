package eu.darken.butler.workspace.ui.modal

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper

internal const val TAG_PANE_FOCUS_PULSE = "pane:focusPulse"

private val PulseStartRadius = 24.dp
private val PulseEndRadius = 96.dp
private const val PulsePeakAlpha = 0.3f
private const val PulseDurationMs = 420

/** Radius of a pulse at [progress] 0..1. */
internal fun pulseRadius(progress: Float): Dp =
    PulseStartRadius + (PulseEndRadius - PulseStartRadius) * progress

/** Opacity of a pulse at [progress] 0..1; fully faded out when it reaches its final radius. */
internal fun pulseAlpha(progress: Float): Float = PulsePeakAlpha * (1f - progress)

/** The pulses currently running in one pane; each one is removed once its animation ends. */
@Stable
internal class PaneFocusPulseState {

    data class Pulse(val id: Long, val position: Offset)

    private var nextId = 0L

    val pulses = mutableStateListOf<Pulse>()

    fun emit(position: Offset) {
        pulses.add(Pulse(id = nextId++, position = position))
    }

    fun remove(pulse: Pulse) {
        pulses.remove(pulse)
    }
}

/**
 * Feedback for presses the pane boundary swallows: a circle expanding out of the tap point while it
 * fades.
 *
 * A swallowed press produces nothing where the finger is — the content's tap detectors never start,
 * so there is no ripple, and the only sign anything happened is the focus border at the pane edge.
 * This draws the missing answer at the place the user is looking.
 *
 * Must be rendered as the last child of the pane host, not as a draw modifier on it: a draw modifier
 * paints below the pane content and the pulse would be invisible under it.
 *
 * Purely decorative — no pointer input, no semantics beyond the test tag, so it changes neither hit
 * testing nor accessibility traversal. With the system animator duration scale at 0 the animation
 * completes instantly and the pulse is effectively suppressed; the focus border, which switches
 * without animation, remains as feedback.
 */
@Composable
internal fun PaneFocusPulseOverlay(
    modifier: Modifier = Modifier,
    state: PaneFocusPulseState,
) {
    if (state.pulses.isEmpty()) return
    val color = MaterialTheme.colorScheme.primary

    Box(modifier = modifier.testTag(TAG_PANE_FOCUS_PULSE)) {
        state.pulses.forEach { pulse ->
            // The whole subtree is keyed: a finished pulse leaving the list must not hand its
            // animation state to the pulse that takes its slot.
            key(pulse.id) {
                val progress = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(
                            durationMillis = PulseDurationMs,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                    state.remove(pulse)
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .drawBehind {
                            drawCircle(
                                color = color,
                                radius = pulseRadius(progress.value).toPx(),
                                center = pulse.position,
                                alpha = pulseAlpha(progress.value),
                            )
                        },
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun PaneFocusPulseOverlayPreview() {
    PreviewWrapper {
        val state = remember {
            PaneFocusPulseState().apply { emit(Offset(200f, 200f)) }
        }
        PaneFocusPulseOverlay(
            modifier = Modifier.size(400.dp),
            state = state,
        )
    }
}
