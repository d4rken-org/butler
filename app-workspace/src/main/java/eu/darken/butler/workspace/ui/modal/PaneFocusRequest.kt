package eu.darken.butler.workspace.ui.modal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest

/**
 * Makes any press within this element focus the enclosing pane.
 *
 * Modal surfaces — scrims, dialog cards, sheet cards — swallow pointer input so it never reaches
 * the pane's own click handler. Without this, tapping an open dialog in one pane leaves a different
 * pane focused, and the next back press goes to that other pane.
 *
 * Observes on the initial pass, so presses the content consumes are still seen. With
 * [consumeWhenUnfocused] unset it never consumes itself, so buttons and text fields inside keep
 * working.
 *
 * @param consumeWhenUnfocused consume the down event of every press arriving while the pane is not
 *        the focused one. Applied on an ancestor of the pane content, the down reaches the
 *        content's tap detectors already claimed, so a press into an unfocused pane only focuses
 *        the pane instead of also activating whatever sits under the finger. Presses stay consumed
 *        until the focus request has round-tripped through the workspace screen and come back as
 *        pane focus — a request that is never honoured leaves the pane tap-inert rather than
 *        misclick-prone. Drags are unaffected: scroll and drag detectors accept a consumed down, so
 *        an unfocused pane can still be scrolled. Enabled only at the pane boundary
 *        ([PaneLayerHost]); the per-surface applications on dialogs and sheets stay pure observers,
 *        the host instance above them already handles the swallow. Pointer input only, by design:
 *        accessibility activation (a semantics click from TalkBack or switch access) invokes the
 *        content's action directly and is not swallowed — assistive tech states its target
 *        explicitly, so the misclick this guards against cannot happen there.
 * @param onPressSwallowed invoked once per down that is actually consumed, with that pointer's
 *        position in this element's coordinate space. Fires only in the [consumeWhenUnfocused] case
 *        — exactly the presses that produce no feedback of their own, since the content's tap
 *        detectors never start.
 */
@Composable
fun Modifier.requestPaneFocusOnPress(
    consumeWhenUnfocused: Boolean = false,
    onPressSwallowed: ((Offset) -> Unit)? = null,
): Modifier {
    val requestFocus = LocalWorkspaceFocusRequest.current ?: return this
    val focusManager = LocalFocusManager.current
    val paneFocused = rememberUpdatedState(LocalPaneFocused.current)
    // Read when a press arrives instead of keying the handler on it: a changed lambda identity
    // would restart the event loop mid-gesture.
    val currentRequestFocus = rememberUpdatedState(requestFocus)
    val currentOnPressSwallowed = rememberUpdatedState(onPressSwallowed)
    return this.pointerInput(consumeWhenUnfocused) {
        awaitPointerEventScope {
            // A raw event loop over every new down instead of one first-down per gesture: while a
            // finger already rests on the pane, a second finger's down belongs to the same gesture
            // but hits its own target — it must be seen (and consumed) individually, or it slips
            // past the swallow below.
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val newDowns = event.changes.filter { it.changedToDownIgnoreConsumed() }
                if (newDowns.isEmpty()) continue
                if (!paneFocused.value) {
                    // The pane-focus request only resolves a round trip later, but whatever was
                    // pressed asks for keyboard focus on the *up* event. Release the old pane's
                    // focus now, or that request loses the race against a modal that is still
                    // active over there. Only for a press arriving in a pane that isn't the active
                    // one — clearing on every press would take focus away from a dialog the user
                    // is typing in.
                    focusManager.clearFocus(force = true)
                    if (consumeWhenUnfocused) {
                        newDowns.forEach {
                            it.consume()
                            currentOnPressSwallowed.value?.invoke(it.position)
                        }
                    }
                }
                currentRequestFocus.value.invoke()
            }
        }
    }
}

/**
 * Reports whether a non-touch pointer (mouse, stylus, trackball) is currently hovering this
 * element with nothing pressed.
 *
 * Observes on the initial pass and never consumes, exactly like [requestPaneFocusOnPress]: the
 * hover of an unfocused pane is watched by an ancestor of content that must keep receiving every
 * event unchanged.
 *
 * A touch pointer never counts as hovering, and its appearance ends an ongoing hover right away.
 * Touch has no cursor to give feedback to, so anything keyed on this stays out of the way of
 * finger input entirely.
 *
 * @param enabled while false nothing is observed and the last reported value is reset to false —
 *        a hover that ended while tracking was off must not come back stale when it resumes.
 */
@Composable
fun Modifier.trackNonTouchHover(
    enabled: Boolean,
    onHoveringChanged: (Boolean) -> Unit,
): Modifier {
    val currentOnHoveringChanged = rememberUpdatedState(onHoveringChanged)
    if (!enabled) return this
    DisposableEffect(Unit) {
        onDispose { currentOnHoveringChanged.value.invoke(false) }
    }
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            var hovering = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val hoveringNow = when {
                    event.changes.any { it.type == PointerType.Touch } -> false
                    event.type == PointerEventType.Exit -> false
                    event.type == PointerEventType.Enter || event.type == PointerEventType.Move -> {
                        // A pressed pointer is dragging, not hovering: the press has its own
                        // handling and must not raise a barrier mid-gesture.
                        event.changes.none { it.pressed }
                    }
                    else -> hovering
                }
                if (hoveringNow == hovering) continue
                hovering = hoveringNow
                currentOnHoveringChanged.value.invoke(hoveringNow)
            }
        }
    }
}
