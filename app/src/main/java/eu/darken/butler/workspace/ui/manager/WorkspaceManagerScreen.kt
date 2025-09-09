package eu.darken.butler.workspace.ui.manager

import android.os.Parcelable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceBadgeExplanationCard
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceButtonBehaviorCard
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceListItem
import eu.darken.butler.workspace.ui.manager.rows.WorkspaceStatusCard
import kotlinx.parcelize.Parcelize
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

sealed class LazyColumnItemKey : Parcelable {
    @Parcelize
    data object StatusCard : LazyColumnItemKey()

    sealed class Workspace(open val id: eu.darken.butler.workspace.core.Workspace.Id) : LazyColumnItemKey() {
        @Parcelize
        data class Standard(override val id: eu.darken.butler.workspace.core.Workspace.Id) : Workspace(id)

        @Parcelize
        data class Compact(override val id: eu.darken.butler.workspace.core.Workspace.Id) : Workspace(id)

        @Parcelize
        data class Detailed(override val id: eu.darken.butler.workspace.core.Workspace.Id) : Workspace(id)
    }

    sealed class Explanation : LazyColumnItemKey() {
        @Parcelize
        data object BadgeExplanation : Explanation()

        @Parcelize
        data object ButtonBehaviorExplanation : Explanation()

        @Parcelize
        data object TutorialCard : Explanation()

        @Parcelize
        data object TipsCard : Explanation()
    }

    @Parcelize
    data class Custom(val type: String, val id: String) : LazyColumnItemKey()
}

