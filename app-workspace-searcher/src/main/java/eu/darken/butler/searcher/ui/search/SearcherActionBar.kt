package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchResult
import kotlin.time.Clock

@Composable
fun SearcherActionBar(
    selectionState: SearcherSelectionState,
    onAction: (SearcherAction) -> Unit,
    onExitSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!selectionState.isSelectionMode) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Selection count
            Text(
                text = stringResource(R.string.searcher_selection_count, selectionState.selectionCount),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp)
            )

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Select All / Deselect All
                if (selectionState.isAllSelected) {
                    ActionButton(
                        action = SearcherAction.DeselectAll,
                        onClick = onAction
                    )
                } else if (selectionState.selectableResults.isNotEmpty()) {
                    ActionButton(
                        action = SearcherAction.SelectAll,
                        onClick = onAction
                    )
                }

                // Copy
                ActionButton(
                    action = SearcherAction.Copy(selectionState.selectedResults),
                    onClick = onAction
                )

                // Cut
                ActionButton(
                    action = SearcherAction.Cut(selectionState.selectedResults),
                    onClick = onAction
                )

                // Share (if reasonable number of items)
                val shareAction = SearcherAction.Share(selectionState.selectedResults)
                if (shareAction.isVisible) {
                    ActionButton(
                        action = shareAction,
                        onClick = onAction
                    )
                }

                // Delete
                ActionButton(
                    action = SearcherAction.Delete(selectionState.selectedResults),
                    onClick = onAction,
                    isDestructive = true
                )

                // Close selection mode
                IconButton(onClick = onExitSelection) {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = stringResource(R.string.searcher_action_exit_selection),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButton(
    action: SearcherAction,
    onClick: (SearcherAction) -> Unit,
    isDestructive: Boolean = action.isDestructive,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    IconButton(
        onClick = { onClick(action) },
        enabled = action.isEnabled,
        modifier = modifier
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.label.get(context),
            tint = when {
                !action.isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                isDestructive -> MaterialTheme.colorScheme.error
                else -> LocalContentColor.current
            }
        )
    }
}

@Preview2
@Composable
private fun SearcherActionBarPreview() {
    val mockPath1 = LocalPath.build("/storage/emulated/0/file1.txt")
    val mockPath2 = LocalPath.build("/storage/emulated/0/file2.txt")
    val mockLookup1 = LocalPathLookup(
        lookedUp = mockPath1,
        fileType = FileType.FILE,
        size = 1024L,
        modifiedAt = Clock.System.now(),
        target = null
    )
    val mockLookup2 = LocalPathLookup(
        lookedUp = mockPath2,
        fileType = FileType.FILE,
        size = 2048L,
        modifiedAt = Clock.System.now(),
        target = null
    )

    val mockResults = listOf(
        SearchResult(lookup = mockLookup1, matchedQuery = "test"),
        SearchResult(lookup = mockLookup2, matchedQuery = "test")
    )

    PreviewWrapper {
        SearcherActionBar(
            selectionState = SearcherSelectionState(
                selectableResults = mockResults,
                selectedResultIds = setOf(mockPath1.path, mockPath2.path)
            ),
            onAction = {},
            onExitSelection = {}
        )
    }
}