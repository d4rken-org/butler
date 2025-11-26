package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.QuestionMark
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.formatSmartTime
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun TrashItemRow(
    item: ExplorerItem.TrashItem,
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
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (showSelection) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelection?.invoke() },
                )
            } else {
                if (item.trashLookup != null) {
                    TintedAsyncImage(
                        model = item.trashLookup,
                        contentDescription = item.originalLookup.name,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.TwoTone.QuestionMark,
                        contentDescription = item.originalLookup.name,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            }
        }

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

            Text(
                text = formatSmartTime(item.deletedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        item.trashLookup?.size?.let {
            Text(
                text = formatFileSize(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview2
@Composable
private fun TrashItemRowPreview() {
    PreviewWrapper {
        TrashItemRow(
            item = MockDataProvider.createMockTrashItem(),
        )
    }
}

@Preview2
@Composable
private fun TrashItemRowSelectedPreview() {
    PreviewWrapper {
        TrashItemRow(
            item = MockDataProvider.createMockTrashItem("photo.jpg"),
            isSelected = true,
            showSelection = true,
        )
    }
}

@Preview2
@Composable
private fun TrashItemRowOldPreview() {
    PreviewWrapper {
        TrashItemRow(
            item = MockDataProvider.createMockTrashItemOld(),
        )
    }
}

@Preview2
@Composable
private fun TrashItemRowUnavailablePreview() {
    PreviewWrapper {
        TrashItemRow(
            item = MockDataProvider.createMockTrashItem(
                name = "missing_file.txt",
                isAvailable = false,
            ),
        )
    }
}