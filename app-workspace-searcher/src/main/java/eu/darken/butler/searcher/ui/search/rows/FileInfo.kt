package eu.darken.butler.searcher.ui.search.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider

@Composable
fun FileInfo(
    modifier: Modifier = Modifier,
    data: FileRowData,
    showMetadata: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        // Line 1: File name
        Text(
            text = data.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Line 2: Parent directory • Size • Date (combined on one line)
        val isDirectory = data.fileType == FileType.DIRECTORY
        val combinedDetails = buildString {
            val size = data.size
            if (!isDirectory && size != null) {
                if (isNotEmpty()) append(" • ")
                append(formatFileSize(size))
            }

            val modifiedAt = data.modifiedAt
            if (modifiedAt != null) {
                if (isNotEmpty()) append(" • ")
                append(formatRelativeTime(modifiedAt))
            }
        }

        if (combinedDetails.isNotEmpty()) {
            Text(
                text = combinedDetails,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = data.lookup.parent?.userReadablePath?.asComposable() ?: "",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis
        )

        // Line 4: Match context (if available)
        data.matchContext?.let { context ->
            val matchText = buildString {
                context.lineNumber?.let { lineNum ->
                    append("Line $lineNum")
                }
                context.matchedLine?.let { line ->
                    if (isNotEmpty()) append(": ")
                    append(line.trim())
                }
            }

            if (matchText.isNotEmpty()) {
                Text(
                    text = matchText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Short path example
            FileInfo(
                data = SearcherMockDataProvider.createMockPdfFile(
                    name = "document.pdf",
                    sizeMB = 1,
                    hoursAgo = 1
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Long path example (will be truncated)
            FileInfo(
                data = SearcherMockDataProvider.createMockTextFile(
                    name = "very-long-filename-with-lots-of-text.txt",
                    sizeKB = 2048,
                    hoursAgo = 2
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Directory without path display
            FileInfo(
                data = SearcherMockDataProvider.createMockDirectory(
                    name = "Pictures",
                    hoursAgo = 24
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}