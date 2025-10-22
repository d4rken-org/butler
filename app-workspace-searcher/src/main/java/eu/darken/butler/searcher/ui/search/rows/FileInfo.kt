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
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.formatRelativeTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@Composable
fun FileInfo(
    modifier: Modifier = Modifier,
    data: FileRowData,
    showPath: Boolean = true,
    showMetadata: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
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
            if (showPath) {
                // Extract parent directory from full path
                val parentDir = data.path.substringBeforeLast('/', "")
                    .substringAfterLast('/', data.path.substringBeforeLast('/'))
                if (parentDir.isNotEmpty()) {
                    append(parentDir)
                }
            }

            if (!isDirectory && data.size != null) {
                if (isNotEmpty()) append(" • ")
                append(formatFileSize(data.size))
            }

            if (data.modifiedAt != null) {
                if (isNotEmpty()) append(" • ")
                append(formatRelativeTime(data.modifiedAt))
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

        // Line 3: Full path (with middle truncation)
        if (showPath && data.path.isNotEmpty()) {
            Text(
                text = data.path,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
        }

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
                data = FileRowData(
                    name = "document.pdf",
                    path = "/storage/emulated/0/Downloads/document.pdf",
                    fileType = FileType.FILE,
                    size = 1024 * 512,
                    modifiedAt = Clock.System.now() - 3600.seconds,
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Long path example (will be truncated)
            FileInfo(
                data = FileRowData(
                    name = "very-long-filename-with-lots-of-text.txt",
                    path = "/storage/emulated/0/Documents/Work/Projects/2024/Q4/Important/Nested/Deeply/very-long-filename-with-lots-of-text.txt",
                    fileType = FileType.FILE,
                    size = 2048 * 1024,
                    modifiedAt = Clock.System.now() - 7200.seconds,
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Directory without path display
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