package eu.darken.butler.history.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign

@Composable
fun HistoryWorkspacePageHost(
    id: Workspace.Id,
    design: WorkspaceDesign,
    vm: HistoryWorkspaceViewModel = hiltViewModel(
        key = id.longTag,
        creationCallback = { factory: HistoryWorkspaceViewModel.Factory -> factory.create(id = id) }
    ),
) {
    ErrorEventHandler(vm)

    val state by vm.state.collectAsState(initial = null)

    var detailEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    var pathScopeOpen by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else 0.dp

    state?.let { s ->
        HistoryWorkspacePage(
            design = design,
            state = s,
            onToggleOutcome = { vm.toggleOutcome(it) },
            onToggleKind = { vm.toggleKind(it) },
            onSetPathScopeRequested = { pathScopeOpen = true },
            onClearPathScope = { vm.setPathScope(null) },
            onEntryClick = { detailEntry = it },
        )

        HistoryEntryDetailsBottomSheet(
            entry = detailEntry,
            bottomInset = navBarInset,
            onDismiss = { detailEntry = null },
        )

        if (pathScopeOpen) {
            PathScopeDialog(
                initialPath = s.filter.pathScope,
                onDismiss = { pathScopeOpen = false },
                onApply = { newScope ->
                    vm.setPathScope(newScope)
                    pathScopeOpen = false
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryWorkspacePage(
    design: WorkspaceDesign = WorkspaceDesign(),
    state: HistoryWorkspaceViewModel.State,
    onToggleOutcome: (eu.darken.butler.workspace.core.operations.history.HistoryOutcome) -> Unit = {},
    onToggleKind: (eu.darken.butler.workspace.core.operations.Operation.Metadata.Kind) -> Unit = {},
    onSetPathScopeRequested: () -> Unit = {},
    onClearPathScope: () -> Unit = {},
    onEntryClick: (HistoryEntry) -> Unit = {},
) {
    val density = LocalDensity.current
    val statusBarInset = if (design.paneEdges.touchesTop) {
        with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    } else 0.dp
    val navBarInset = if (design.paneEdges.touchesBottom) {
        with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    } else 0.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = statusBarInset),
    ) {
        HistoryFilterChips(
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            filter = state.filter,
            onToggleOutcome = onToggleOutcome,
            onToggleKind = onToggleKind,
            onClearPathScope = onClearPathScope,
            onSetPathScope = onSetPathScopeRequested,
        )

        if (state.isEmpty) {
            EmptyState(filtered = !state.filter.isUnfiltered)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = navBarInset + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.groups.forEach { group ->
                    stickyHeader(key = "h-${group.key.name}") {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = group.key.label(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                            )
                        }
                    }
                    items(group.entries, key = { it.id }) { entry ->
                        HistoryEntryRow(entry = entry, onClick = { onEntryClick(entry) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = stringResource(
                    if (filtered) R.string.history_empty_filtered_title else R.string.history_empty_title
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    if (filtered) R.string.history_empty_filtered_subtitle else R.string.history_empty_subtitle
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
