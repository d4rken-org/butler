package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun DirectoryRow(
    modifier: Modifier = Modifier,
    item: ExplorerItem.RegularDirectory,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean,
) {
    FileRowBase(
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        showSelection = showSelection,
        modifier = modifier,
        leadingContent = {
            Icon(
                imageVector = Icons.TwoTone.Folder,
                contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        secondaryText = buildString {
            item.childCount?.let { count ->
                append(stringResource(R.string.explorer_file_items_count, count))
                append(" • ")
            }
            append(item.lookup.modifiedAt?.let { formatDate(it) } ?: "?")
            item.permissions?.let { perms ->
                append(" • ")
                append(perms.mode)
            }
            item.ownership?.let { owner ->
                append(" • ")
                append(owner.userName ?: owner.userId)
            }
        }
    )
}

@Preview2
@Composable
private fun DirectoryRowPreview() {
    DirectoryRow(
        item = MockDataProvider.createMockDirectory(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun DirectoryRowSelectedPreview() {
    DirectoryRow(
        item = MockDataProvider.createMockDirectory("Downloads", 12),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}