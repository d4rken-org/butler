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
 * @param modal whether this layer traps focus while on top. The pane's base content layer is not
 *        modal, otherwise keyboard focus could never leave the pane.
 * @param enabled while `false` the layer is fully transparent: no registration, no containment and
 *        [LocalLayerActive] is inherited unchanged. Lets a layer outlive its own visibility (e.g. a
 *        sheet running its exit transition) without remounting its content.
 */
@Composable
fun PaneLayer(
    modifier: Modifier = Modifier,
    rank: Int = LocalPaneLayerRank.current,
    modal: Boolean = true,
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

    LaunchedEffect(registered, isTop, onTopPath, active, modal) {
        if (!registered) return@LaunchedEffect
        if (!onTopPath) {
            // A text field behind a dialog would otherwise keep focus and the soft keyboard
            if (hasFocus) focusManager.clearFocus(force = true)
        } else if (isTop && modal && active) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { hasFocus = it.hasFocus }
            .focusProperties {
                if (!registered) return@focusProperties
                if (isTop) {
                    if (modal) onExit = { cancelFocusChange() }
                } else if (!onTopPath) {
                    onEnter = { cancelFocusChange() }
                }
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
