package eu.darken.butler.searcher.ui.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.searcher.ui.search.rows.StandardFileIcon

@Composable
fun SelectableFileGrid(
    result: SearchItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        label = "background_color"
    )

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onClick() }
                        )
                    } else {
                        StandardFileIcon(result)
                    }
                }

                Text(
                    text = result.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
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
