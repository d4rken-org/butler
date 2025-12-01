package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.ClearAll
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Schedule
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.asComposable
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.formatRelativeTime
import eu.darken.butler.common.ui.SwipeToDismissItem
import eu.darken.butler.searcher.R
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearchTarget
import eu.darken.butler.searcher.core.history.SearchHistory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

fun LazyListScope.searchHistorySection(
    searchHistory: List<SearchHistory.SearchHistoryItem>,
    onHistoryItemClick: (SearchHistory.SearchHistoryItem) -> Unit,
    onHistoryItemRemove: (SearchHistory.SearchHistoryItem) -> Unit,
    onShowClearHistoryDialog: () -> Unit,
) {
    // Header with title and clear all button
    item {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.searcher_recent_searches),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Surface(
                modifier = Modifier.clickable { onShowClearHistoryDialog() },
                shape = RoundedCornerShape(6.dp),
                color = Color.Transparent
            ) {
                Text(
                    text = stringResource(R.string.searcher_history_clear_all_action),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }

    // History items
    items(searchHistory, key = { it.id }) { historyItem ->
        SearchHistoryItem(
            historyItem = historyItem,
            onItemClick = { onHistoryItemClick(historyItem) },
            onItemRemove = { onHistoryItemRemove(historyItem) }
        )
    }
}

@Composable
fun SearchHistoryItem(
    historyItem: SearchHistory.SearchHistoryItem,
    onItemClick: () -> Unit,
    onItemRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SwipeToDismissItem(
        modifier = modifier.fillMaxWidth(),
        onDismiss = onItemRemove,
        dismissThreshold = 0.5f,
        backgroundShape = RoundedCornerShape(12.dp),
        dismissContent = {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.searcher_history_remove_action),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onError
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.TwoTone.ClearAll,
                contentDescription = stringResource(R.string.searcher_history_remove_action),
                tint = MaterialTheme.colorScheme.onError
            )
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onItemClick() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Line 1: Search query with icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Search,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = historyItem.baseQuery,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Line 2: Paths with icon
                historyItem.searchQuery?.targets?.takeIf { it.isNotEmpty() }?.let { targets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            targets.forEach { target ->
                                when (target) {
                                    is SearchTarget.Path -> {
                                        Text(
                                            text = target.displayText.asComposable(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Line 3: Results and time with icon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.TwoTone.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatRelativeTime(historyItem.searchedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        historyItem.resultCount?.let { count ->
                            Text(
                                text = "• ${
                                    pluralStringResource(
                                        R.plurals.searcher_history_result_count,
                                        count,
                                        count
                                    )
                                }",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview2
@Composable
private fun SearchHistoryItemPreview() {
    PreviewWrapper {
        SearchHistoryItem(
            historyItem = SearchHistory.SearchHistoryItem(
                id = "preview-1",
                baseQuery = "gradle build",
                searchQuery = SearchQuery.create(
                    query = "gradle build",
                    paths = listOf(
                        LocalPath.build("/storage/emulated/0/Documents"),
                        LocalPath.build("/storage/emulated/0/Download"),
                        LocalPath.build("/storage/emulated/0/Music"),
                    ),
                    caseSensitive = false,
                    wholeWord = false,
                    useRegex = false
                ),
                searchedAt = Clock.System.now() - 30.minutes,
                resultCount = 42
            ),
            onItemClick = {},
            onItemRemove = {}
        )
    }
}

@Preview2
@Composable
private fun SearchHistoryItemNoResultsPreview() {
    PreviewWrapper {
        SearchHistoryItem(
            historyItem = SearchHistory.SearchHistoryItem(
                id = "preview-2",
                baseQuery = "nonexistent",
                searchQuery = SearchQuery.create(
                    query = "nonexistent",
                    paths = listOf(LocalPath.build("/storage/emulated/0")),
                    caseSensitive = true,
                    wholeWord = true,
                    useRegex = false
                ),
                searchedAt = Clock.System.now() - 2.hours,
                resultCount = 0
            ),
            onItemClick = {},
            onItemRemove = {}
        )
    }
}

@Preview2
@Composable
private fun SearchHistorySectionPreview() {
    PreviewWrapper {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            searchHistorySection(
                searchHistory = listOf(
                    SearchHistory.SearchHistoryItem(
                        id = "preview-1",
                        baseQuery = "gradle build",
                        searchQuery = SearchQuery.create(
                            query = "gradle build",
                            paths = listOf(LocalPath.build("/home/user/projects")),
                            caseSensitive = false,
                            wholeWord = false,
                            useRegex = false
                        ),
                        searchedAt = Clock.System.now() - 30.minutes,
                        resultCount = 42
                    ),
                    SearchHistory.SearchHistoryItem(
                        id = "preview-2",
                        baseQuery = "nonexistent",
                        searchQuery = SearchQuery.create(
                            query = "nonexistent",
                            paths = listOf(LocalPath.build("/storage/emulated/0")),
                            caseSensitive = true,
                            wholeWord = true,
                            useRegex = false
                        ),
                        searchedAt = Clock.System.now() - 2.hours,
                        resultCount = 0
                    ),
                    SearchHistory.SearchHistoryItem(
                        id = "preview-3",
                        baseQuery = "import android",
                        searchQuery = SearchQuery.create(
                            query = "import android",
                            paths = listOf(LocalPath.build("/home/user/android-project/src")),
                            caseSensitive = false,
                            wholeWord = false,
                            useRegex = false
                        ),
                        searchedAt = Clock.System.now() - 5.hours,
                        resultCount = 127
                    )
                ),
                onHistoryItemClick = {},
                onHistoryItemRemove = {},
                onShowClearHistoryDialog = {}
            )
        }
    }
}