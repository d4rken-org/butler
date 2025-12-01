package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.KeyboardArrowDown
import androidx.compose.material.icons.twotone.KeyboardArrowUp
import androidx.compose.material.icons.twotone.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.R
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.workspace.ui.scroll.BottomBarScrollState

@Composable
fun EditorSearchBar(
    modifier: Modifier = Modifier,
    scrollState: BottomBarScrollState,
    searchQuery: String,
    searchResults: List<SearchResult>,
    currentIndex: Int,
    caseSensitive: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onCaseSensitiveToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val hasResults = searchResults.isNotEmpty()
    val hasPrevious = hasResults && currentIndex > 0
    val hasNext = hasResults && currentIndex < searchResults.size - 1

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                // Binary snap behavior: fully visible or fully hidden
                alpha = if (scrollState.collapsedFraction > 0.1f) 0f else 1f
                translationY = if (scrollState.collapsedFraction > 0.1f) 64.dp.toPx() else 0f
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Search text field
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (hasNext) {
                            onNext()
                        }
                    }
                ),
                decorationBox = { innerTextField ->
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = stringResource(R.string.editor_search_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    innerTextField()
                }
            )

            // Result counter
            if (hasResults) {
                Text(
                    text = stringResource(
                        R.string.editor_search_results_format,
                        currentIndex + 1,
                        searchResults.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Previous result button
            IconButton(
                onClick = onPrevious,
                enabled = hasPrevious,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.editor_search_previous),
                    tint = if (hasPrevious) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            }

            // Next result button
            IconButton(
                onClick = onNext,
                enabled = hasNext,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.editor_search_next),
                    tint = if (hasNext) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            }

            // Case sensitive toggle
            IconButton(
                onClick = onCaseSensitiveToggle,
                modifier = Modifier.size(40.dp),
                colors = if (caseSensitive) {
                    IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    IconButtonDefaults.iconButtonColors()
                }
            ) {
                Icon(
                    imageVector = Icons.TwoTone.TextFields,
                    contentDescription = stringResource(R.string.editor_search_case_sensitive_label),
                )
            }

            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Close,
                    contentDescription = stringResource(eu.darken.butler.common.R.string.general_close_action),
                )
            }
        }
    }
}

@Preview2
@Composable
private fun EditorSearchBarEmptyPreview() {
    PreviewWrapper {
        EditorSearchBar(
            scrollState = BottomBarScrollState(),
            searchQuery = "",
            searchResults = emptyList(),
            currentIndex = 0,
            caseSensitive = false,
            onSearchQueryChange = {},
            onCaseSensitiveToggle = {},
            onPrevious = {},
            onNext = {},
            onClose = {},
        )
    }
}

@Preview2
@Composable
private fun EditorSearchBarWithQueryPreview() {
    PreviewWrapper {
        EditorSearchBar(
            scrollState = BottomBarScrollState(),
            searchQuery = "test",
            searchResults = emptyList(),
            currentIndex = 0,
            caseSensitive = false,
            onSearchQueryChange = {},
            onCaseSensitiveToggle = {},
            onPrevious = {},
            onNext = {},
            onClose = {},
        )
    }
}

@Preview2
@Composable
private fun EditorSearchBarWithResultsPreview() {
    PreviewWrapper {
        EditorSearchBar(
            scrollState = BottomBarScrollState(),
            searchQuery = "test",
            searchResults = List(10) {
                SearchResult(
                    position = eu.darken.butler.editor.core.engine.TextPosition.ZERO,
                    matchText = "test",
                    chunkId = eu.darken.butler.editor.core.engine.TextChunk.ChunkId("0")
                )
            },
            currentIndex = 2,
            caseSensitive = false,
            onSearchQueryChange = {},
            onCaseSensitiveToggle = {},
            onPrevious = {},
            onNext = {},
            onClose = {},
        )
    }
}

@Preview2
@Composable
private fun EditorSearchBarCaseSensitivePreview() {
    PreviewWrapper {
        EditorSearchBar(
            scrollState = BottomBarScrollState(),
            searchQuery = "Test",
            searchResults = List(5) {
                SearchResult(
                    position = eu.darken.butler.editor.core.engine.TextPosition.ZERO,
                    matchText = "Test",
                    chunkId = eu.darken.butler.editor.core.engine.TextChunk.ChunkId("0")
                )
            },
            currentIndex = 0,
            caseSensitive = true,
            onSearchQueryChange = {},
            onCaseSensitiveToggle = {},
            onPrevious = {},
            onNext = {},
            onClose = {},
        )
    }
}
