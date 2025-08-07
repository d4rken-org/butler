package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Folder
import androidx.compose.material.icons.twotone.Looks3
import androidx.compose.material.icons.twotone.LooksOne
import androidx.compose.material.icons.twotone.LooksTwo
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
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
    selected: Map<Int, Workspace.Info>,
    focusedId: Workspace.Id?,
    design: WorkspaceDesign = WorkspaceDesign(),
    onTabAction: (WorkspaceAction) -> Unit,
    onPaneAssignment: (workspaceId: Workspace.Id, paneIndex: Int) -> Unit,
    onPaneMenuToggle: (Boolean) -> Unit = {},
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
                    val paneIndex = selected.entries.find { it.value.id == ws.id }?.key
                    WorkspaceRailItem(
                        workspace = ws,
                        isSelected = selected.values.any { it.id == ws.id },
                        isFocused = focusedId == ws.id,
                        currentPaneIndex = paneIndex,
                        onTabAction = onTabAction,
                        onPaneAssignment = onPaneAssignment,
                        maxPanes = design.maxPanes,
                        onPaneMenuToggle = onPaneMenuToggle,
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
    maxPanes: Int,
    onPaneMenuToggle: (Boolean) -> Unit,
) {
    var showPaneMenu by remember { mutableStateOf(false) }

    LaunchedEffect(showPaneMenu) {
        onPaneMenuToggle(showPaneMenu)
    }

    Box {
        NavigationRailItem(
            selected = isSelected,
            onClick = { showPaneMenu = true },
            modifier = if (isFocused) {
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp)
                    )
            } else {
                Modifier
            },
            icon = {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Show pane number on the left of the icon
                    currentPaneIndex?.let { paneIdx ->
                        Text(
                            text = "${paneIdx + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(end = 2.dp)
                        )
                    }
                    Icon(
                        imageVector = when (workspace.type) {
                            Workspace.Type.TEMPLATES -> Icons.TwoTone.Workspaces
                            Workspace.Type.EXPLORER -> Icons.TwoTone.Folder
                            Workspace.Type.SEARCHER -> Icons.TwoTone.Search
                            Workspace.Type.EDITOR -> Icons.TwoTone.Edit
                        },
                        contentDescription = workspace.title.get(LocalContext.current),
                        modifier = if (currentPaneIndex != null) {
                            Modifier.padding(start = 2.dp)
                        } else {
                            Modifier
                        }
                    )
                }
            },
            label = {
                Text(
                    text = workspace.title.get(LocalContext.current),
                    style = MaterialTheme.typography.labelSmall.copy(
                        textDecoration = if (isFocused) TextDecoration.Underline else TextDecoration.None
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            },
        )

        DropdownMenu(
            expanded = showPaneMenu,
            onDismissRequest = { showPaneMenu = false },
        ) {
            repeat(maxPanes) { paneIndex ->
                DropdownMenuItem(
                    text = { Text("Pane ${paneIndex + 1}") },
                    leadingIcon = {
                        Icon(
                            imageVector = when (paneIndex) {
                                0 -> Icons.TwoTone.LooksOne
                                1 -> Icons.TwoTone.LooksTwo
                                2 -> Icons.TwoTone.Looks3
                                else -> Icons.TwoTone.LooksOne
                            },
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onPaneAssignment(workspace.id, paneIndex)
                        showPaneMenu = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Close") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.TwoTone.Close,
                        contentDescription = null,
                    )
                },
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
                title = "Explorer 1234".toCaString(),
            ),
            Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.SEARCHER,
                title = "Search 1234".toCaString(),
            ),
            Workspace.Info(
                id = Workspace.Id(),
                type = Workspace.Type.EDITOR,
                title = "Editor 1234".toCaString(),
            ),
        )
        WorkspaceNavigationRail(
            workspaceButtonState = null,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
            workspaces = tabs,
            selected = mapOf(0 to tabs[0], 1 to tabs[1]),
            focusedId = tabs[0].id,
            onTabAction = {},
            onPaneAssignment = { _, _ -> },
            onPaneMenuToggle = {},
        )
    }
}

@Preview2
@Composable
private fun PaneMenuPreview() {
    PreviewWrapper {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = {},
            ) {
                DropdownMenuItem(
                    text = { Text("Pane 1") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.TwoTone.LooksOne,
                            contentDescription = null,
                        )
                    },
                    onClick = {},
                )
                DropdownMenuItem(
                    text = { Text("Pane 2") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.TwoTone.LooksTwo,
                            contentDescription = null,
                        )
                    },
                    onClick = {},
                )
                DropdownMenuItem(
                    text = { Text("Pane 3") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.TwoTone.Looks3,
                            contentDescription = null,
                        )
                    },
                    onClick = {},
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Close") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.TwoTone.Close,
                            contentDescription = null,
                        )
                    },
                    onClick = {},
                )
            }
        }
    }
}