package eu.darken.butler.explorer.ui.explorer.rows

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerPathItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun SymlinkFileRow(
    item: ExplorerPathItem.SymbolicLink,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    showSelection: Boolean,
    modifier: Modifier = Modifier
) {
    FileRowBase(
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        showSelection = showSelection,
        modifier = modifier,
        leadingContent = {
            Icon(
                imageVector = Icons.TwoTone.Link,
                contentDescription = stringResource(R.string.explorer_file_symlink_content_desc),
                tint = if (item.isBroken) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(32.dp)
            )
        },
        primaryText = item.displayName,
        secondaryText = buildString {
            item.targetPath?.let { 
                append("→ $it")
                append(" • ")
            }
            if (item.isBroken) {
                append(stringResource(R.string.explorer_file_broken_link_label))
            } else {
                append(item.displayDate)
            }
        }
    )
}

@Preview2
@Composable
private fun SymlinkFileRowPreview() {
    SymlinkFileRow(
        item = MockDataProvider.createMockSymbolicLink(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun SymlinkFileRowBrokenPreview() {
    SymlinkFileRow(
        item = MockDataProvider.createMockSymbolicLink("broken_link", "/path/to/missing/file", true),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}