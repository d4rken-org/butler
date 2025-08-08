package eu.darken.butler.explorer.ui.explorer.rows

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Movie
import androidx.compose.material.icons.twotone.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.core.engine.ExplorerPathItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun MediaFileRow(
    item: ExplorerPathItem.MediaFile,
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
            if (item.isVideo) {
                // TODO: Replace with AsyncImage when Coil integration is complete
                Icon(
                    imageVector = Icons.TwoTone.Movie,
                    contentDescription = "Video file",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.TwoTone.MusicNote,
                    contentDescription = "Audio file",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(32.dp)
                )
            }
        },
        primaryText = item.displayName,
        secondaryText = buildString {
            item.duration?.let { 
                append(it)
                append(" • ")
            }
            item.resolution?.let { 
                append(it)
                append(" • ")
            }
            append(item.displaySize)
        }
    )
}

@Preview2
@Composable
private fun AudioFileRowPreview() {
    MediaFileRow(
        item = MockDataProvider.createMockMediaFile(),
        isSelected = false,
        onToggleSelection = {},
        onClick = {},
        showSelection = false
    )
}

@Preview2
@Composable
private fun VideoFileRowPreview() {
    MediaFileRow(
        item = MockDataProvider.createMockMediaFile("movie.mp4", isVideo = true, duration = "1:23:45", resolution = "4K"),
        isSelected = true,
        onToggleSelection = {},
        onClick = {},
        showSelection = true
    )
}