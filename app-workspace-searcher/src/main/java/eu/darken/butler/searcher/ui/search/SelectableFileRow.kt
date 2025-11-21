package eu.darken.butler.searcher.ui.search

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.ui.search.preview.SearcherMockDataProvider
import eu.darken.butler.searcher.ui.search.rows.FileInfo
import eu.darken.butler.searcher.ui.search.rows.StandardFileIcon

@Composable
fun SelectableFileRow(
    result: SearchItem,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Animation values
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
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading content - either checkbox OR icon
            Box(
                modifier = Modifier.size(40.dp),
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

            Spacer(modifier = Modifier.width(12.dp))

            // File info
            FileInfo(
                result = result,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview2
@Composable
private fun SelectableFileRowPreview() {
    val searchResult = SearcherMockDataProvider.createMockTextFile(
        name = "example.txt",
        sizeKB = 1,
        hoursAgo = 1
    )

    PreviewWrapper {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            // Normal mode
            SelectableFileRow(
                result = searchResult,
                isSelected = false,
                isSelectionMode = false,
                onClick = {},
                onLongPress = {}
            )

            // Selection mode - unselected
            SelectableFileRow(
                result = searchResult,
                isSelected = false,
                isSelectionMode = true,
                onClick = {},
                onLongPress = {}
            )

            // Selection mode - selected
            SelectableFileRow(
                result = searchResult,
                isSelected = true,
                isSelectionMode = true,
                onClick = {},
                onLongPress = {}
            )
        }
    }
}