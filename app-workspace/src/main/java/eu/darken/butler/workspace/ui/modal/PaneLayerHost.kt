package eu.darken.butler.workspace.ui.modal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Owns the modal layer stack of a single workspace pane.
 *
 * Must sit at the level that encloses *both* the pane's parent workspace and any pane-local child
 * modal, otherwise it cannot arbitrate between them.
 *
 * Any press anywhere in the pane makes it the focused one. This has to sit here rather than on the
 * individual modal surfaces: a press that a text field or a list row consumes never reaches a click
 * handler further up, so a pane whose content swallowed the touch would never become focused — and
 * a modal in the previously focused pane would keep its focus trap armed forever, leaving keyboard
 * focus unable to move between panes at all.
 *
 * Callers must provide [LocalWorkspaceFocusRequest] *above* this composable for that to work.
 *
 * @param paneFocused whether this pane is the focused one. Accepts either occupant of the pane —
 *        the parent workspace or its pane-local child modal — because a child modal's id can never
 *        become the globally focused workspace id.
 */
@Composable
fun PaneLayerHost(
    modifier: Modifier = Modifier,
    paneFocused: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val layerState = remember { PaneLayerState() }

    CompositionLocalProvider(
        LocalPaneLayerState provides layerState,
        LocalPaneFocused provides paneFocused,
        LocalLayerActive provides paneFocused,
        LocalPaneLayerRank provides PaneLayerRank.CONTENT,
    ) {
        Box(
            modifier = modifier.requestPaneFocusOnPress(),
            content = content,
        )
    }
}
