package eu.darken.butler.searcher.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.R
import eu.darken.butler.workspace.core.Workspace

@Composable
fun SearchStatusCard(
    state: SearcherWorkspaceViewModel.State,
    onCancel: () -> Unit,
    onClear: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Icon - show circular progress when searching, static icon otherwise
                if (state.isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.TwoTone.Search,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Primary message
                    Text(
                        text = if (state.isSearching) {
                            state.searchState.progress?.let { progress ->
                                val folderName = when (val path = progress.currentPath) {
                                    is LocalPath -> path.parent?.name ?: path.name
                                    else -> path.name
                                }
                                stringResource(R.string.searcher_progress_searching_in, folderName)
                            } ?: stringResource(R.string.searcher_progress_searching)
                        } else {
                            when {
                                state.searchState.error != null -> stringResource(R.string.searcher_search_error)
                                state.searchState.results.isNotEmpty() -> stringResource(R.string.searcher_status_results_found, state.searchState.results.size)
                                else -> stringResource(R.string.searcher_status_no_results)
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // Secondary message - always present to maintain card height
                    Text(
                        text = when {
                            state.isSearching -> {
                                state.searchState.progress?.let { progress ->
                                    stringResource(
                                        R.string.searcher_progress_stats,
                                        progress.itemsScanned,
                                        progress.resultsFound
                                    )
                                } ?: stringResource(R.string.searcher_progress_searching)
                            }
                            state.searchState.results.isNotEmpty() -> {
                                stringResource(R.string.searcher_status_search_completed)
                            }
                            else -> {
                                // No results - provide helpful text
                                stringResource(R.string.searcher_placeholder_search)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        minLines = 1, // Ensure minimum height even when empty
                        maxLines = 1 // Keep it single line for consistency
                    )
                }
                
                // Fixed-width container for action area to prevent width changes
                Box(
                    modifier = Modifier.width(72.dp), // Fixed width for consistent layout
                    contentAlignment = Alignment.Center
                ) {
                    // Always show an action button to maintain consistent UI
                    if (state.isSearching) {
                        // Cancel button when searching
                        TextButton(
                            onClick = onCancel,
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.general_cancel_action),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    } else {
                        // Clear button when showing results or no results
                        TextButton(
                            onClick = onClear,
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.searcher_history_clear_confirm_action),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            } // End of Row
        }
    }
}

@Preview2
@Composable
private fun SearchStatusCardPreview() {
    PreviewWrapper {
        SearchStatusCard(
            state = SearcherWorkspaceViewModel.State(
                id = Workspace.Id(),
                searchPath = LocalPath.build("/storage/emulated/0/Documents"),
                searchState = SearcherWorkspaceViewModel.SearchState(
                    status = SearcherWorkspaceViewModel.SearchState.Status.COMPLETED,
                    results = listOf(), // Empty for "no results" state
                    progress = null
                )
            ),
            onCancel = {},
            onClear = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}