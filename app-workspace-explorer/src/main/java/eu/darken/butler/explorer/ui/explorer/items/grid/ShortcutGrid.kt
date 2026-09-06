package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.BadgedIcon
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.core.ExplorerViewStyle
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.items.gridBadgeSize
import eu.darken.butler.explorer.ui.explorer.items.gridIconSize
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun ShortcutGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.Shortcut,
    density: ExplorerViewStyle.Density,
    isEnabled: Boolean = true,
    onClick: () -> Unit,
) {
    val hasBadge = item.badge != null
    val badgeIcon = when (item.badge) {
        ExplorerItem.Shortcut.Badge.PAUSED -> Icons.TwoTone.PauseCircle
        null -> null
    }
    FileGridBase(
        modifier = modifier,
        item = item,
        density = density,
        isSelected = false,
        onToggleSelection = {},
        onClick = onClick,
        onLongClick = {},
        showSelection = false,
        isEnabled = isEnabled,
        icon = {
            BadgedIcon(
                icon = item.displayIcon,
                badge = badgeIcon,
                iconSize = density.gridIconSize,
                badgeSize = density.gridBadgeSize,
                iconTint = if (hasBadge) Color.White.copy(alpha = 0.7f) else Color.White,
                badgeTint = Color.White,
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        secondaryText = null,
        backgroundColor = if (hasBadge) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        }
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShortcutGridPreview() {
    ShortcutGrid(
        item = MockDataProvider.createMockShortcut(),
        density = ExplorerViewStyle.Density.COMFORTABLE,
        onClick = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShortcutGridCompactPreview() {
    ShortcutGrid(
        item = MockDataProvider.createMockShortcut(),
        density = ExplorerViewStyle.Density.COMPACT,
        onClick = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun ShortcutGridDetailedPreview() {
    ShortcutGrid(
        item = MockDataProvider.createMockShortcut(),
        density = ExplorerViewStyle.Density.DETAILED,
        onClick = {}
    )
}
