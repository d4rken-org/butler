package eu.darken.butler.searcher.ui.search.elements

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper


@Composable
fun CompactFilterChip(
    modifier: Modifier = Modifier,
    label: String,
    selected: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    height: Dp = CompactChipDefaults.Height,
    iconSize: Dp = CompactChipDefaults.IconSize,
    fontSize: TextUnit = CompactChipDefaults.FontSize,
    horizontalPadding: Dp = CompactChipDefaults.HorizontalPadding,
    iconSpacing: Dp = CompactChipDefaults.IconSpacing,
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.height(height),
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontSize = fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(start = horizontalPadding, end = if (onRemove != null) iconSpacing else horizontalPadding),
            )
            if (onRemove != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clickable(enabled = enabled, onClick = onRemove)
                        .padding(end = horizontalPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}

@Composable
fun CompactAssistChip(
    modifier: Modifier = Modifier,
    label: String,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    height: Dp = CompactChipDefaults.Height,
    iconSize: Dp = CompactChipDefaults.IconSize,
    fontSize: TextUnit = CompactChipDefaults.FontSize,
    horizontalPadding: Dp = CompactChipDefaults.HorizontalPadding,
    iconSpacing: Dp = CompactChipDefaults.IconSpacing,
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Surface(
        modifier = modifier.height(height),
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                )
                Spacer(modifier = Modifier.width(iconSpacing))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

object CompactChipDefaults {
    val Height = 24.dp
    val IconSize = 14.dp
    val FontSize = 11.sp
    val HorizontalPadding = 10.dp
    val IconSpacing = 4.dp
}

@Preview2
@Composable
private fun CompactFilterChipSelectedPreview() {
    PreviewWrapper {
        CompactFilterChip(
            label = "/storage/emulated/0",
            selected = true,
            onClick = {},
            onRemove = {},
        )
    }
}

@Preview2
@Composable
private fun CompactFilterChipUnselectedPreview() {
    PreviewWrapper {
        CompactFilterChip(
            label = "/storage/emulated/0",
            selected = false,
            onClick = {},
            onRemove = {},
        )
    }
}

@Preview2
@Composable
private fun CompactFilterChipLongTextPreview() {
    PreviewWrapper {
        CompactFilterChip(
            label = "/storage/emulated/0/very/long/path/that/should/truncate",
            selected = true,
            onClick = {},
            onRemove = {},
        )
    }
}

@Preview2
@Composable
private fun CompactFilterChipNoRemovePreview() {
    PreviewWrapper {
        CompactFilterChip(
            label = ">100MB",
            selected = true,
            onClick = {},
            onRemove = null,
        )
    }
}

@Preview2
@Composable
private fun CompactFilterChipDisabledPreview() {
    PreviewWrapper {
        CompactFilterChip(
            label = "/storage/emulated/0",
            selected = true,
            enabled = false,
            onClick = {},
            onRemove = {},
        )
    }
}

@Preview2
@Composable
private fun CompactAssistChipWithIconPreview() {
    PreviewWrapper {
        CompactAssistChip(
            label = "Add path",
            leadingIcon = Icons.TwoTone.Add,
            onClick = {},
        )
    }
}

@Preview2
@Composable
private fun CompactAssistChipNoIconPreview() {
    PreviewWrapper {
        CompactAssistChip(
            label = "Show fewer",
            onClick = {},
        )
    }
}

@Preview2
@Composable
private fun CompactAssistChipDisabledPreview() {
    PreviewWrapper {
        CompactAssistChip(
            label = "Add path",
            leadingIcon = Icons.TwoTone.Add,
            enabled = false,
            onClick = {},
        )
    }
}
