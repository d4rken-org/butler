package eu.darken.butler.searcher.ui.search.items

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.DriveFileRenameOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.TintedAsyncImage
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.DateTimeStyle
import eu.darken.butler.common.formatDateTime
import eu.darken.butler.common.formatFileSize
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearcherViewStyle
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.searcher.ui.search.util.getEllipsizedMatchLine

/**
 * A modification timestamp, named by the same glyph the Explorer listing uses for the field.
 * The label lives in the content description, because the row has no width to spend on the word.
 */
@Composable
private fun ModifiedDate(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.TwoTone.DriveFileRenameOutline,
            contentDescription = stringResource(R.string.searcher_view_detail_modified_label),
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
fun SelectableFileRow(
    result: SearchItem,
    density: SearcherViewStyle.Density,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = density.rowPadding, vertical = density.rowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading content - either checkbox OR icon
        Box(
            modifier = Modifier.size(density.rowIconSize),
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
                    modifier = Modifier.size(density.rowIconSize),
                )
            }
        }

        Spacer(modifier = Modifier.width(density.rowIconGap))

        // File info
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val isDirectory = result.fileType == FileType.DIRECTORY
            val parentPath = result.lookup.parent?.userReadablePath?.asComposable()
            val sizeText = result.size
                ?.takeIf { !isDirectory }
                ?.let { formatFileSize(it, shortFormat = density.usesShortFileSize) }
            val dateText = result.modifiedAt
                ?.takeIf { density != SearcherViewStyle.Density.COMPACT }
                ?.let {
                    if (density == SearcherViewStyle.Density.DETAILED) {
                        formatDateTime(it, DateTimeStyle.FULL)
                    } else {
                        formatRelativeTime(it)
                    }
                }

            // Line 1: file name, with the size beside it unless a third line carries both
            // figures. The name is the short text on the row, so the size fits next to it
            // without eating into the path below.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = result.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (sizeText != null && !density.showsMetadataOnOwnLine) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            // Line 2 at detailed density: size and date, on the line the path vacates.
            if (density.showsMetadataOnOwnLine && (sizeText != null || dateText != null)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (sizeText != null) {
                        Text(
                            text = sizeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (dateText != null) ModifiedDate(text = dateText)
                }
            }

            // Parent path: beside the date at the tighter densities, on a line of its own at
            // detailed. Results of one search mostly share a prefix, so the tail is what tells
            // them apart and the start is what gets cut.
            val dateBesidePath = dateText?.takeIf { !density.showsMetadataOnOwnLine }

            if (!parentPath.isNullOrEmpty() || dateBesidePath != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = parentPath ?: "",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        modifier = Modifier.weight(1f),
                    )

                    if (dateBesidePath != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        ModifiedDate(text = dateBesidePath)
                    }
                }
            }

            // Line 4: Match context (if available)
            // The excerpt is cut here rather than by maxLines: text discarded up front cannot be
            // recovered by letting the Text wrap.
            val matchDisplay = remember(result.matchContext, density) {
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
                                maxLength = density.matchLineLength,
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
                    maxLines = density.matchLineMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
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
                density = SearcherViewStyle.Density.COMFORTABLE,
                isSelected = false,
                isSelectionMode = false,
                onClick = {},
                onLongPress = {},
            )

            // Selection mode - unselected
            SelectableFileRow(
                result = searchResult,
                density = SearcherViewStyle.Density.COMFORTABLE,
                isSelected = false,
                isSelectionMode = true,
                onClick = {},
                onLongPress = {},
            )

            // Selection mode - selected
            SelectableFileRow(
                result = searchResult,
                density = SearcherViewStyle.Density.COMFORTABLE,
                isSelected = true,
                isSelectionMode = true,
                onClick = {},
                onLongPress = {},
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
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
                density = SearcherViewStyle.Density.COMFORTABLE,
                isSelected = false,
                isSelectionMode = false,
                onClick = {},
                onLongPress = {},
            )

            // Long line with match in middle - shows ellipsization
            SelectableFileRow(
                result = longLineResult,
                density = SearcherViewStyle.Density.COMFORTABLE,
                isSelected = false,
                isSelectionMode = false,
                onClick = {},
                onLongPress = {},
            )
        }
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun SelectableFileRowDensityPreview() {
    val searchResult = SearcherMockDataProvider.createMockSearchResult(
        name = "config.json",
        sizeKB = 12,
        hoursAgo = 3,
        matchedQuery = "timeout",
        matchContext = SearchItem.MatchContext(
            lineNumber = 42,
            matchedLine = "    const configuration = { baseUrl: 'https://example.com', apiEndpoint: '/api/v2/data', timeout: 30000 };",
            startIndex = 88,
            endIndex = 95,
        ),
    )

    PreviewWrapper {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearcherViewStyle.Density.entries.forEach { density ->
                SelectableFileRow(
                    result = searchResult,
                    density = density,
                    isSelected = false,
                    isSelectionMode = false,
                    onClick = {},
                    onLongPress = {},
                )
            }
        }
    }
}
