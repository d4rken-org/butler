package eu.darken.butler.searcher.ui.search.items

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.searcher.ui.search.util.getEllipsizedMatchLine

@Composable
fun SelectableFileRow(
    result: SearchItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading content - either checkbox OR icon
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                    )
                } else {
                    TintedAsyncImage(
                        model = result.lookup,
                        contentDescription = result.fileType.name,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // File info
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                // Line 1: File name
                Text(
                    text = result.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Line 2: Size (left) + Date (right)
                val isDirectory = result.fileType == FileType.DIRECTORY
                val size = result.size
                val modifiedAt = result.modifiedAt
                val hasSize = !isDirectory && size != null
                val hasDate = modifiedAt != null

                if (hasSize || hasDate) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (hasSize) {
                            Text(
                                text = formatFileSize(size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Spacer(modifier = Modifier.width(0.dp))
                        }

                        if (hasDate) {
                            Text(
                                text = formatRelativeTime(modifiedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Line 3: Parent path
                Text(
                    text = result.lookup.parent?.userReadablePath?.asComposable() ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )

                // Line 4: Match context (if available)
                val matchDisplay = remember(result.matchContext) {
                    result.matchContext?.let { context ->
                        if (context.lineNumber != null && context.matchedLine != null) {
                            val trimmedLine = context.matchedLine.trim()
                            // Adjust indices for trimmed whitespace
                            val leadingWhitespace = context.matchedLine.length - context.matchedLine.trimStart().length
                            val adjustedStartIndex = (context.startIndex ?: 0) - leadingWhitespace
                            val adjustedEndIndex = (context.endIndex ?: 0) - leadingWhitespace

                            val displayLine = if (adjustedStartIndex in 0..<adjustedEndIndex) {
                                getEllipsizedMatchLine(
                                    line = trimmedLine,
                                    startIndex = adjustedStartIndex,
                                    endIndex = adjustedEndIndex,
                                    maxLength = 60,
                                )
                            } else {
                                trimmedLine
                            }
                            context.lineNumber to displayLine
                        } else null
                    }
                }

                matchDisplay?.let { (lineNumber, displayLine) ->
                    Text(
                        text = stringResource(
                            R.string.searcher_match_line_label,
                            lineNumber,
                            displayLine,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Preview2
@Composable
private fun SelectableFileRowPreview() {
    val searchResult = SearcherMockDataProvider.createMockTextFile(
        name = "example.txt",
        sizeKB = 1,
        hoursAgo = 1,
    )

    PreviewWrapper {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Normal mode
            SelectableFileRow(
                result = searchResult,
                isSelected = false,
                isSelectionMode = false,
                onClick = {},
                onLongPress = {},
            )

            // Selection mode - unselected
            SelectableFileRow(
                result = searchResult,
                isSelected = false,
                isSelectionMode = true,
                onClick = {},
                onLongPress = {},
            )

            // Selection mode - selected
            SelectableFileRow(
                result = searchResult,
                isSelected = true,
                isSelectionMode = true,
                onClick = {},
                onLongPress = {},
            )
        }
    }
}

@Preview2
@Composable
private fun SelectableFileRowWithMatchPreview() {
    val searchResult = SearcherMockDataProvider.createMockSearchResult(
        name = "config.json",
        sizeKB = 12,
        hoursAgo = 3,
        matchedQuery = "timeout",
        matchContext = SearchItem.MatchContext(
            lineNumber = 42,
            matchedLine = "  \"timeout\": 5000,",
            startIndex = 3,
            endIndex = 10,
        ),
    )

    // Long line with match far in the middle - demonstrates ellipsization
    val longLineResult = SearcherMockDataProvider.createMockSearchResult(
        name = "app.config.ts",
        sizeKB = 8,
        hoursAgo = 2,
        matchedQuery = "apiEndpoint",
        matchContext = SearchItem.MatchContext(
            lineNumber = 156,
            matchedLine = "    const configuration = { baseUrl: 'https://example.com', apiEndpoint: '/api/v2/data', timeout: 30000, retries: 3 };",
            startIndex = 61,
            endIndex = 72,
        ),
    )

    PreviewWrapper {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Short match - no ellipsization needed
            SelectableFileRow(
                result = searchResult,
                isSelected = false,
                isSelectionMode = false,
                onClick = {},
                onLongPress = {},
            )

            // Long line with match in middle - shows ellipsization
            SelectableFileRow(
                result = longLineResult,
                isSelected = false,
                isSelectionMode = false,
                onClick = {},
                onLongPress = {},
            )
        }
    }
}
