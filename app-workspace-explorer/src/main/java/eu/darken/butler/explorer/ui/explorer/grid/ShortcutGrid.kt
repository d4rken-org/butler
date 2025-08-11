package eu.darken.butler.explorer.ui.explorer.grid

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.darken.butler.explorer.core.engine.ExplorerItem

@Composable
fun ShortcutGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.Shortcut,
    onClick: () -> Unit,
) {
    FileGridBase(
        modifier = modifier,
        item = item,
        isSelected = false,
        onToggleSelection = {},
        onClick = onClick,
        onLongClick = {},
        showSelection = false,
        icon = {
            Icon(
                imageVector = item.displayIcon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        secondaryText = null,
        backgroundColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    )
}