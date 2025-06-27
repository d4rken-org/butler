package eu.darken.butler.explorer.ui.explorer.rows

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.explorer.ui.explorer.FileItem
import eu.darken.butler.explorer.ui.explorer.preview.MockDataProvider

@Composable
internal fun MediaFileRow(
    item: FileItem.MediaFile,
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
                    imageVector = Icons.Default.Movie,
                    contentDescription = "Video file",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(40.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "Audio file",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(40.dp)
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