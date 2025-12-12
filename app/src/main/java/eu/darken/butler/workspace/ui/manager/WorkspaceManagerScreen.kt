package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import eu.darken.butler.common.compose.ScrollPop
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.error.ErrorEventHandler
import eu.darken.butler.common.navigation.NavigationEventHandler
import eu.darken.butler.common.ui.waitForState
import eu.darken.butler.workspace.core.Workspace

@Composable
fun WorkspaceManagerScreenHost(
    vm: WorkspaceManagerViewModel = hiltViewModel()
) {
    // Invalidate preview cache every time the screen appears
    LaunchedEffect(Unit) {
        vm.onScreenAppeared()
    }

    ErrorEventHandler(vm)
    NavigationEventHandler(vm)

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
            onDismissBadgeExplanation = vm::dismissBadgeExplanation,
            onDismissLongPressHint = vm::dismissLongPressHint,
            onCloseAllWorkspaces = vm::closeAllWorkspaces,
            onTabsClick = vm::clearFilters,
            onOperationsFilterClick = vm::toggleOperationsFilter,
            onAttentionFilterClick = vm::toggleAttentionFilter,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceManagerScreen(
    state: WorkspaceManagerViewModel.State,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onReorderWorkspaces: (List<Workspace.Id>) -> Unit,
    onSelectWorkspace: (Workspace.Id) -> Unit,
    onCreateWorkspace: (Workspace.Type) -> Unit,
    onNavigateBack: () -> Unit,
    onDismissBadgeExplanation: () -> Unit,
    onDismissLongPressHint: () -> Unit,
    onCloseAllWorkspaces: () -> Unit,
    onTabsClick: () -> Unit = {},
    onOperationsFilterClick: () -> Unit = {},
    onAttentionFilterClick: () -> Unit = {},
) {
    var showCloseAllDialog by remember { mutableStateOf(false) }
    var isFabVisible by remember { mutableStateOf(true) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    
    // FAB visibility scroll connection
    val fabScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (kotlin.math.abs(delta) > 5f) { // Threshold to avoid jitter
                    when {
                        delta < 0 -> isFabVisible = false // Scrolling down - hide FAB
                        delta > 0 -> isFabVisible = true  // Scrolling up - show FAB
                    }
                }
                return Offset.Zero
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .nestedScroll(fabScrollConnection),
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
            Box(modifier = Modifier.padding(16.dp)) {
                ScrollPop(isVisible = isFabVisible) {
                    WorkspaceManagerFAB(
                        workspaceCount = state.workspaceCount,
                        onCreateWorkspace = onCreateWorkspace,
                        onShowCloseAllDialog = { showCloseAllDialog = true },
                        showLongPressHint = state.showLongPressHint,
                        onDismissLongPressHint = onDismissLongPressHint
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AdaptiveWorkspaceManagerContent(
            state = state,
            paddingValues = paddingValues,
            onCloseWorkspace = onCloseWorkspace,
            onReorderWorkspaces = onReorderWorkspaces,
            onSelectWorkspace = onSelectWorkspace,
            onDismissBadgeExplanation = onDismissBadgeExplanation,
            onTabsClick = onTabsClick,
            onOperationsFilterClick = onOperationsFilterClick,
            onAttentionFilterClick = onAttentionFilterClick,
        )
    }

    // Close all confirmation dialog
    CloseAllWorkspacesDialog(
        visible = showCloseAllDialog,
        workspaceCount = state.workspaceCount,
        onDismiss = { showCloseAllDialog = false },
        onConfirm = {
            onCloseAllWorkspaces()
            showCloseAllDialog = false
        }
    )
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
                        subtitle = "Workspace templates".toCaString(),
                        isFocused = true,
                        isSelected = true,
                        paneNumber = 0,
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.EXPLORER,
                        title = "Explorer".toCaString(),
                        subtitle = "File explorer".toCaString(),
                        isSelected = true,
                        paneNumber = 1,
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = Workspace.Id(),
                        type = Workspace.Type.SEARCHER,
                        title = "Search".toCaString(),
                        subtitle = "File search".toCaString(),
                        paneNumber = null,
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
            onDismissBadgeExplanation = {},
            onDismissLongPressHint = {},
            onCloseAllWorkspaces = {}
        )
    }
}

@Preview2
@Composable
private fun WorkspaceManagerScreenEmptyPreview() {
    PreviewWrapper {
        WorkspaceManagerScreen(
            state = WorkspaceManagerViewModel.State(
                workspaces = emptyList(),
                operationsCount = 0,
                attentionCount = 0
            ),
            onCloseWorkspace = {},
            onReorderWorkspaces = {},
            onSelectWorkspace = {},
            onCreateWorkspace = {},
            onNavigateBack = {},
            onDismissBadgeExplanation = {},
            onDismissLongPressHint = {},
            onCloseAllWorkspaces = {}
        )
    }
}
