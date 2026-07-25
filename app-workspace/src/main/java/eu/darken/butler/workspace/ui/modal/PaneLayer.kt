package eu.darken.butler.workspace.ui.modal

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics

/**
 * One layer of a pane's modal stack.
 *
 * Registers itself with the enclosing [PaneLayerHost] and, from the resulting stack position,
 * derives everything a modal needs:
 * - [LocalLayerActive] for the subtree, so back handlers and focus requests self-disable while
 *   something is stacked on top of them;
 * - focus containment while on top — `focusProperties { canFocus = false }` is not usable here
 *   because focus search descends into a deactivated node's children, so the trap is built from
 *   `focusGroup()` plus `onExit { cancelFocusChange() }`, paired with clearing focus when the layer
 *   is covered and pulling focus in when it becomes active;
 * - hiding covered layers from accessibility traversal.
 *
 * @param rank stacking rank, defaults to the ambient [LocalPaneLayerRank] of the region.
 * @param modal whether this layer traps focus while it is the active one. The pane's base content
 *        layer is not modal, otherwise keyboard focus could never leave the pane.
 * @param takeFocus whether becoming the active layer should pull keyboard focus into this layer.
 *        Defaults to [modal]. A layer whose content deliberately dismisses focus and the keyboard —
 *        a confirm-only dialog, a sheet without a text field — must opt out, otherwise the two
 *        effects fight and the one composed deeper simply wins by running last. Containment does
 *        not depend on this: it comes from the trap here plus the `onEnter` guard on covered layers.
 * @param enabled while `false` the layer is fully transparent: no registration, no containment and
 *        [LocalLayerActive] is inherited unchanged. Lets a layer outlive its own visibility (e.g. a
 *        sheet running its exit transition) without remounting its content.
 */
@Composable
fun PaneLayer(
    modifier: Modifier = Modifier,
    rank: Int = LocalPaneLayerRank.current,
    modal: Boolean = true,
    takeFocus: Boolean = modal,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val layerState = LocalPaneLayerState.current
    val paneFocused = LocalPaneFocused.current
    val inheritedActive = LocalLayerActive.current
    val parentToken = LocalPaneLayerParent.current
    val token = remember { Any() }
    val registered = enabled && layerState != null

    DisposableEffect(layerState, token, rank, parentToken, registered) {
        if (registered) layerState?.push(token, rank, parentToken)
        onDispose { layerState?.pop(token) }
    }

    val isTop = !registered || layerState?.isTop(token) == true
    // A layer that encloses the top one must stay reachable, or it would take the top layer down
    // with it — but it is still not the active layer.
    val onTopPath = !registered || layerState?.isOnTopPath(token) == true
    val active = if (registered) paneFocused && isTop else inheritedActive

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }

    // Read inside onEnter/onExit rather than baked into the modifier: a focus-properties change is
    // only picked up on the next measure pass, which is *after* the effects below run. Deciding at
    // invocation time means a trap that has just been disarmed can no longer veto the very
    // clearFocus() that disarming it was supposed to allow.
    val trapFocus = rememberUpdatedState(registered && isTop && modal && active)
    val blockFocusEntry = rememberUpdatedState(registered && !onTopPath)

    // A text field behind a dialog would otherwise keep focus and the soft keyboard. Keyed on
    // hasFocus too, so a layer that only gains focus after being covered still gives it up.
    LaunchedEffect(registered, onTopPath, hasFocus) {
        if (registered && !onTopPath && hasFocus) focusManager.clearFocus(force = true)
    }

    // Losing pane focus while holding it must release focus as well — a modal that is still top of
    // its own pane hits neither the covered case above nor the activation case below. Deliberately
    // keyed on paneFocused only: re-running this when hasFocus changes would clear focus the moment
    // the user taps into a pane that has not been marked focused yet.
    LaunchedEffect(registered, paneFocused) {
        if (registered && !paneFocused && hasFocus) focusManager.clearFocus(force = true)
    }

    LaunchedEffect(registered, isTop, active, takeFocus) {
        if (registered && isTop && active && takeFocus) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { hasFocus = it.hasFocus }
            .focusProperties {
                // The trap follows `active`, not just `isTop`: armed in an unfocused pane it would
                // keep focus hostage there and stop another pane from taking it.
                onExit = { if (trapFocus.value) cancelFocusChange() }
                onEnter = { if (blockFocusEntry.value) cancelFocusChange() }
            }
            .focusGroup()
            .then(
                if (registered && !onTopPath) Modifier.semantics { hideFromAccessibility() } else Modifier
            ),
    ) {
        val boxScope = this
        CompositionLocalProvider(
            LocalLayerActive provides active,
            LocalPaneLayerRank provides rank,
            LocalPaneLayerParent provides if (registered) token else parentToken,
        ) {
            boxScope.content()
        }
    }
}
