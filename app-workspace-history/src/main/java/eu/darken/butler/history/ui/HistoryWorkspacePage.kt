package eu.darken.butler.history.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.icon
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.core.operations.history.HistoryFilter
import eu.darken.butler.workspace.core.operations.history.HistoryOutcome
import eu.darken.butler.workspace.ui.floatingbar.BarAnimation
import eu.darken.butler.workspace.ui.common.WorkspacePaddings
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.insets.paneInsets
import eu.darken.butler.workspace.ui.insets.rememberPaneFloatingBarStackState
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign
import eu.darken.butler.workspace.ui.scroll.rememberWorkspaceLazyListState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun HistoryWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: HistoryWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: HistoryWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    val state by vm.state.collectAsState(initial = null)

    state?.let { s ->
        HistoryWorkspacePage(
            workspaceId = id,
            design = design,
            state = s,
            onRemoveOutcome = { vm.toggleOutcome(it) },
            onRemoveKind = { vm.toggleKind(it) },
            onRemovePathScope = { vm.removePathScope(it) },
            onAddFilter = { vm.setAddFilterOpen(true) },
            onClearFilter = { vm.clearFilter() },
            onEntryClick = { vm.showEntryDetails(it) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryWorkspacePage(
    workspaceId: Workspace.Id,
    design: WorkspaceDesign = WorkspaceDesign(),
    state: HistoryWorkspaceViewModel.State,
    onRemoveOutcome: (HistoryOutcome) -> Unit = {},
    onRemoveKind: (Operation.Metadata.Kind) -> Unit = {},
    onRemovePathScope: (String) -> Unit = {},
    onAddFilter: () -> Unit = {},
    onClearFilter: () -> Unit = {},
    onEntryClick: (HistoryEntry) -> Unit = {},
) {
    val paneInsets = design.paneInsets()
    val navBarInset = paneInsets.bottom
    val statusBarInset = paneInsets.top

    val topBarStackState = rememberPaneFloatingBarStackState(
        position = BarPosition.TOP,
        workspaceId = workspaceId,
        defaultSpacing = 8.dp,
        edgePadding = 8.dp,
        contentPadding = 8.dp,
        design = design,
        estimatedContentPadding = 192.dp,
    )

    val listState = rememberWorkspaceLazyListState(workspaceId, slot = HistoryScrollSlots.LIST)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(topBarStackState.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = WorkspacePaddings.ContentHorizontal,
                end = WorkspacePaddings.ContentHorizontal,
                top = topBarStackState.contentPaddingDp(),
                bottom = navBarInset + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(WorkspacePaddings.ListGap),
        ) {
            if (state.entryCount == 0) {
                item(key = "empty") {
                    EmptyState(
                        modifier = Modifier.fillParentMaxSize(),
                        hasAnyHistory = state.hasAnyHistory,
                        onClearFilter = onClearFilter,
                    )
                }
            } else {
                state.groups.forEach { group ->
                    stickyHeader(key = "h-${group.key.name}") {
                        DateGroupHeader(group = group)
                    }
                    items(group.entries, key = { it.id }) { entry ->
                        HistoryEntryRow(entry = entry, onClick = { onEntryClick(entry) })
                    }
                }
            }
        }

        FloatingBarStack(
            state = topBarStackState,
            position = BarPosition.TOP,
            modifier = Modifier.align(Alignment.TopCenter),
            bars = {
                FloatingBar(
                    key = HistoryBarKeys.TOOLBAR,
                    visible = true,
                    scrollBehavior = BarScrollBehavior.CollapseOnScroll,
                    animation = BarAnimation.Slide(),
                ) {
                    HistoryToolbarCard(
                        workspaceId = workspaceId,
                        design = design,
                        filter = state.filter,
                        entryCount = state.entryCount,
                        totalCount = state.totalCount,
                        collapsedFraction = collapsedFraction,
                        onRemoveOutcome = onRemoveOutcome,
                        onRemoveKind = onRemoveKind,
                        onRemovePathScope = onRemovePathScope,
                        onAddFilter = onAddFilter,
                        onClearFilter = onClearFilter,
                    )
                }
            },
        )
    }
}

@Composable
private fun DateGroupHeader(group: HistoryWorkspaceViewModel.DateGroup) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.key.label(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = pluralStringResource(
                    R.plurals.history_group_count,
                    group.entries.size,
                    group.entries.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier = Modifier,
    hasAnyHistory: Boolean,
    onClearFilter: () -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Workspace.Type.HISTORY.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = stringResource(
                    if (hasAnyHistory) {
                        R.string.history_empty_filtered_title
                    } else {
                        R.string.history_empty_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    if (hasAnyHistory) {
                        R.string.history_empty_filtered_subtitle
                    } else {
                        R.string.history_empty_subtitle
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (hasAnyHistory) {
                TextButton(onClick = onClearFilter) {
                    Text(stringResource(R.string.history_empty_filtered_reset_action))
                }
            }
        }
    }
}

@Composable
private fun HistoryWorkspaceViewModel.GroupKey.label(): String = stringResource(
    when (this) {
        HistoryWorkspaceViewModel.GroupKey.TODAY -> R.string.history_group_today
        HistoryWorkspaceViewModel.GroupKey.YESTERDAY -> R.string.history_group_yesterday
        HistoryWorkspaceViewModel.GroupKey.THIS_WEEK -> R.string.history_group_this_week
        HistoryWorkspaceViewModel.GroupKey.THIS_MONTH -> R.string.history_group_this_month
        HistoryWorkspaceViewModel.GroupKey.OLDER -> R.string.history_group_older
    }
)

private fun mockEntry(
    id: String,
    kind: Operation.Metadata.Kind,
    outcome: HistoryOutcome,
    intent: Operation.Metadata.Intent? = null,
    path: String,
    completedAgo: kotlin.time.Duration = 30.seconds,
): HistoryEntry {
    val now = Clock.System.now()
    return HistoryEntry(
        id = id,
        kind = kind,
        intent = intent,
        originType = HistoryEntry.OriginType.EXPLORER,
        originWorkspaceId = "ws",
        title = kind.name,
        description = path,
        summary = null,
        startedAt = now - completedAgo - 5.seconds,
        completedAt = now - completedAgo,
        duration = 5.seconds,
        outcome = outcome,
        errorMessage = null,
        errorClass = null,
        affectedPathsCount = 1,
        partialErrorCount = 0,
        pathsTruncated = false,
        paths = listOf(
            HistoryEntry.PathChange(
                path = path,
                previousPath = null,
                change = Operation.Report.PathChange.Change.ADDED,
            ),
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HistoryWorkspacePageWithEntriesPreview() {
    val workspaceId = Workspace.Id()
    val entries = listOf(
        mockEntry("1", Operation.Metadata.Kind.COPY, HistoryOutcome.COMPLETED, path = "/sdcard/DCIM/photo.jpg", completedAgo = 30.seconds),
        mockEntry("2", Operation.Metadata.Kind.MOVE, HistoryOutcome.COMPLETED, intent = Operation.Metadata.Intent.RENAME, path = "/sdcard/Documents/notes.txt", completedAgo = 5.minutes),
        mockEntry("3", Operation.Metadata.Kind.DELETE, HistoryOutcome.FAILED, path = "/sdcard/Protected/secret.bin", completedAgo = 12.minutes),
        mockEntry("4", Operation.Metadata.Kind.SAVE, HistoryOutcome.PARTIAL, path = "/sdcard/Downloads/list.csv", completedAgo = 45.minutes),
    )
    HistoryWorkspacePage(
        workspaceId = workspaceId,
        state = HistoryWorkspaceViewModel.State(
            id = workspaceId,
            filter = HistoryFilter(),
            groups = listOf(
                HistoryWorkspaceViewModel.DateGroup(
                    HistoryWorkspaceViewModel.GroupKey.TODAY,
                    entries,
                ),
            ),
            entryCount = entries.size,
            totalCount = entries.size,
            hasAnyHistory = true,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HistoryWorkspacePageEmptyPreview() {
    val workspaceId = Workspace.Id()
    HistoryWorkspacePage(
        workspaceId = workspaceId,
        state = HistoryWorkspaceViewModel.State(
            id = workspaceId,
            filter = HistoryFilter(),
            groups = emptyList(),
            entryCount = 0,
            totalCount = 0,
            hasAnyHistory = false,
        ),
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun HistoryWorkspacePageEmptyFilteredPreview() {
    val workspaceId = Workspace.Id()
    HistoryWorkspacePage(
        workspaceId = workspaceId,
        state = HistoryWorkspaceViewModel.State(
            id = workspaceId,
            filter = HistoryFilter(outcomes = setOf(HistoryOutcome.FAILED)),
            groups = emptyList(),
            entryCount = 0,
            totalCount = 200,
            hasAnyHistory = true,
        ),
    )
}
