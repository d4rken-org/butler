package eu.darken.butler.workspace.ui.modal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
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
 * Observes on the initial pass and never consumes, so buttons and text fields inside keep working.
 */
@Composable
fun Modifier.requestPaneFocusOnPress(): Modifier {
    val requestFocus = LocalWorkspaceFocusRequest.current ?: return this
    val focusManager = LocalFocusManager.current
    val paneFocused = rememberUpdatedState(LocalPaneFocused.current)
    return this.pointerInput(requestFocus) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!paneFocused.value) {
                // The pane-focus request only resolves a round trip later, but whatever was pressed
                // asks for keyboard focus on the *up* event. Release the old pane's focus now, or
                // that request loses the race against a modal that is still active over there.
                // Only for a press arriving in a pane that isn't the active one — clearing on every
                // press would take focus away from a dialog the user is typing in.
                focusManager.clearFocus(force = true)
            }
            requestFocus()
        }
    }
}
