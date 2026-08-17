package eu.darken.butler.common.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.ButlerPreviewWrapper

enum class ButlerChipSize(val height: Dp, val fontSize: TextUnit, val iconSize: Dp) {
    Mini(16.dp, 8.sp, 10.dp),
    Compact(20.dp, 10.sp, 12.dp),
    Default(24.dp, 11.sp, 14.dp),
    Large(28.dp, 12.sp, 16.dp),
}

@Immutable
data class ButlerChipColors(
    val containerColor: Color,
    val contentColor: Color,
    val selectedContainerColor: Color,
    val selectedContentColor: Color,
)

object ButlerChipDefaults {

    @Composable
    fun colors(
        containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
        selectedContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    ): ButlerChipColors = ButlerChipColors(
        containerColor = containerColor,
        contentColor = contentColor,
        selectedContainerColor = selectedContainerColor,
        selectedContentColor = selectedContentColor,
    )

    @Composable
    fun accentedColors(
        containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
        selectedContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    ): ButlerChipColors = ButlerChipColors(
        containerColor = containerColor,
        contentColor = contentColor,
        selectedContainerColor = selectedContainerColor,
        selectedContentColor = selectedContentColor,
    )

    @Composable
    fun highlightColors(
        containerColor: Color = MaterialTheme.colorScheme.primary,
        contentColor: Color = MaterialTheme.colorScheme.onPrimary,
        selectedContainerColor: Color = MaterialTheme.colorScheme.primary,
        selectedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    ): ButlerChipColors = ButlerChipColors(
        containerColor = containerColor,
        contentColor = contentColor,
        selectedContainerColor = selectedContainerColor,
        selectedContentColor = selectedContentColor,
    )

    @Composable
    fun errorColors(
        containerColor: Color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
        contentColor: Color = MaterialTheme.colorScheme.onErrorContainer,
        selectedContainerColor: Color = MaterialTheme.colorScheme.errorContainer,
        selectedContentColor: Color = MaterialTheme.colorScheme.onErrorContainer,
    ): ButlerChipColors = ButlerChipColors(
        containerColor = containerColor,
        contentColor = contentColor,
        selectedContainerColor = selectedContainerColor,
        selectedContentColor = selectedContentColor,
    )
}

@Composable
fun ButlerChip(
    modifier: Modifier = Modifier,
    label: String,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    strikethrough: Boolean = false,
    size: ButlerChipSize = ButlerChipSize.Default,
    colors: ButlerChipColors = ButlerChipDefaults.colors(),
    contentDescription: String? = null,
) {
    val containerColor = if (selected) colors.selectedContainerColor else colors.containerColor
    val contentColor = if (selected) colors.selectedContentColor else colors.contentColor
    val disabledAlpha = if (enabled) 1f else 0.38f

    val chipModifier = modifier
        .height(size.height)
        .then(
            if (contentDescription != null || onClick != null) {
                Modifier.semantics {
                    if (contentDescription != null) this.contentDescription = contentDescription
                    if (onClick != null) this.selected = selected
                }
            } else Modifier
        )
    val chipShape = MaterialTheme.shapes.small
    val chipContainerColor = containerColor.copy(alpha = containerColor.alpha * disabledAlpha)
    val chipContentColor = contentColor.copy(alpha = contentColor.alpha * disabledAlpha)

    val chipContent: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(size.iconSize),
                )
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                Spacer(modifier = Modifier.width(10.dp))
            }

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontSize = size.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (strikethrough) TextDecoration.LineThrough else null,
                modifier = Modifier.padding(end = if (onRemove != null) 4.dp else 10.dp),
            )

            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable(enabled = enabled, onClick = onRemove)
                        .padding(end = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = null,
                        modifier = Modifier.size(size.iconSize),
                    )
                }
            }
        }
    }

    if (onClick != null) {
        Surface(
            modifier = chipModifier,
            onClick = onClick,
            enabled = enabled,
            shape = chipShape,
            color = chipContainerColor,
            contentColor = chipContentColor,
            content = chipContent,
        )
    } else {
        // A decorative chip must not be a clickable Surface: even disabled it consumes presses aimed
        // at the row/content beneath it and claims a 48dp minimum touch target around itself.
        Surface(
            modifier = chipModifier,
            shape = chipShape,
            color = chipContainerColor,
            contentColor = chipContentColor,
            content = chipContent,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipDefaultPreview() {
    ButlerChip(
        label = "Default Chip",
        onClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipWithLeadingIconPreview() {
    ButlerChip(
        label = "42 items",
        leadingIcon = Icons.TwoTone.Folder,
        onClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipRemovablePreview() {
    ButlerChip(
        label = "System",
        onRemove = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipSelectedPreview() {
    ButlerChip(
        label = "Enabled",
        selected = true,
        onClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipAddActionPreview() {
    ButlerChip(
        label = "Filter",
        leadingIcon = Icons.TwoTone.Add,
        onClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipCompactPreview() {
    ButlerChip(
        label = "Debug",
        size = ButlerChipSize.Compact,
        colors = ButlerChipDefaults.accentedColors(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipLargePreview() {
    ButlerChip(
        label = "Large Chip",
        leadingIcon = Icons.TwoTone.Star,
        size = ButlerChipSize.Large,
        onClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipErrorPreview() {
    ButlerChip(
        label = "Excluded",
        strikethrough = true,
        onRemove = {},
        colors = ButlerChipDefaults.errorColors(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipDisabledPreview() {
    ButlerChip(
        label = "Disabled",
        enabled = false,
        onClick = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipRemovableWithIconPreview() {
    ButlerChip(
        label = "Category",
        leadingIcon = Icons.TwoTone.Folder,
        onRemove = {},
        colors = ButlerChipDefaults.accentedColors(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipMiniPreview() {
    ButlerChip(
        label = "System",
        size = ButlerChipSize.Mini,
        colors = ButlerChipDefaults.accentedColors(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ButlerChipHighlightPreview() {
    ButlerChip(
        label = "3 selected",
        leadingIcon = Icons.TwoTone.Star,
        colors = ButlerChipDefaults.highlightColors(),
        onClick = {},
    )
}
