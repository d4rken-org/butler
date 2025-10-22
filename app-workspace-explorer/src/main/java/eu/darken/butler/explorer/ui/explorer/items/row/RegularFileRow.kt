package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.formatDate
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun RegularFileRow(
    modifier: Modifier = Modifier,
    item: ExplorerItem.RegularFile,
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
            TintedAsyncImage(
                model = item.lookup,
                contentDescription = stringResource(R.string.explorer_file_regular_content_desc),
                modifier = Modifier.size(32.dp)
            )
        },
        primaryText = item.displayName.get(LocalContext.current),
        secondaryText = buildString {
            append(item.lookup.size?.let { formatFileSize(it) } ?: "?")
            append(" • ")
            append(item.lookup.modifiedAt?.let { formatDate(it) } ?: "?")
            item.permissions?.let { perms ->
                append(" • ")
                append(perms.mode)
            }
            item.ownership?.let { owner ->
                append(" • ")
                append(owner.userName ?: owner.userId)
                append(" | ")
                append(owner.groupName ?: owner.groupId)
            }
        }
    )
}

@Preview2
@Composable
private fun RegularFileRowPreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun RegularFileRowSelectedPreview() {
    RegularFileRow(
        item = MockDataProvider.createMockRegularFile("config.json"),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}