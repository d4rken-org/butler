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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.testTag
import eu.darken.butler.common.compose.LocalTooltipsEnabled
import eu.darken.butler.common.ui.dialogs.LocalAlertDialogRenderer
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogRenderer
import eu.darken.butler.workspace.ui.insets.LocalPaneEdges
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign.PaneEdges

internal const val TAG_PANE_HOVER_BARRIER = "pane:hoverBarrier"

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
 * With [clickToFocus] on (the user's setting, on by default) an unfocused pane answers nothing
 * until it has been focused:
 * - Presses arriving while the pane is not the focused one are consumed, so the first click into it
 *   focuses the pane without activating the content under the finger. Such a press gets no feedback
 *   from the content it never reached, so the host answers it itself with a [PaneFocusPulseOverlay]
 *   at the tap point.
 * - The pane's interaction feedback is switched off: ripples and their hover, press and focus state
 *   layers ([LocalRippleConfiguration] `null` covers every Material component, [LocalIndication] the
 *   plain `clickable`s), plus tooltips. A pointer hovering an unfocused pane would otherwise light
 *   rows and buttons up as if a click would activate them, while the boundary above swallows exactly
 *   that click.
 * - While a non-touch pointer hovers, a barrier covers the pane and takes its content out of hit
 *   testing. Hover feedback that no ambient switch reaches — Material's per-component hover
 *   elevation, the pointer icon a text field sets — only stops when the hover stops arriving, and
 *   Compose derives hover enter/exit from hit-test results. The barrier exists solely while a
 *   cursor is inside: touch never raises it.
 *   The accepted cost of occluding by hit test: a gesture that *begins* while the barrier is up
 *   never reaches the content, since Compose resolves the hit path at the down and keeps it for the
 *   rest of the gesture. Wheel scrolling of an unfocused pane is therefore blocked for as long as
 *   the barrier is up, on any device with a pointer — a scroll neither lowers the barrier nor gets
 *   past it, so only moving the cursor out of the pane or the pane gaining focus restores it. This
 *   one does not clear itself. Where a cursor and a touchscreen are both in use, a finger swipe that
 *   begins under a resting cursor is lost too — but that first touch lowers the barrier, so the next
 *   swipe scrolls, until a fresh cursor move raises it again.
 *   Taps still focus the pane through the press observer above, and drag-and-drop is unaffected.
 *
 * With [clickToFocus] off none of that applies: an unfocused pane is directly interactive, clicks
 * land where they are aimed and pane focus follows them.
 *
 * The host itself always spans the full pane and must never be inset — its layers carry the pane's
 * scrims and pointer barriers. [paneEdges] is published to the subtree instead, so the content and
 * the modal surfaces inside can pad themselves.
 *
 * @param clickToFocus whether an unfocused pane stays inert until it is clicked once, per the user's
 *        "Click to focus" setting. Off makes an unfocused pane directly interactive.
 * @param paneFocused whether this pane is the focused one. Accepts either occupant of the pane —
 *        the parent workspace or its pane-local child modal: a child modal CAN hold the global focus
 *        (`createAndFocus` and a tab-manager selection both put it there), so the caller resolves
 *        focus through the owning tab rather than comparing this pane's own id.
 * @param backActive whether system Back may be dispatched to this pane. Separate from [paneFocused]
 *        because a pane can be the focused one while not being on screen — the classic pager parks
 *        on its trailing placeholder page without focus leaving the last tab. Defaults to
 *        [paneFocused], so a layout that cannot park off its panes needs no extra wiring.
 * @param allowPresses whether presses arriving in this pane may act, read at event time. Published
 *        as [LocalPanePressesAllowed] so the per-surface observers on dialogs and sheets withhold
 *        the same presses the boundary does. Answering false makes the pane tap-inert and stops it
 *        asking to be focused; it stays scrollable and draggable. A pager-driven layout answers
 *        false while it is not resting on this pane's page, where two pages share the viewport and
 *        a down starting the next swipe lands on the neighbour.
 */
@Composable
fun PaneLayerHost(
    modifier: Modifier = Modifier,
    paneFocused: Boolean,
    clickToFocus: Boolean = true,
    backActive: Boolean = paneFocused,
    allowPresses: () -> Boolean = { true },
    paneEdges: PaneEdges = LocalPaneEdges.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val layerState = remember { PaneLayerState() }
    val pulseState = remember { PaneFocusPulseState() }
    val inert = clickToFocus && !paneFocused
    var pointerHovering by remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalPaneLayerState provides layerState,
        LocalPaneFocused provides paneFocused,
        LocalLayerActive provides paneFocused,
        // Provided only here and inherited unchanged through every PaneLayer: PaneLayer recomputes
        // and overrides LocalLayerActive for its subtree, so a value narrowed there would be
        // discarded by every nested dialog and sheet.
        LocalPaneBackActive provides backActive,
        // Provided above the host's own press observer below, so the boundary is gated by the same
        // value the dialogs and sheets inside it read.
        LocalPanePressesAllowed provides allowPresses,
        LocalPaneLayerRank provides PaneLayerRank.CONTENT,
        LocalPaneEdges provides paneEdges,
        // Shared dialogs from app-common (ErrorDialog above all) become pane-bound inside a pane
        LocalAlertDialogRenderer provides PaneBoundAlertDialogRenderer,
        // Reading `.current` keeps the focused case on whatever the theme provides, instead of
        // pinning it to a default this file would have to keep in sync.
        LocalIndication provides if (inert) NoIndication else LocalIndication.current,
        LocalRippleConfiguration provides if (inert) null else LocalRippleConfiguration.current,
        // Narrowing, never widening: an outer provider that already muted tooltips keeps its say.
        LocalTooltipsEnabled provides (!inert && LocalTooltipsEnabled.current),
    ) {
        Box(
            // The pane boundary is also where the first press into an unfocused pane is swallowed:
            // it focuses the pane without activating the content under the finger. Without
            // clickToFocus the same modifier degrades to a pure observer: the press reaches the
            // content and focus follows it, and no pulse is emitted because nothing was swallowed.
            modifier = modifier
                .requestPaneFocusOnPress(
                    consumeWhenUnfocused = clickToFocus,
                    onPressSwallowed = { pulseState.emit(it) },
                )
                .trackNonTouchHover(enabled = inert) { pointerHovering = it },
        ) {
            content()
            if (inert && pointerHovering) {
                Box(
                    // Hit testing stops at the topmost hit sibling, so this takes the content out
                    // of the hover path: it receives one Exit and no further Enter, which ends
                    // tint, elevation, tooltips and cursor shape at their source. It must consume
                    // nothing — the press observer above still needs every event — and must carry
                    // no semantics beyond the test tag, so assistive tech keeps reaching content
                    // that the barrier only covers visually.
                    modifier = Modifier
                        .matchParentSize()
                        .testTag(TAG_PANE_HOVER_BARRIER)
                        .pointerInput(Unit) {},
                )
            }
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
