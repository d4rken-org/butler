package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel
import eu.darken.butler.workspace.ui.manager.WorkspaceDesign


@Composable
fun WorkspaceNavigationRail(
    modifier: Modifier = Modifier,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
    workspaces: List<Workspace.Info>,
    selected: List<Workspace.Info>,
    focusedId: Workspace.Id?,
    design: WorkspaceDesign = WorkspaceDesign(),
    onTabAction: (WorkspaceAction) -> Unit,
    onPaneAssignment: (workspaceId: Workspace.Id, paneIndex: Int) -> Unit,
    showPaneNumbers: Boolean = false,
) {

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(80.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WorkspaceButton(
                state = workspaceButtonState,
                onAction = onWorkspaceAction,
                onNavToWorkspaceManager = onNavToWorkspaceManager,
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                workspaces.forEach { ws ->
                    val paneIndex = selected.indexOfFirst { it.id == ws.id }
                    WorkspaceRailItem(
                        workspace = ws,
                        isSelected = selected.map { it.id }.contains(ws.id),
                        isFocused = focusedId == ws.id,
                        currentPaneIndex = if (paneIndex >= 0) paneIndex else null,
                        onTabAction = onTabAction,
                        onPaneAssignment = onPaneAssignment,
                        showPaneNumbers = showPaneNumbers,
                        maxPanes = design.maxPanes,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            AddWorkspaceButton(
                onClick = { onTabAction(WorkspaceAction.Create(Workspace.Type.TEMPLATES)) }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WorkspaceRailItem(
    workspace: Workspace.Info,
    isSelected: Boolean,
    isFocused: Boolean,
    currentPaneIndex: Int?,
    onTabAction: (WorkspaceAction) -> Unit,
    onPaneAssignment: (workspaceId: Workspace.Id, paneIndex: Int) -> Unit,
    showPaneNumbers: Boolean,
    maxPanes: Int,
) {
    var showPaneMenu by remember { mutableStateOf(false) }

    Box {
        NavigationRailItem(
            selected = isSelected,
            onClick = { showPaneMenu = true },
            icon = {
                Icon(
                    imageVector = when (workspace.type) {
                        Workspace.Type.TEMPLATES -> Icons.TwoTone.Workspaces
                        Workspace.Type.EXPLORER -> Icons.TwoTone.Folder
                        Workspace.Type.SEARCHER -> Icons.TwoTone.Search
                        Workspace.Type.EDITOR -> Icons.TwoTone.Edit
                    },
                    contentDescription = workspace.title.get(LocalContext.current),
                )
            },
            label = {
                Text(
                    text = workspace.title.get(LocalContext.current),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            },
        )

        if (isFocused) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    ),
            )
        }

        // Show pane number indicator
        currentPaneIndex?.let { paneIdx ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = "${paneIdx + 1}",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        DropdownMenu(
            expanded = showPaneMenu,
            onDismissRequest = { showPaneMenu = false },
        ) {
            repeat(maxPanes) { paneIndex ->
                DropdownMenuItem(
                    text = { Text("Pane ${paneIndex + 1}") },
                    onClick = {
                        onPaneAssignment(workspace.id, paneIndex)
                        showPaneMenu = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Close") },
                onClick = {
                    onTabAction(WorkspaceAction.Close(workspace.id))
                    showPaneMenu = false
                },
            )
        }
    }
}

@Composable
private fun AddWorkspaceButton(
    onClick: () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = "Add Workspace",
        )
    }
}

@Preview2
@Composable
private fun WorkspaceNavigationRailPreview() {
    PreviewWrapper {
        val tabs = listOf(
            Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EXPLORER,
                title = "Explorer".toCaString(),
            ),
            Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.SEARCHER,
                title = "Search".toCaString(),
            ),
            Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EDITOR,
                title = "Editor".toCaString(),
            ),
        )
        WorkspaceNavigationRail(
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
            workspaces = tabs,
            selected = listOf(tabs[0], tabs[1]),
            focusedId = tabs[0].id,
            onTabAction = {},
            onPaneAssignment = { _, _ -> },
        )
    }
}