package eu.darken.butler.searcher.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Cancel
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Error
import androidx.compose.material.icons.twotone.ExpandLess
import androidx.compose.material.icons.twotone.ExpandMore
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.searcher.core.SearchTarget
import eu.darken.butler.searcher.core.SearcherWorkspace
import eu.darken.butler.searcher.core.engine.SearchEngine

@Composable
fun SearchProgressCard(
    targetProgress: List<SearchEngine.SearchTargetProgress>,
    overallProgress: SearcherWorkspace.State.SearchProgress?,
    searchStatus: SearcherWorkspace.State.SearchStatus,
    onCancel: () -> Unit,
    onErrorClick: (path: String, exception: Throwable) -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    // Auto-collapse when search completes, but expand during active search
    var isExpanded by rememberSaveable(searchStatus) {
        mutableStateOf(
            initiallyExpanded || searchStatus == SearcherWorkspace.State.SearchStatus.SEARCHING
        )
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column {
            // Header: Overall stats + expand button
            SearchProgressHeader(
                pathCount = targetProgress.size,
                totalScanned = overallProgress?.itemsScanned ?: 0,
                totalFound = overallProgress?.resultsFound ?: 0,
                searchStatus = searchStatus,
                failedCount = targetProgress.count { it.status == SearchEngine.SearchTargetProgress.Status.ERROR },
                isExpanded = isExpanded,
                onExpandClick = { isExpanded = !isExpanded },
                onCancelClick = onCancel
            )

            // Expandable per-path list
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )

                    val context = LocalContext.current
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        itemsIndexed(targetProgress) { index, pathProgress ->
                            SearchPathProgressRow(
                                path = pathProgress.target.path.userReadablePath.get(context),
                                itemsScanned = pathProgress.itemsScanned,
                                resultsFound = pathProgress.resultsFound,
                                status = pathProgress.status,
                                exception = pathProgress.exception,
                                onErrorClick = if (pathProgress.exception != null) {
                                    {
                                        onErrorClick(
                                            pathProgress.target.path.userReadablePath.get(context),
                                            pathProgress.exception
                                        )
                                    }
                                } else null
                            )

                            if (index < targetProgress.size - 1) {
                                HorizontalDivider(
                                    thickness = 0.5.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchProgressHeader(
    pathCount: Int,
    totalScanned: Int,
    totalFound: Int,
    searchStatus: SearcherWorkspace.State.SearchStatus,
    failedCount: Int,
    isExpanded: Boolean,
    onExpandClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    val isSearching = searchStatus == SearcherWorkspace.State.SearchStatus.SEARCHING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpandClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon based on search state
        when (searchStatus) {
            SearcherWorkspace.State.SearchStatus.SEARCHING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
            SearcherWorkspace.State.SearchStatus.COMPLETED -> {
                if (failedCount > 0) {
                    Icon(
                        imageVector = Icons.TwoTone.Error,
                        contentDescription = "Completed with errors",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                } else {
                    Icon(
                        imageVector = Icons.TwoTone.CheckCircle,
                        contentDescription = "Completed",
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF4CAF50)
                    )
                }
            }
            SearcherWorkspace.State.SearchStatus.ERROR -> {
                Icon(
                    imageVector = Icons.TwoTone.Error,
                    contentDescription = "Error",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            SearcherWorkspace.State.SearchStatus.CANCELLED -> {
                Icon(
                    imageVector = Icons.TwoTone.Cancel,
                    contentDescription = "Cancelled",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SearcherWorkspace.State.SearchStatus.IDLE -> {
                // Should not show card in IDLE state
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            val actionText = if (isSearching) "Searching" else "Searched"
            val failureText = if (failedCount > 0) " ($failedCount failed)" else ""

            Text(
                text = "$actionText $pathCount location${if (pathCount > 1) "s" else ""}$failureText",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = "$totalScanned scanned • $totalFound found",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onExpandClick) {
            Icon(
                imageVector = if (isExpanded) {
                    Icons.TwoTone.ExpandLess
                } else {
                    Icons.TwoTone.ExpandMore
                },
                contentDescription = if (isExpanded) "Collapse" else "Expand"
            )
        }

        // Only show cancel button while actively searching
        if (isSearching) {
            TextButton(onClick = onCancelClick) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun SearchPathProgressRow(
    path: String,
    itemsScanned: Int,
    resultsFound: Int,
    status: SearchEngine.SearchTargetProgress.Status,
    exception: Throwable?,
    onErrorClick: (() -> Unit)?,
) {
    val rowModifier = if (status == SearchEngine.SearchTargetProgress.Status.ERROR && exception != null && onErrorClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onErrorClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status-based icon
        Icon(
            imageVector = when (status) {
                SearchEngine.SearchTargetProgress.Status.SEARCHING -> Icons.TwoTone.Search
                SearchEngine.SearchTargetProgress.Status.COMPLETED -> Icons.TwoTone.CheckCircle
                SearchEngine.SearchTargetProgress.Status.ERROR -> Icons.TwoTone.Error
                SearchEngine.SearchTargetProgress.Status.CANCELLED -> Icons.TwoTone.Cancel
            },
            contentDescription = when (status) {
                SearchEngine.SearchTargetProgress.Status.SEARCHING -> "Searching"
                SearchEngine.SearchTargetProgress.Status.COMPLETED -> "Completed"
                SearchEngine.SearchTargetProgress.Status.ERROR -> "Error"
                SearchEngine.SearchTargetProgress.Status.CANCELLED -> "Cancelled"
            },
            modifier = Modifier.size(12.dp),
            tint = when (status) {
                SearchEngine.SearchTargetProgress.Status.SEARCHING -> MaterialTheme.colorScheme.primary
                SearchEngine.SearchTargetProgress.Status.COMPLETED -> Color(0xFF4CAF50)
                SearchEngine.SearchTargetProgress.Status.ERROR -> MaterialTheme.colorScheme.error
                SearchEngine.SearchTargetProgress.Status.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = "$itemsScanned scanned • $resultsFound found",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            // Show brief error message inline
            if (status == SearchEngine.SearchTargetProgress.Status.ERROR && exception != null) {
                Text(
                    text = exception.message ?: exception::class.simpleName ?: "Unknown error",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Status text
        Text(
            text = when (status) {
                SearchEngine.SearchTargetProgress.Status.SEARCHING -> "Searching…"
                SearchEngine.SearchTargetProgress.Status.COMPLETED -> "Done"
                SearchEngine.SearchTargetProgress.Status.ERROR -> "Error"
                SearchEngine.SearchTargetProgress.Status.CANCELLED -> "Cancelled"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Helper functions for creating preview test data
private fun createSearchTargetProgress(
    status: SearchEngine.SearchTargetProgress.Status,
    path: String,
    scanned: Int,
    found: Int,
    exception: Throwable? = null
) = SearchEngine.SearchTargetProgress(
    target = SearchTarget.Path.from(LocalPath.build(path)),
    itemsScanned = scanned,
    resultsFound = found,
    status = status,
    exception = exception
)

private fun createSearchProgress(scanned: Int, found: Int) =
    SearcherWorkspace.State.SearchProgress(
        currentPath = LocalPath.build("/sdcard"),
        itemsScanned = scanned,
        resultsFound = found
    )

@Preview2
@Composable
private fun SearchProgressCardMixedPreview() {
    PreviewWrapper {
        SearchProgressCard(
            targetProgress = listOf(
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.SEARCHING,
                    "/sdcard",
                    1542,
                    12
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/storage/emulated/0",
                    823,
                    3
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.SEARCHING,
                    "/mnt/usb_storage",
                    0,
                    0
                ),
            ),
            overallProgress = createSearchProgress(2365, 15),
            searchStatus = SearcherWorkspace.State.SearchStatus.SEARCHING,
            onCancel = {},
            onErrorClick = { _, _ -> },
            initiallyExpanded = true
        )
    }
}

@Preview2
@Composable
private fun SearchProgressCardAllSearchingPreview() {
    PreviewWrapper {
        SearchProgressCard(
            targetProgress = listOf(
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.SEARCHING,
                    "/sdcard",
                    2400,
                    18
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.SEARCHING,
                    "/storage/emulated/0",
                    1200,
                    8
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.SEARCHING,
                    "/mnt/usb_storage",
                    500,
                    2
                ),
            ),
            overallProgress = createSearchProgress(4100, 28),
            searchStatus = SearcherWorkspace.State.SearchStatus.SEARCHING,
            onCancel = {},
            onErrorClick = { _, _ -> },
            initiallyExpanded = true
        )
    }
}

@Preview2
@Composable
private fun SearchProgressCardWithErrorsPreview() {
    PreviewWrapper {
        SearchProgressCard(
            targetProgress = listOf(
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/sdcard",
                    3200,
                    25
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.ERROR,
                    "/storage/usb",
                    150,
                    0,
                    exception = SecurityException("Permission denied: READ_EXTERNAL_STORAGE required")
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.SEARCHING,
                    "/mnt/external",
                    800,
                    5
                ),
            ),
            overallProgress = createSearchProgress(4150, 30),
            searchStatus = SearcherWorkspace.State.SearchStatus.SEARCHING,
            onCancel = {},
            onErrorClick = { _, _ -> },
            initiallyExpanded = true
        )
    }
}

@Preview2
@Composable
private fun SearchProgressCardCancelledPreview() {
    PreviewWrapper {
        SearchProgressCard(
            targetProgress = listOf(
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/sdcard/Documents",
                    1500,
                    12
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.CANCELLED,
                    "/storage/emulated/0",
                    400,
                    3
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.CANCELLED,
                    "/mnt/usb_storage",
                    0,
                    0
                ),
            ),
            overallProgress = createSearchProgress(1900, 15),
            searchStatus = SearcherWorkspace.State.SearchStatus.CANCELLED,
            onCancel = {},
            onErrorClick = { _, _ -> },
            initiallyExpanded = true
        )
    }
}

@Preview2
@Composable
private fun SearchProgressCardSinglePathPreview() {
    PreviewWrapper {
        SearchProgressCard(
            targetProgress = listOf(
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.SEARCHING,
                    "/sdcard",
                    5420,
                    42
                ),
            ),
            overallProgress = createSearchProgress(5420, 42),
            searchStatus = SearcherWorkspace.State.SearchStatus.SEARCHING,
            onCancel = {},
            onErrorClick = { _, _ -> },
            initiallyExpanded = true
        )
    }
}

@Preview2
@Composable
private fun SearchProgressCardCompletedPreview() {
    PreviewWrapper {
        SearchProgressCard(
            targetProgress = listOf(
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/sdcard",
                    3200,
                    25
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/storage/emulated/0",
                    1850,
                    14
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/mnt/usb_storage",
                    920,
                    6
                ),
            ),
            overallProgress = createSearchProgress(5970, 45),
            searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
            onCancel = {},
            onErrorClick = { _, _ -> },
            initiallyExpanded = true
        )
    }
}

@Preview2
@Composable
private fun SearchProgressCardCompletedWithErrorsPreview() {
    PreviewWrapper {
        SearchProgressCard(
            targetProgress = listOf(
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/sdcard",
                    3200,
                    25
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.ERROR,
                    "/storage/usb",
                    150,
                    0,
                    exception = java.io.IOException("I/O error: Device not accessible")
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/mnt/external",
                    1850,
                    14
                ),
            ),
            overallProgress = createSearchProgress(5200, 39),
            searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
            onCancel = {},
            onErrorClick = { _, _ -> },
            initiallyExpanded = true
        )
    }
}

@Preview2
@Composable
private fun SearchProgressCardEmptyPreview() {
    PreviewWrapper {
        SearchProgressCard(
            targetProgress = emptyList(),
            overallProgress = null,
            searchStatus = SearcherWorkspace.State.SearchStatus.SEARCHING,
            onCancel = {},
            onErrorClick = { _, _ -> },
            initiallyExpanded = true
        )
    }
}

@Preview2
@Composable
private fun SearchProgressCardCompletedCollapsedPreview() {
    PreviewWrapper {
        SearchProgressCard(
            targetProgress = listOf(
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/sdcard",
                    3200,
                    25
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/storage/emulated/0",
                    1850,
                    14
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/mnt/usb_storage",
                    920,
                    6
                ),
            ),
            overallProgress = createSearchProgress(5970, 45),
            searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
            onCancel = {},
            onErrorClick = { _, _ -> },
            initiallyExpanded = false
        )
    }
}

@Preview2
@Composable
private fun SearchProgressCardCompletedWithErrorsCollapsedPreview() {
    PreviewWrapper {
        SearchProgressCard(
            targetProgress = listOf(
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/sdcard",
                    3200,
                    25
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.ERROR,
                    "/storage/usb",
                    150,
                    0,
                    exception = java.io.IOException("I/O error: Device not accessible")
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.COMPLETED,
                    "/mnt/external",
                    1850,
                    14
                ),
            ),
            overallProgress = createSearchProgress(5200, 39),
            searchStatus = SearcherWorkspace.State.SearchStatus.COMPLETED,
            onCancel = {},
            onErrorClick = { _, _ -> },
            initiallyExpanded = false
        )
    }
}

@Preview2
@Composable
private fun SearchProgressCardSearchingCollapsedPreview() {
    PreviewWrapper {
        SearchProgressCard(
            targetProgress = listOf(
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.SEARCHING,
                    "/sdcard",
                    2400,
                    18
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.SEARCHING,
                    "/storage/emulated/0",
                    1200,
                    8
                ),
                createSearchTargetProgress(
                    SearchEngine.SearchTargetProgress.Status.SEARCHING,
                    "/mnt/usb_storage",
                    500,
                    2
                ),
            ),
            overallProgress = createSearchProgress(4100, 28),
            searchStatus = SearcherWorkspace.State.SearchStatus.SEARCHING,
            onCancel = {},
            onErrorClick = { _, _ -> },
            initiallyExpanded = false
        )
    }
}
