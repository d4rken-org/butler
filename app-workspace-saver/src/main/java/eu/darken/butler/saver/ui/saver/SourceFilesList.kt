package eu.darken.butler.saver.ui.saver

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.FileCopy
import androidx.compose.material.icons.twotone.InsertDriveFile
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.theming.onScrim
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.saver.core.ContentUriHelper
import eu.darken.butler.common.R as CommonR

private fun ContentUriHelper.SourceInfo.isMedia(): Boolean {
    return mimeType?.let { it.startsWith("image/") || it.startsWith("video/") } == true
}

@Composable
internal fun SourceFilesList(
    modifier: Modifier = Modifier,
    sourceInfos: List<ContentUriHelper.SourceInfo>,
) {
    val context = LocalContext.current
    val allMedia = sourceInfos.all { it.isMedia() }

    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            // Header with file count
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    modifier = Modifier.size(24.dp),
                    imageVector = Icons.TwoTone.FileCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = context.getQuantityString2(
                            CommonR.plurals.common_files_count,
                            sourceInfos.size,
                            sourceInfos.size,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val totalSize = sourceInfos.mapNotNull { it.size }.sum()
                    if (totalSize > 0) {
                        Text(
                            text = formatFileSize(bytes = totalSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Show status indicator
                val allAccessible = sourceInfos.all { it.isAccessible }
                val anyInaccessible = sourceInfos.any { !it.isAccessible }
                when {
                    allAccessible -> Icon(
                        imageVector = Icons.TwoTone.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    anyInaccessible -> Icon(
                        imageVector = Icons.TwoTone.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            HorizontalDivider()

            if (allMedia) {
                // Grid view for media files
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sourceInfos) { info ->
                        SourceFileGridItem(info = info)
                    }
                }
            } else {
                // List view for mixed/non-media files
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp),
                ) {
                    items(sourceInfos) { info ->
                        SourceFileRow(info = info)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceFileRow(
    modifier: Modifier = Modifier,
    info: ContentUriHelper.SourceInfo,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Thumbnail: image preview or file icon
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val isImage = info.mimeType?.startsWith("image/") == true
            if (isImage) {
                AsyncImage(
                    model = info.uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    modifier = Modifier.size(24.dp),
                    imageVector = Icons.TwoTone.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // File info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = info.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            info.size?.let { size ->
                Text(
                    text = formatFileSize(bytes = size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Accessibility indicator
        if (!info.isAccessible) {
            Icon(
                modifier = Modifier.size(20.dp),
                imageVector = Icons.TwoTone.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SourceFileGridItem(
    modifier: Modifier = Modifier,
    info: ContentUriHelper.SourceInfo,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Thumbnail
        AsyncImage(
            model = info.uri,
            contentDescription = info.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        // Top bar: error indicator + size
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Error indicator (left)
            if (!info.isAccessible) {
                Icon(
                    modifier = Modifier.size(16.dp),
                    imageVector = Icons.TwoTone.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            } else {
                Spacer(modifier = Modifier.size(16.dp))
            }
            // File size (right)
            info.size?.let { size ->
                Text(
                    text = formatFileSize(bytes = size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onScrim,
                    maxLines = 1,
                )
            }
        }

        // Bottom bar: filename
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(
                text = info.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onScrim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview2
@Composable
private fun SourceFilesListPreview() {
    PreviewWrapper {
        SourceFilesList(
            sourceInfos = listOf(
                ContentUriHelper.SourceInfo(
                    uri = "content://example/image1.jpg".toUri(),
                    displayName = "vacation_photo_001.jpg",
                    mimeType = "image/jpeg",
                    size = 3_500_000,
                    isAccessible = true,
                ),
                ContentUriHelper.SourceInfo(
                    uri = "content://example/image2.jpg".toUri(),
                    displayName = "vacation_photo_002.jpg",
                    mimeType = "image/jpeg",
                    size = 2_800_000,
                    isAccessible = true,
                ),
                ContentUriHelper.SourceInfo(
                    uri = Uri.parse("content://example/image3.jpg"),
                    displayName = "vacation_photo_003.jpg",
                    mimeType = "image/jpeg",
                    size = 4_200_000,
                    isAccessible = true,
                ),
            )
        )
    }
}

@Preview2
@Composable
private fun SourceFilesListWithInaccessiblePreview() {
    PreviewWrapper {
        SourceFilesList(
            sourceInfos = listOf(
                ContentUriHelper.SourceInfo(
                    uri = Uri.parse("content://example/image1.jpg"),
                    displayName = "accessible_photo.jpg",
                    mimeType = "image/jpeg",
                    size = 3_500_000,
                    isAccessible = true,
                ),
                ContentUriHelper.SourceInfo(
                    uri = Uri.parse("content://example/image2.jpg"),
                    displayName = "expired_photo.jpg",
                    mimeType = "image/jpeg",
                    size = 2_800_000,
                    isAccessible = false,
                ),
            )
        )
    }
}

@Preview2
@Composable
private fun SourceFilesListManyFilesPreview() {
    PreviewWrapper {
        SourceFilesList(
            sourceInfos = (1..10).map { i ->
                ContentUriHelper.SourceInfo(
                    uri = Uri.parse("content://example/file$i.jpg"),
                    displayName = "photo_${i.toString().padStart(3, '0')}.jpg",
                    mimeType = "image/jpeg",
                    size = 1_000_000L * i,
                    isAccessible = true,
                )
            }
        )
    }
}

@Preview2
@Composable
private fun SourceFilesListMixedTypesPreview() {
    PreviewWrapper {
        SourceFilesList(
            sourceInfos = listOf(
                ContentUriHelper.SourceInfo(
                    uri = Uri.parse("content://example/photo.jpg"),
                    displayName = "vacation_photo.jpg",
                    mimeType = "image/jpeg",
                    size = 3_500_000,
                    isAccessible = true,
                ),
                ContentUriHelper.SourceInfo(
                    uri = Uri.parse("content://example/document.pdf"),
                    displayName = "travel_itinerary.pdf",
                    mimeType = "application/pdf",
                    size = 1_200_000,
                    isAccessible = true,
                ),
                ContentUriHelper.SourceInfo(
                    uri = Uri.parse("content://example/video.mp4"),
                    displayName = "sunset_video.mp4",
                    mimeType = "video/mp4",
                    size = 15_000_000,
                    isAccessible = true,
                ),
            )
        )
    }
}
