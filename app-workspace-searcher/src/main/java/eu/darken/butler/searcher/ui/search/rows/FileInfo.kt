package eu.darken.butler.searcher.ui.search.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Composable
fun FileInfo(
    data: FileRowData,
    showPath: Boolean = true,
    showMetadata: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = data.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (showPath) {
            Text(
                text = data.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        val isDirectory = data.fileType == FileType.DIRECTORY
        val details = buildString {
            if (!isDirectory && data.size != null) {
                append(formatFileSize(data.size))
            }
            if (data.modifiedAt != null) {
                if (isNotEmpty()) append(" • ")
                append(formatRelativeTime(data.modifiedAt))
            }
        }
        
        if (details.isNotEmpty()) {
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showMetadata && data.metadata.isNotEmpty()) {
            val metadataText = data.metadata.entries
                .take(2)
                .joinToString(" • ") { "${it.key}: ${it.value}" }
            
            Text(
                text = metadataText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


@Preview2
@Composable
private fun FileInfoPreview() {
    PreviewWrapper {
        Column {
            FileInfo(
                data = FileRowData(
                    name = "document.pdf",
                    path = "/storage/emulated/0/Downloads/document.pdf",
                    fileType = FileType.FILE,
                    size = 1024 * 512,
                    modifiedAt = Clock.System.now() - 3600.seconds,
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            FileInfo(
                data = FileRowData(
                    name = "Pictures",
                    path = "/storage/emulated/0/Pictures",
                    fileType = FileType.DIRECTORY,
                    modifiedAt = Clock.System.now() - 86400.seconds
                ),
                showPath = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}