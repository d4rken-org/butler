package eu.darken.butler.explorer.ui.explorer.items.row

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.QuestionMark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.formatSmartTime
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
fun TrashItemRow(
    modifier: Modifier = Modifier,
    item: ExplorerItem.Trash.Root,
    isSelected: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    showSelection: Boolean = false,
) {
    val context = LocalContext.current

    FileRowBase(
        modifier = modifier.alpha(if (item.isAvailable) 1f else 0.5f),
        item = item,
        isSelected = isSelected,
        onToggleSelection = onToggleSelection,
        onClick = onClick,
        onLongClick = onLongClick,
        showSelection = showSelection,
        leadingContent = {
            if (item.trashLookup != null) {
                TintedAsyncImage(
                    model = item.trashLookup,
                    contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.TwoTone.QuestionMark,
                    contentDescription = stringResource(R.string.explorer_file_folder_content_desc),
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        },
        primaryText = item.displayName.get(context),
        secondaryText = item.subtitle.get(context),
        tertiaryText = formatSmartTime(item.deletedAt, absoluteStyle = DateTimeStyle.FULL),
        tertiaryEndText = item.trashLookup?.size?.let { formatFileSize(it) },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashItemRowPreview() {
    TrashItemRow(
        item = MockDataProvider.createMockTrashItem(),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashItemRowSelectedPreview() {
    TrashItemRow(
        item = MockDataProvider.createMockTrashItem("photo.jpg"),
        isSelected = true,
        showSelection = true,
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashItemRowOldPreview() {
    TrashItemRow(
        item = MockDataProvider.createMockTrashItemOld(),
    )
}

/** Long deletion timestamp and a wide size in a narrow row - the tertiary line's worst case. */
@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashItemRowNarrowPreview() {
    TrashItemRow(
        modifier = Modifier.width(200.dp),
        item = MockDataProvider.createMockTrashItemOld(sizeKB = 1_289_748),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun TrashItemRowUnavailablePreview() {
    TrashItemRow(
        item = MockDataProvider.createMockTrashItem(
            name = "missing_file.txt",
            isAvailable = false,
        ),
    )
}
