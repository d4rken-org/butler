package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.core.engine.ExplorerItem

@Composable
fun RecycleBinItemRow(
    item: ExplorerItem.RecycleBinItem,
    isSelected: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    showSelection: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSelection) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection?.invoke() },
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        Icon(
            imageVector = item.displayIcon,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = if (!item.isAvailable) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = item.displayName.get(LocalContext.current),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (!item.isAvailable) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurface
            )

            item.subtitle?.let { subtitle ->
                Text(
                    text = subtitle.get(LocalContext.current),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Text(
            text = formatFileSize(item.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun RecycleBinItemGrid(
    item: ExplorerItem.RecycleBinItem,
    isSelected: Boolean = false,
    onToggleSelection: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    showSelection: Boolean = false,
) {
    Card(
        modifier = Modifier
            .padding(4.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                contentAlignment = Alignment.TopEnd
            ) {
                Icon(
                    imageVector = item.displayIcon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = if (!item.isAvailable) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.onSurface
                )

                if (showSelection) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection?.invoke() },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = item.displayName.get(LocalContext.current),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = if (!item.isAvailable) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp)
            )

            Text(
                text = formatFileSize(item.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}