package eu.darken.butler.common.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString

/**
 * Whether hover and long-press tooltips may trigger in this subtree.
 *
 * Turned off for a workspace pane that is not the focused one: such a pane answers no click until
 * it is focused, so nothing in it may advertise itself as interactive either. Tooltips are the one
 * hover affordance that survives the pane's indication suppression, because they are their own
 * window rather than a state layer.
 *
 * Turning it off also drops the tooltip's own `onLongClick` semantics action and its keyboard-focus
 * trigger, which is why every anchor must carry its label as a `contentDescription` too — all of
 * them do, so assistive tech loses nothing while the tooltip is muted. Deliberately unlike the
 * pane's press swallow, which stays pointer-only: there the semantics action *is* the activation
 * path, here it only duplicates a label.
 *
 * Not a `staticCompositionLocalOf`: the workspace layer flips it whenever pane focus moves, and a
 * static local invalidates its provider's whole content subtree on every change, while this one
 * only invalidates the composables that actually read it.
 */
val LocalTooltipsEnabled = compositionLocalOf { true }

/**
 * Wraps [content] (typically an icon button) in a plain tooltip shown on long press / hover,
 * anchored above the wrapped element.
 *
 * Honours [LocalTooltipsEnabled], so a tooltip inside an unfocused pane stays silent.
 */
@Composable
fun ButlerTooltip(
    modifier: Modifier = Modifier,
    label: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        modifier = modifier,
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        enableUserInput = LocalTooltipsEnabled.current,
        content = content,
    )
}

@Composable
fun ButlerTooltip(
    modifier: Modifier = Modifier,
    label: CaString,
    content: @Composable () -> Unit,
) = ButlerTooltip(
    modifier = modifier,
    label = label.asComposable(),
    content = content,
)

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerTooltipPreview() {
    ButlerTooltip(label = "Save".toCaString()) {
        IconButton(onClick = {}) {
            Icon(Icons.TwoTone.Save, contentDescription = "Save")
        }
    }
}
