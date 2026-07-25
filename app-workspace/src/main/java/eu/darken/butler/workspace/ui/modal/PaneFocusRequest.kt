package eu.darken.butler.workspace.ui.modal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
    return this.pointerInput(requestFocus) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            requestFocus()
        }
    }
}
