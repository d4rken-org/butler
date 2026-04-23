package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem

@Composable
fun TrashNestedItemRow(
    modifier: Modifier = Modifier,
    item: ExplorerItem.Trash.Nested,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit = {},
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
            TintedAsyncImage(
                model = item.lookup,
                contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                modifier = Modifier.size(32.dp),
            )
        },
        primaryText = item.displayName.get(context),
        secondaryText = item.lookup.size?.let { formatFileSize(it) },
        tertiaryText = null,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashNestedItemRowPreview() {
    TrashNestedItemRow(
        item = eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider.createMockTrashNestedItem(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashNestedItemRowSelectedPreview() {
    TrashNestedItemRow(
        item = eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider.createMockTrashNestedItem("photo.jpg"),
        isSelected = true,
        showSelection = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashNestedItemRowDirectoryPreview() {
    TrashNestedItemRow(
        item = eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider.createMockTrashNestedDirectory(),
    )
}
