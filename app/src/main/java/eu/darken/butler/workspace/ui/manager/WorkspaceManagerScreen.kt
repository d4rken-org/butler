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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.compose.ScrollPop
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.dialogs.WorkspaceRenameDialog
import eu.darken.butler.workspace.ui.template.QuickCreateItem

@Composable
fun WorkspaceManagerScreen(
    state: WorkspaceManagerViewModel.State,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onReorderWorkspaces: (List<Workspace.Id>) -> Unit,
    onSelectWorkspace: (Workspace.Id) -> Unit,
    onPauseWorkspace: (Workspace.Id) -> Unit,
    onResumeWorkspace: (Workspace.Id) -> Unit,
    onCreateWorkspace: (Workspace.Type) -> Unit,
    onQuickCreate: (QuickCreateItem) -> Unit,
    onNavigateBack: () -> Unit,
    onDismissBadgeExplanation: () -> Unit,
    onCloseAllWorkspaces: () -> Unit,
    onRenameWorkspace: (Workspace.Id, String?) -> Unit = { _, _ -> },
    onTabsClick: () -> Unit = {},
    onOperationsFilterClick: () -> Unit = {},
    onAttentionFilterClick: () -> Unit = {},
) {
    var showCloseAllDialog by remember { mutableStateOf(false) }
    var isFabVisible by remember { mutableStateOf(true) }

    // Held as an id, not a captured item: the item is a snapshot whose automatic title can change,
    // that another surface can rename, or whose tab can close while the dialog is open.
    var renameTargetId by remember { mutableStateOf<Workspace.Id?>(null) }

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
                        quickCreateItems = state.quickCreateItems,
                        onCreateWorkspace = onCreateWorkspace,
                        onQuickCreate = onQuickCreate,
                        onShowCloseAllDialog = { showCloseAllDialog = true },
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
            onPauseWorkspace = onPauseWorkspace,
            onResumeWorkspace = onResumeWorkspace,
            onDismissBadgeExplanation = onDismissBadgeExplanation,
            onRenameWorkspace = { renameTargetId = it },
            onTabsClick = onTabsClick,
            onOperationsFilterClick = onOperationsFilterClick,
            onAttentionFilterClick = onAttentionFilterClick,
        )
    }

    renameTargetId?.let { targetId ->
        val target = state.workspaces.firstOrNull { it.id == targetId }
        if (target == null) {
            renameTargetId = null
        } else {
            key(targetId) {
                WorkspaceRenameDialog(
                    currentCustomTitle = target.customTitle,
                    autoTitle = target.autoTitle.get(LocalContext.current),
                    onConfirm = { newTitle ->
                        renameTargetId = null
                        onRenameWorkspace(targetId, newTitle)
                    },
                    onDismiss = { renameTargetId = null },
                )
            }
        }
    }

    // Close all confirmation dialog
    CloseAllWorkspacesDialog(
        visible = showCloseAllDialog,
        workspaceCount = state.workspaceCount,
        hasUnsavedChanges = state.hasUnsavedChanges,
        onDismiss = { showCloseAllDialog = false },
        onConfirm = {
            onCloseAllWorkspaces()
            showCloseAllDialog = false
        }
    )
}


@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceManagerScreenPreview() {
    val templatesId = Workspace.Id()
    val explorerId = Workspace.Id()
    val searcherId = Workspace.Id()
    WorkspaceManagerScreen(
        state = WorkspaceManagerViewModel.State(
            workspaces = listOf(
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = templatesId,
                    topId = templatesId,
                    type = Workspace.Type.TEMPLATES,
                    title = "New".toCaString(),
                    autoTitle = "New".toCaString(),
                    subtitle = null,
                    isFocused = true,
                    isSelected = true,
                    paneNumber = 0,
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = explorerId,
                    topId = explorerId,
                    type = Workspace.Type.EXPLORER,
                    title = "/storage/emulated/0/Download".toCaString(),
                    autoTitle = "/storage/emulated/0/Download".toCaString(),
                    subtitle = null,
                    isSelected = true,
                    paneNumber = 1,
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = searcherId,
                    topId = searcherId,
                    type = Workspace.Type.SEARCHER,
                    title = "report".toCaString(),
                    autoTitle = "report".toCaString(),
                    subtitle = "SD card".toCaString(),
                    paneNumber = null,
                )
            ),
            operationsCount = 3,
            attentionCount = 2
        ),
        onCloseWorkspace = {},
        onReorderWorkspaces = {},
        onSelectWorkspace = {},
        onPauseWorkspace = {},
        onResumeWorkspace = {},
        onCreateWorkspace = {},
        onQuickCreate = {},
        onNavigateBack = {},
        onDismissBadgeExplanation = {},
        onCloseAllWorkspaces = {}
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceManagerScreenEmptyPreview() {
    WorkspaceManagerScreen(
        state = WorkspaceManagerViewModel.State(
            workspaces = emptyList(),
            operationsCount = 0,
            attentionCount = 0
        ),
        onCloseWorkspace = {},
        onReorderWorkspaces = {},
        onSelectWorkspace = {},
        onPauseWorkspace = {},
        onResumeWorkspace = {},
        onCreateWorkspace = {},
        onQuickCreate = {},
        onNavigateBack = {},
        onDismissBadgeExplanation = {},
        onCloseAllWorkspaces = {}
    )
}