@Composable
fun WorkspaceManagerScreenHost(
    vm: WorkspaceManagerViewModel = hiltViewModel()
) {
    ErrorEventHandler(vm)

    val state by waitForState(vm.state)
    log(vm.tag) { "Compose state: $state" }

    state?.let { currentState ->
        WorkspaceManagerScreen(
            state = currentState,
            onCloseWorkspace = vm::closeWorkspace,
            onReorderWorkspaces = vm::reorderWorkspaces,
            onSelectWorkspace = vm::selectWorkspace,
            onCreateWorkspace = vm::createWorkspace,
            onNavigateBack = vm::navigateBack,
            onNavigateToSettings = vm::navigateToSettings,
            onDismissBadgeExplanation = vm::dismissBadgeExplanation,
            onDismissButtonBehaviorExplanation = vm::dismissButtonBehaviorExplanation,
            onToggleButtonActions = vm::toggleButtonActions,
            onCloseAllWorkspaces = vm::closeAllWorkspaces,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WorkspaceManagerScreen(
    state: WorkspaceManagerViewModel.State,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onReorderWorkspaces: (List<Workspace.Id>) -> Unit,
    onSelectWorkspace: (Workspace.Id) -> Unit,
    onCreateWorkspace: (Workspace.Type) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDismissBadgeExplanation: () -> Unit,
    onDismissButtonBehaviorExplanation: () -> Unit,
    onToggleButtonActions: () -> Unit,
    onCloseAllWorkspaces: () -> Unit,
) {
    // Local state for drag reordering
    var localWorkspaceItems by remember { mutableStateOf(state.workspaces) }
    var isDragging by remember { mutableStateOf(false) }

    // Update local items when state changes and we're not dragging
    if (!isDragging) {
        log("WorkspaceManager") { "Updating local workspace items: ${state.workspaces}" }
        localWorkspaceItems = state.workspaces
    }

    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(
        lazyListState = lazyListState
    ) { from, to ->
        log("WorkspaceManager") { "Reorder from ${from.index} to ${to.index}, fromKey=${from.key}, toKey=${to.key}" }

        val fromKey = from.key as? LazyColumnItemKey
        val toKey = to.key as? LazyColumnItemKey

        when {
            fromKey is LazyColumnItemKey.Workspace && toKey is LazyColumnItemKey.Workspace -> {
                log("WorkspaceManager") { "Reordering workspaces: ${fromKey.id} -> ${toKey.id}" }

                // Find indices in our local workspace list
                val fromWorkspaceIndex = localWorkspaceItems.indexOfFirst { it.id == fromKey.id }
                val toWorkspaceIndex = localWorkspaceItems.indexOfFirst { it.id == toKey.id }

                if (fromWorkspaceIndex == -1 || toWorkspaceIndex == -1) {
                    log("WorkspaceManager") { "Could not find workspace indices: from=$fromWorkspaceIndex, to=$toWorkspaceIndex" }
                    return@rememberReorderableLazyListState
                }

                // Perform the reorder on the workspace list
                val newList = localWorkspaceItems.toMutableList()
                val movedItem = newList.removeAt(fromWorkspaceIndex)
                newList.add(toWorkspaceIndex, movedItem)

                localWorkspaceItems = newList
                log("WorkspaceManager") { "Reordered workspace from $fromWorkspaceIndex to $toWorkspaceIndex" }
            }
            else -> {
                log("WorkspaceManager") { "Ignoring reorder: incompatible item types (from=${fromKey?.javaClass?.simpleName}, to=${toKey?.javaClass?.simpleName})" }
            }
        }
    }

    var showCloseAllDialog by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    // FAB scroll offset
    var fabOffsetY by remember { mutableFloatStateOf(0f) }
    val fabNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0) {
                    // Scrolling up - hide FAB
                    fabOffsetY = (fabOffsetY + available.y).coerceAtLeast(-200f)
                } else if (available.y > 0) {
                    // Scrolling down - show FAB
                    fabOffsetY = (fabOffsetY + available.y).coerceAtMost(0f)
                }
                return Offset.Zero
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .nestedScroll(fabNestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Workspaces,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = stringResource(R.string.workspace_manager_title),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.TwoTone.Settings,
                            contentDescription = stringResource(R.string.workspace_manager_settings_content_desc)
                        )
                    }
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = stringResource(R.string.workspace_manager_dismiss_content_desc)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            WorkspaceManagerFAB(
                workspaceCount = state.workspaceCount,
                fabOffsetY = fabOffsetY,
                onCreateWorkspace = onCreateWorkspace,
                onShowCloseAllDialog = { showCloseAllDialog = true }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            state = lazyListState,
            contentPadding = paddingValues,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status card (show when there are workspaces)
            if (state.workspaceCount > 0) {
                item(key = LazyColumnItemKey.StatusCard) {
                    WorkspaceStatusCard(
                        workspaceCount = state.workspaceCount,
                        operationsCount = state.operationsCount,
                        attentionCount = state.attentionCount
                    )
                }
            }

            if (state.workspaces.isEmpty()) {
                // Empty state as a single item
                item(key = LazyColumnItemKey.Custom("empty_state", "")) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillParentMaxHeight()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.TwoTone.Workspaces,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = stringResource(R.string.workspace_manager_empty_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        Text(
                            text = stringResource(R.string.workspace_manager_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            } else {
                items(
                    items = localWorkspaceItems,
                    key = { workspace -> LazyColumnItemKey.Workspace.Standard(workspace.id) }
                ) { workspace ->
                    ReorderableItem(
                        reorderableLazyListState,
                        key = LazyColumnItemKey.Workspace.Standard(workspace.id)
                    ) { itemIsDragging ->
                        WorkspaceListItem(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .animateItem(),
                            reorderableScope = this@ReorderableItem,
                            workspace = workspace,
                            onClose = { onCloseWorkspace(workspace.id) },
                            onSelect = { onSelectWorkspace(workspace.id) },
                            isDragging = itemIsDragging,
                            onDragStarted = { isDragging = true },
                            onDragStopped = {
                                isDragging = false
                                // Commit the reordered list
                                onReorderWorkspaces(localWorkspaceItems.map { it.id })
                            }
                        )
                    }
                }
            }

            // Button behavior explanation card (show regardless of workspace state)
            if (state.showButtonBehaviorExplanation) {
                item(key = LazyColumnItemKey.Explanation.ButtonBehaviorExplanation) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        WorkspaceButtonBehaviorCard(
                            isButtonFlipped = state.isButtonActionsFlipped,
                            onToggleFlipped = { onToggleButtonActions() },
                            onDismiss = onDismissButtonBehaviorExplanation
                        )
                    }
                }
            }

            // Badge explanation card (show regardless of workspace state)
            if (state.showBadgeExplanation) {
                item(key = LazyColumnItemKey.Explanation.BadgeExplanation) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        WorkspaceBadgeExplanationCard(
                            onDismiss = onDismissBadgeExplanation
                        )
                    }
                }
            }
        }
    }

    // Close all confirmation dialog
    if (showCloseAllDialog) {
        AlertDialog(
            onDismissRequest = { showCloseAllDialog = false },
            title = { Text(stringResource(R.string.workspace_manager_close_all_title)) },
            text = {
                val workspaceString = if (state.workspaceCount == 1) {
                    stringResource(R.string.workspace_manager_close_all_message_singular)
                } else {
                    stringResource(R.string.workspace_manager_close_all_message_plural)
                }
                Text(
                    stringResource(
                        R.string.workspace_manager_close_all_message,
                        state.workspaceCount,
                        workspaceString
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCloseAllWorkspaces()
                        showCloseAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.workspace_manager_close_all_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCloseAllDialog = false }
                ) {
                    Text(stringResource(R.string.general_cancel_action))
                }
            }
        )
    }
}



@Preview2
@Composable
private fun WorkspaceManagerScreenPreview() {
    PreviewWrapper {
        WorkspaceManagerScreen(
            state = WorkspaceManagerViewModel.State(
                workspaces = listOf(
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.TEMPLATES,
                        title = "Templates".toCaString(),
                        subtitle = "Workspace templates"
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.EXPLORER,
                        title = "Explorer".toCaString(),
                        subtitle = "File explorer"
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.SEARCHER,
                        title = "Search".toCaString(),
                        subtitle = "File search"
                    )
                ),
                operationsCount = 3,
                attentionCount = 2
            ),
            onCloseWorkspace = {},
            onReorderWorkspaces = {},
            onSelectWorkspace = {},
            onCreateWorkspace = {},
            onNavigateBack = {},
            onNavigateToSettings = {},
            onDismissBadgeExplanation = {},
            onDismissButtonBehaviorExplanation = {},
            onToggleButtonActions = {},
            onCloseAllWorkspaces = {}
        )
    }
}
