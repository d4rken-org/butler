package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem

@Composable
fun TrashItemGrid(
    item: ExplorerItem.TrashItem,
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
                modifier = Modifier.size(32.dp),
            ) {
                if (showSelection) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelection?.invoke() },
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    TintedAsyncImage(
                        model = item.trashLookup,
                        contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                        modifier = Modifier.size(32.dp),
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

            item.trashLookup?.size?.let {
                Text(
                    text = formatFileSize(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}