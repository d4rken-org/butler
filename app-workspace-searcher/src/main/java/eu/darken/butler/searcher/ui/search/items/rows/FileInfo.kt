package eu.darken.butler.searcher.ui.search.items.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider

@Composable
fun FileInfo(
    modifier: Modifier = Modifier,
    result: SearchItem,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        // Line 1: File name
        Text(
            text = result.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Line 2: Parent directory • Size • Date (combined on one line)
        val isDirectory = result.fileType == FileType.DIRECTORY
        val combinedDetails = buildString {
            val size = result.size
            if (!isDirectory && size != null) {
                if (isNotEmpty()) append(" • ")
                append(formatFileSize(size))
            }

            val modifiedAt = result.modifiedAt
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
            text = result.lookup.parent?.userReadablePath?.asComposable() ?: "",
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis
        )

        // Line 4: Match context (if available)
        result.matchContext?.let { context ->
            if (context.lineNumber != null && context.matchedLine != null) {
                Text(
                    text = stringResource(
                        R.string.searcher_match_line_label,
                        context.lineNumber,
                        context.matchedLine.trim()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
                result = SearcherMockDataProvider.createMockPdfFile(
                    name = "document.pdf",
                    sizeMB = 1,
                    hoursAgo = 1
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Long path example (will be truncated)
            FileInfo(
                result = SearcherMockDataProvider.createMockTextFile(
                    name = "very-long-filename-with-lots-of-text.txt",
                    sizeKB = 2048,
                    hoursAgo = 2
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Directory without path display
            FileInfo(
                result = SearcherMockDataProvider.createMockDirectory(
                    name = "Pictures",
                    hoursAgo = 24
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Content match example
            FileInfo(
                result = SearcherMockDataProvider.createMockSearchResult(
                    name = "config.json",
                    sizeKB = 12,
                    hoursAgo = 3,
                    matchedQuery = "timeout",
                    matchContext = SearchItem.MatchContext(
                        lineNumber = 42,
                        matchedLine = "  \"timeout\": 5000,",
                        startIndex = 2,
                        endIndex = 9
                    )
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}