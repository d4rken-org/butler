package eu.darken.butler.workspace.ui.modal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * Dismisses a popup-rendered menu when its pane stops being the focused one.
 *
 * A [androidx.compose.material3.DropdownMenu] renders in its own window, outside the pane's
 * pointer boundary: [PaneLayerHost] can neither swallow the press that would activate an item in
 * an unfocused pane nor focus the pane for it. Closing the menu when the pane loses focus is what
 * upholds the pane contract — the first click into an unfocused pane focuses it and does nothing
 * else. Outside a pane ([LocalPaneFocused] defaults to true) this never triggers.
 *
 * Call it next to the menu, keyed on the same expanded state the menu renders from.
 */
@Composable
fun DismissWhenPaneUnfocused(expanded: Boolean, onDismiss: () -> Unit) {
    val paneFocused = LocalPaneFocused.current
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    LaunchedEffect(expanded, paneFocused) {
        if (expanded && !paneFocused) currentOnDismiss.value.invoke()
    }
}
