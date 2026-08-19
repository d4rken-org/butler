package eu.darken.butler.workspace.ui.modal

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode
import eu.darken.butler.common.compose.LocalTooltipsEnabled
import eu.darken.butler.common.ui.dialogs.LocalAlertDialogRenderer
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogRenderer
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
 * focus unable to move between panes at all. Presses arriving while the pane is not the focused one
 * are additionally consumed, so the first click into an unfocused pane focuses it without
 * activating the content under the finger. Such a press gets no feedback from the content it never
 * reached, so the host answers it itself with a [PaneFocusPulseOverlay] at the tap point.
 *
 * Callers must provide [eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest] *above* this
 * composable for that to work.
 *
 * While the pane is not the focused one its interaction feedback is switched off: ripples and their
 * hover, press and focus state layers ([LocalRippleConfiguration] `null` covers every Material
 * component, [LocalIndication] the plain `clickable`s), plus tooltips. A pointer hovering an
 * unfocused pane would otherwise light rows and buttons up as if a click would activate them, while
 * the boundary above swallows exactly that click.
 *
 * Only the affordances are suppressed, never the input itself: scrolling, dragging and drop-target
 * highlights keep working in an unfocused pane, matching every desktop convention for inactive
 * windows and preserving the scrollability the swallow deliberately left intact. That choice bounds
 * what can be suppressed — Material's hover *elevation* (filled buttons level 0 to 1, elevated
 * cards level 1 to 2) is animated per component from its own interaction source, with no ambient
 * switch, so a cursor still lifts those surfaces slightly. Removing that too would mean taking the
 * pane out of hit testing, which is what keeps scrolling alive.
 *
 * The host itself always spans the full pane and must never be inset — its layers carry the pane's
 * scrims and pointer barriers. [paneEdges] is published to the subtree instead, so the content and
 * the modal surfaces inside can pad themselves.
 *
 * @param paneFocused whether this pane is the focused one. Accepts either occupant of the pane —
 *        the parent workspace or its pane-local child modal: a child modal CAN hold the global focus
 *        (`createAndFocus` and a tab-manager selection both put it there), so the caller resolves
 *        focus through the owning tab rather than comparing this pane's own id.
 * @param backActive whether system Back may be dispatched to this pane. Separate from [paneFocused]
 *        because a pane can be the focused one while not being on screen — the classic pager parks
 *        on its trailing placeholder page without focus leaving the last tab. Defaults to
 *        [paneFocused], so a layout that cannot park off its panes needs no extra wiring.
 */
@Composable
fun PaneLayerHost(
    modifier: Modifier = Modifier,
    paneFocused: Boolean,
    backActive: Boolean = paneFocused,
    paneEdges: PaneEdges = LocalPaneEdges.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val layerState = remember { PaneLayerState() }
    val pulseState = remember { PaneFocusPulseState() }

    CompositionLocalProvider(
        LocalPaneLayerState provides layerState,
        LocalPaneFocused provides paneFocused,
        LocalLayerActive provides paneFocused,
        // Provided only here and inherited unchanged through every PaneLayer: PaneLayer recomputes
        // and overrides LocalLayerActive for its subtree, so a value narrowed there would be
        // discarded by every nested dialog and sheet.
        LocalPaneBackActive provides backActive,
        LocalPaneLayerRank provides PaneLayerRank.CONTENT,
        LocalPaneEdges provides paneEdges,
        // Shared dialogs from app-common (ErrorDialog above all) become pane-bound inside a pane
        LocalAlertDialogRenderer provides PaneBoundAlertDialogRenderer,
        // Reading `.current` keeps the focused case on whatever the theme provides, instead of
        // pinning it to a default this file would have to keep in sync.
        LocalIndication provides if (paneFocused) LocalIndication.current else NoIndication,
        LocalRippleConfiguration provides if (paneFocused) LocalRippleConfiguration.current else null,
        // Narrowing, never widening: an outer provider that already muted tooltips keeps its say.
        LocalTooltipsEnabled provides (paneFocused && LocalTooltipsEnabled.current),
    ) {
        Box(
            // The pane boundary is also where the first press into an unfocused pane is swallowed:
            // it focuses the pane without activating the content under the finger.
            modifier = modifier.requestPaneFocusOnPress(
                consumeWhenUnfocused = true,
                onPressSwallowed = { pulseState.emit(it) },
            ),
        ) {
            content()
            // Last child, so the pulse draws above the pane content and its modal layers — the
            // swallowed press has to be answered where the finger is.
            PaneFocusPulseOverlay(
                modifier = Modifier.matchParentSize(),
                state = pulseState,
            )
        }
    }
}

/**
 * [Indication] that renders nothing, used to strip hover and press feedback from an unfocused pane.
 *
 * Covers the `clickable`s that take their indication from [LocalIndication]; Material components
 * pass `ripple()` explicitly and are switched off through [LocalRippleConfiguration] instead.
 */
private object NoIndication : IndicationNodeFactory {

    /** No draw, no layout, no pointer input — it only exists so the factory can return a node. */
    private class Node : Modifier.Node()

    override fun create(interactionSource: InteractionSource): DelegatableNode = Node()

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = System.identityHashCode(this)
}
