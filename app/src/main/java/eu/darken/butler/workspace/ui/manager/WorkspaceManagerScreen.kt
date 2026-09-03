package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Workspaces
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
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBar
import eu.darken.butler.workspace.ui.dialogs.ManagerDialog
import eu.darken.butler.workspace.ui.dialogs.WorkspaceCloseConfirmationDialog
import eu.darken.butler.workspace.ui.dialogs.WorkspaceRenameDialog
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.BarScrollBehavior
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import eu.darken.butler.workspace.ui.floatingbar.contentPaddingDp
import eu.darken.butler.workspace.ui.floatingbar.rememberFloatingBarStackState
import eu.darken.butler.workspace.ui.template.QuickCreateItem

@Composable
fun WorkspaceManagerScreen(
    state: WorkspaceManagerViewModel.State,
    /** The close confirmation this overlay hosts while it covers the panes, or null. */
    closeConfirmation: ManagerDialog.WorkspaceTargeted.CloseConfirmation? = null,
    onCloseConfirmationResolve: (Boolean) -> Unit = {},
    onCloseConfirmationGoTo: () -> Unit = {},
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
    onStartSelection: (Workspace.Id) -> Unit = {},
    onToggleSelection: (Workspace.Id) -> Unit = {},
    onSelectAllTabs: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onCloseSelectedWorkspaces: () -> Unit = {},
    onPauseSelectedWorkspaces: () -> Unit = {},
    onRenameWorkspace: (Workspace.Id, String?) -> Unit = { _, _ -> },
    onTabsClick: () -> Unit = {},
    onOperationsFilterClick: () -> Unit = {},
    onAttentionFilterClick: () -> Unit = {},
    lazyGridState: LazyGridState = rememberLazyGridState(),
) {
    var showCloseAllDialog by remember { mutableStateOf(false) }
    var showCloseSelectedDialog by remember { mutableStateOf(false) }
    var showPausePartialDialog by remember { mutableStateOf(false) }
    val barStackState = rememberFloatingBarStackState(BarPosition.BOTTOM)

    // Held as an id, not a captured item: the item is a snapshot whose automatic title can change,
    // that another surface can rename, or whose tab can close while the dialog is open.
    var renameTargetId by remember { mutableStateOf<Workspace.Id?>(null) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
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
                    // The one X leaves whatever mode you are in: selection first, then the manager.
                    // It stays put while selecting because the count chip scrolls away with the
                    // grid, which would otherwise leave no visible way out.
                    IconButton(onClick = { if (state.isSelectionActive) onClearSelection() else onNavigateBack() }) {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = stringResource(
                                if (state.isSelectionActive) {
                                    R.string.workspace_manager_selection_cancel_content_desc
                                } else {
                                    R.string.workspace_manager_dismiss_content_desc
                                }
                            ),
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // Only selection actions exist so far, so the bar is absent the rest of the time rather
        // than drawn empty; sort/view/filter entries will populate the non-selection case later.
        val actions = remember(state.isSelectionActive, state.allSelected, state.selectionPausableCount) {
            if (!state.isSelectionActive) {
                emptyList()
            } else {
                listOf(
                    WorkspaceManagerActionBarItem.SelectAll(isEnabled = !state.allSelected),
                    WorkspaceManagerActionBarItem.PauseSelected(isEnabled = state.selectionPausableCount > 0),
                    WorkspaceManagerActionBarItem.CloseSelected,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AdaptiveWorkspaceManagerContent(
                modifier = Modifier.nestedScroll(barStackState.nestedScrollConnection),
                state = state,
                paddingValues = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding() + barStackState.contentPaddingDp(),
                ),
                onCloseWorkspace = onCloseWorkspace,
                onReorderWorkspaces = onReorderWorkspaces,
                onSelectWorkspace = onSelectWorkspace,
                onPauseWorkspace = onPauseWorkspace,
                onResumeWorkspace = onResumeWorkspace,
                onDismissBadgeExplanation = onDismissBadgeExplanation,
                onStartSelection = onStartSelection,
                onToggleSelection = onToggleSelection,
                onRenameWorkspace = { renameTargetId = it },
                onTabsClick = onTabsClick,
                onClearSelection = onClearSelection,
                onOperationsFilterClick = onOperationsFilterClick,
                onAttentionFilterClick = onAttentionFilterClick,
                lazyGridState = lazyGridState,
            )

            FloatingBarStack(
                position = BarPosition.BOTTOM,
                state = barStackState,
            ) {
                // Declared first, so the action bar below it sits closest to the screen edge.
                FloatingBar(
                    key = "manager_fab",
                    visible = !state.isSelectionActive,
                    scrollBehavior = BarScrollBehavior.HideOnScroll,
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        WorkspaceManagerFAB(
                            workspaceCount = state.workspaceCount,
                            quickCreateItems = state.quickCreateItems,
                            onQuickCreate = onQuickCreate,
                            onShowCloseAllDialog = { showCloseAllDialog = true },
                        )
                    }
                }

                // Static, not HideOnScroll: scrolling a long grid mid-selection must not take the
                // batch actions away with it.
                FloatingBar(
                    key = "manager_actions",
                    visible = actions.isNotEmpty(),
                    scrollBehavior = BarScrollBehavior.Static,
                    revealOn = state.selectedIds,
                ) {
                    WorkspaceActionBar(
                        actions = actions,
                        onActionClick = { action ->
                            when (action) {
                                is WorkspaceManagerActionBarItem.SelectAll -> onSelectAllTabs()
                                is WorkspaceManagerActionBarItem.PauseSelected -> {
                                    // A fully pausable selection just pauses; the dialog exists only
                                    // to report what a partial one will skip.
                                    if (state.selectionPausableCount < state.selectedCount) {
                                        showPausePartialDialog = true
                                    } else {
                                        onPauseSelectedWorkspaces()
                                    }
                                }
                                is WorkspaceManagerActionBarItem.CloseSelected -> {
                                    showCloseSelectedDialog = true
                                }
                            }
                        },
                    )
                }
            }
        }
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

    // Hosted here rather than in the closing tab's pane: this overlay covers every pane, so a
    // pane-hosted confirmation renders underneath it - or off screen entirely when that tab is not
    // the one on display - and the close sits pending with nothing to answer it.
    closeConfirmation?.let { confirmation ->
        key(confirmation.id) {
            WorkspaceCloseConfirmationDialog(
                workspaceTitle = confirmation.workspaceTitle,
                hasUnsavedChanges = confirmation.hasUnsavedChanges,
                unsavedCount = confirmation.unsavedCount,
                onDismiss = { onCloseConfirmationResolve(false) },
                onConfirm = { onCloseConfirmationResolve(true) },
                onGoToWorkspace = onCloseConfirmationGoTo,
            )
        }
    }

    CloseWorkspacesDialog(
        visible = showCloseAllDialog,
        workspaceCount = state.workspaceCount,
        hasUnsavedChanges = state.hasUnsavedChanges,
        onDismiss = { showCloseAllDialog = false },
        onConfirm = {
            onCloseAllWorkspaces()
            showCloseAllDialog = false
        }
    )

    PausePartialSelectionDialog(
        visible = showPausePartialDialog,
        pausableCount = state.selectionPausableCount,
        selectedCount = state.selectedCount,
        onDismiss = { showPausePartialDialog = false },
        onConfirm = {
            onPauseSelectedWorkspaces()
            showPausePartialDialog = false
        }
    )

    CloseWorkspacesDialog(
        visible = showCloseSelectedDialog,
        workspaceCount = state.selectedCount,
        hasUnsavedChanges = state.selectionHasUnsavedChanges,
        isSelection = true,
        onDismiss = { showCloseSelectedDialog = false },
        onConfirm = {
            onCloseSelectedWorkspaces()
            showCloseSelectedDialog = false
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
                    isVisibleInPane = true,
                    paneNumber = 0,
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = explorerId,
                    topId = explorerId,
                    type = Workspace.Type.EXPLORER,
                    title = "/storage/emulated/0/Download".toCaString(),
                    autoTitle = "/storage/emulated/0/Download".toCaString(),
                    subtitle = null,
                    isVisibleInPane = true,
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

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceManagerScreenSelectionPreview() {
    val explorerId = Workspace.Id()
    val searcherId = Workspace.Id()
    val editorId = Workspace.Id()
    WorkspaceManagerScreen(
        state = WorkspaceManagerViewModel.State(
            workspaces = listOf(
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = explorerId,
                    topId = explorerId,
                    type = Workspace.Type.EXPLORER,
                    title = "/storage/emulated/0/Download".toCaString(),
                    autoTitle = "/storage/emulated/0/Download".toCaString(),
                    subtitle = null,
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = searcherId,
                    topId = searcherId,
                    type = Workspace.Type.SEARCHER,
                    title = "report".toCaString(),
                    autoTitle = "report".toCaString(),
                    subtitle = "SD card".toCaString(),
                ),
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = editorId,
                    topId = editorId,
                    type = Workspace.Type.EDITOR,
                    title = "notes.md".toCaString(),
                    autoTitle = "notes.md".toCaString(),
                    subtitle = "/storage/emulated/0/Documents".toCaString(),
                ),
            ),
            selectedIds = setOf(explorerId, editorId),
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
        onCloseAllWorkspaces = {},
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun WorkspaceManagerScreenCloseConfirmationPreview() {
    val editorId = Workspace.Id()
    WorkspaceManagerScreen(
        state = WorkspaceManagerViewModel.State(
            workspaces = listOf(
                WorkspaceManagerViewModel.WorkspaceItem(
                    id = editorId,
                    topId = editorId,
                    type = Workspace.Type.EDITOR,
                    title = "notes.txt".toCaString(),
                    autoTitle = "notes.txt".toCaString(),
                    subtitle = null,
                    isFocused = true,
                    isVisibleInPane = true,
                    paneNumber = 0,
                ),
            ),
            attentionCount = 1,
        ),
        closeConfirmation = ManagerDialog.WorkspaceTargeted.CloseConfirmation(
            id = "close-confirmation",
            targetWorkspaceId = editorId,
            closingWorkspaceId = editorId,
            workspaceTitle = "notes.txt".toCaString(),
            hasUnsavedChanges = true,
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
        onCloseAllWorkspaces = {},
    )
}
