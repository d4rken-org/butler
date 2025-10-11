package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun StorageRow(
    modifier: Modifier = Modifier,
    item: ExplorerItem.Storage,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showSelection: Boolean = false,
) {
    val context = LocalContext.current

    FileRowBase(
        modifier = modifier,
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        showSelection = showSelection,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = item.displayIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        },
        primaryText = item.displayName.get(context),
        secondaryText = when (item) {
            is ExplorerItem.Storage.Local -> stringResource(R.string.explorer_file_storage_local_label)
            is ExplorerItem.Storage.SAF -> stringResource(R.string.explorer_file_storage_saf_label)
        }
    )
}

@Preview2
@Composable
private fun StorageRowLocalPreview() {
    PreviewWrapper {
        StorageRow(
            item = MockDataProvider.createMockStorageLocal(),
            onClick = {}
        )
    }
}

@Preview2
@Composable
private fun StorageRowSAFPreview() {
    PreviewWrapper {
        StorageRow(
            item = MockDataProvider.createMockStorageSAF(),
            onClick = {}
        )
    }
}
