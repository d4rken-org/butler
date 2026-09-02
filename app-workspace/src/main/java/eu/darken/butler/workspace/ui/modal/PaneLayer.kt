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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import eu.darken.butler.workspace.ui.LocalWorkspaceFocusRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * How long a pane-focus request may stay unanswered before the layer gives up its focus.
 *
 * This is a safety net for a request that was never going to be honoured — no handler, or a pane
 * that cannot become the focused one — not an estimate of how long the round trip takes. Anything
 * legitimate answers far sooner; overshooting only delays the cleanup, while undershooting would
 * throw away focus that was about to become valid, and nothing would restore it afterwards.
 */
private val PANE_FOCUS_REQUEST_TIMEOUT = 1.seconds

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
    val inheritedOnTopPath = LocalLayerOnTopPath.current
    val parentToken = LocalPaneLayerParent.current
    val token = remember { Any() }
    val registered = enabled && layerState != null

    DisposableEffect(layerState, token, rank, parentToken, registered) {
        if (registered) layerState.push(token, rank, parentToken)
        onDispose { layerState?.pop(token) }
    }

    val isTop = !registered || layerState.isTop(token)
    // A layer that encloses the top one must stay reachable, or it would take the top layer down
    // with it — but it is still not the active layer.
    val onTopPath = !registered || layerState.isOnTopPath(token)
    val active = if (registered) paneFocused && isTop else inheritedActive

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }

    // The handlers below stay installed and decide when focus actually tries to move, instead of
    // being added and removed as the state changes. The decision then always reflects the stack and
    // pane state at that moment, and the modifier chain keeps a stable identity.
    val trapFocus = rememberUpdatedState(registered && isTop && modal && active)
    val blockFocusEntry = rememberUpdatedState(registered && !onTopPath)
    val currentPaneFocused = rememberUpdatedState(paneFocused)
    val requestPaneFocus = LocalWorkspaceFocusRequest.current

    // A text field behind a dialog would otherwise keep focus and the soft keyboard. Keyed on
    // hasFocus too, so a layer that only gains focus after being covered still gives it up.
    LaunchedEffect(registered, onTopPath, hasFocus) {
        if (registered && !onTopPath && hasFocus) focusManager.clearFocus(force = true)
    }

    // Losing pane focus while holding it must release focus as well — a modal that is still top of
    // its own pane hits neither the covered case above nor the activation case below. Keyed on
    // paneFocused only, so this handles the pane being left and never fights the case below.
    LaunchedEffect(registered, paneFocused) {
        if (registered && !paneFocused && hasFocus) focusManager.clearFocus(force = true)
    }

    // The mirror image: focus arriving in a pane that is not the focused one — keyboard traversal
    // into it, or a child requesting focus asynchronously. Treat it exactly like a tap on a modal
    // surface and ask for this pane to become focused, so focus and back dispatch stay in the same
    // pane. Only if that request goes unhonoured is the focus given up; simply clearing here would
    // steal focus the instant a user reaches into a pane that is not marked focused yet.
    LaunchedEffect(registered, hasFocus) {
        if (!registered || !hasFocus || currentPaneFocused.value) return@LaunchedEffect
        requestPaneFocus?.invoke()
        // Wait for the answer rather than for a number of frames: the request travels through the
        // workspace screen action, the ViewModel and the page manager before it comes back as pane
        // focus, and there is no frame count that reliably covers that.
        val honoured = withTimeoutOrNull(PANE_FOCUS_REQUEST_TIMEOUT) {
            snapshotFlow { currentPaneFocused.value }.first { it }
        }
        if (honoured == null && hasFocus && !currentPaneFocused.value) {
            focusManager.clearFocus(force = true)
        }
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
            LocalLayerOnTopPath provides if (registered) onTopPath else inheritedOnTopPath,
            LocalPaneLayerRank provides rank,
            LocalPaneLayerParent provides if (registered) token else parentToken,
        ) {
            boxScope.content()
        }
    }
}
