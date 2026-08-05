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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString

/**
 * Wraps [content] (typically an icon button) in a plain tooltip shown on long press / hover,
 * anchored above the wrapped element.
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
