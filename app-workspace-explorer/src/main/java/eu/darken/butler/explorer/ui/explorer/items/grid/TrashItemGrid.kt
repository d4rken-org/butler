package eu.darken.butler.explorer.ui.explorer.items.grid

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.files.extensions.isDirectory
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.formatSmartTime
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun TrashItemGrid(
    modifier: Modifier = Modifier,
    item: ExplorerItem.TrashItem,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    showSelection: Boolean = false,
) {
    val context = LocalContext.current
    val isDirectory = item.originalLookup.isDirectory

    FileGridBase(
        modifier = modifier.alpha(if (item.isAvailable) 1f else 0.5f),
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        showSelection = showSelection,
        icon = {
            Icon(
                imageVector = if (isDirectory) Icons.TwoTone.Folder else Icons.TwoTone.Description,
                contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        },
        primaryText = item.displayName.get(context),
        secondaryText = item.trashLookup?.size?.let { formatFileSize(it) },
        tertiaryText = formatSmartTime(item.deletedAt),
        previewContent = {
            TintedAsyncImage(
                model = item.trashLookup ?: item.originalLookup,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        },
    )
}

@Preview2
@Composable
private fun TrashItemGridPreview() {
    PreviewWrapper {
        TrashItemGrid(
            item = MockDataProvider.createMockTrashItem(),
        )
    }
}

@Preview2
@Composable
private fun TrashItemGridSelectedPreview() {
    PreviewWrapper {
        TrashItemGrid(
            item = MockDataProvider.createMockTrashItem("photo.jpg"),
            isSelected = true,
            showSelection = true,
        )
    }
}

@Preview2
@Composable
private fun TrashItemGridOldPreview() {
    PreviewWrapper {
        TrashItemGrid(
            item = MockDataProvider.createMockTrashItemOld(),
        )
    }
}
