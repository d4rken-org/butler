package eu.darken.butler.searcher.ui.search.items

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Description
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.theming.onScrim
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider

@Composable
fun SelectableFileGrid(
    result: SearchItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Type-specific background color (match Explorer's transparency)
    val backgroundColor = when (result.fileType) {
        FileType.DIRECTORY -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    // Parent path for context
    val parentPath = result.lookup.parent?.userReadablePath?.asComposable()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(
                width = if (isSelected) 2.dp else 0.5.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                },
                shape = RoundedCornerShape(4.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        shape = RoundedCornerShape(4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Preview/thumbnail background
            TintedAsyncImage(
                model = result.lookup,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )

            // Top overlay bar with icon and file size
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon or checkbox in top-left
                Box(
                    modifier = Modifier.size(20.dp)
                ) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onClick() },
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        // Use Material TwoTone icons like Explorer
                        when (result.fileType) {
                            FileType.DIRECTORY -> Icon(
                                imageVector = Icons.TwoTone.Folder,
                                contentDescription = "Folder",
                                tint = MaterialTheme.colorScheme.onScrim,
                                modifier = Modifier.size(20.dp)
                            )
                            else -> Icon(
                                imageVector = Icons.TwoTone.Description,
                                contentDescription = "File",
                                tint = MaterialTheme.colorScheme.onScrim,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // File size in top-right
                result.size?.let { size ->
                    Text(
                        text = formatFileSize(size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onScrim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            // Bottom overlay bar with filename and parent path
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    // Primary: Filename
                    Text(
                        text = result.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onScrim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Tertiary: Parent path
                    if (parentPath != null) {
                        Text(
                            text = parentPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onScrim.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun SelectableFileGridPreview() {
    val searchResult = SearcherMockDataProvider.createMockTextFile(
        name = "example.txt",
        sizeKB = 1,
        hoursAgo = 1
    )
    PreviewWrapper {
        SelectableFileGrid(
            result = searchResult,
            isSelected = false,
            isSelectionMode = false,
            onClick = {},
            onLongPress = {}
        )
    }
}

@Preview2
@Composable
private fun SelectableFileGridSelectedPreview() {
    val searchResult = SearcherMockDataProvider.createMockTextFile(
        name = "very-long-filename-that-might-need-truncation.txt",
        sizeKB = 1,
        hoursAgo = 1
    )
    PreviewWrapper {
        SelectableFileGrid(
            result = searchResult,
            isSelected = true,
            isSelectionMode = true,
            onClick = {},
            onLongPress = {}
        )
    }
}

@Preview2
@Composable
private fun SelectableFileGridDirectoryPreview() {
    val searchResult = SearcherMockDataProvider.createMockDirectory(
        name = "Documents"
    )
    PreviewWrapper {
        SelectableFileGrid(
            result = searchResult,
            isSelected = false,
            isSelectionMode = false,
            onClick = {},
            onLongPress = {}
        )
    }
}
