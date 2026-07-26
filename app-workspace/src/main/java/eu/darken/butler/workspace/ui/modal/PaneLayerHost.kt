package eu.darken.butler.workspace.ui.modal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import eu.darken.butler.workspace.ui.insets.LocalPaneEdges
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges

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
 * Callers must provide [eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest] *above* this
 * composable for that to work.
 *
 * The host itself always spans the full pane and must never be inset — its layers carry the pane's
 * scrims and pointer barriers. [paneEdges] is published to the subtree instead, so the content and
 * the modal surfaces inside can pad themselves.
 *
 * @param paneFocused whether this pane is the focused one. Accepts either occupant of the pane —
 *        the parent workspace or its pane-local child modal — because a child modal's id can never
 *        become the globally focused workspace id.
 */
@Composable
fun PaneLayerHost(
    modifier: Modifier = Modifier,
    paneFocused: Boolean,
    paneEdges: PaneEdges = LocalPaneEdges.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val layerState = remember { PaneLayerState() }

    CompositionLocalProvider(
        LocalPaneLayerState provides layerState,
        LocalPaneFocused provides paneFocused,
        LocalLayerActive provides paneFocused,
        LocalPaneLayerRank provides PaneLayerRank.CONTENT,
        LocalPaneEdges provides paneEdges,
    ) {
        Box(
            modifier = modifier.requestPaneFocusOnPress(),
            content = content,
        )
    }
}
