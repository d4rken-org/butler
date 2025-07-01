package eu.darken.butler.workspace.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.templates.ui.WorkspaceTab
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.WorkspaceAction

enum class PaneMode {
    AUTO,
    SINGLE,
    DUAL,
    TRIPLE
}

@Composable
fun WorkspaceNavigationRail(
    modifier: Modifier = Modifier,
    tabs: List<WorkspaceTab>,
    selectedIds: List<Workspace.Id>,
    focusedId: Workspace.Id?,
    paneMode: PaneMode,
    onPaneModeChange: (PaneMode) -> Unit,
    onTabAction: (WorkspaceAction) -> Unit,
    onPaneAssignment: (workspaceId: Workspace.Id, paneIndex: Int) -> Unit,
    showPaneNumbers: Boolean = false,
) {
    val maxPanes = when (paneMode) {
        PaneMode.AUTO -> 3 // Show all options in auto mode
        PaneMode.SINGLE -> 1
        PaneMode.DUAL -> 2
        PaneMode.TRIPLE -> 3
    }
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PaneModeSelector(
                paneMode = paneMode,
                onPaneModeChange = onPaneModeChange,
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.forEach { tab ->
                    WorkspaceRailItem(
                        tab = tab,
                        isSelected = selectedIds.contains(tab.id),
                        isFocused = focusedId == tab.id,
                        onTabAction = onTabAction,
                        onPaneAssignment = onPaneAssignment,
                        showPaneNumbers = showPaneNumbers,
                        maxPanes = maxPanes,
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
private fun PaneModeSelector(
    paneMode: PaneMode,
    onPaneModeChange: (PaneMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = true },
            tonalElevation = 2.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = paneMode.name,
                    style = MaterialTheme.typography.labelMedium,
                )
                Icon(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.CenterEnd),
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            PaneMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.name) },
                    onClick = {
                        onPaneModeChange(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkspaceRailItem(
    tab: WorkspaceTab,
    isSelected: Boolean,
    isFocused: Boolean,
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
                    imageVector = when (tab.type) {
                        Workspace.Type.TEMPLATES -> Icons.TwoTone.Workspaces
                        Workspace.Type.EXPLORER -> Icons.TwoTone.Folder
                        Workspace.Type.SEARCHER -> Icons.TwoTone.Search
                        Workspace.Type.EDITOR -> Icons.TwoTone.Edit
                    },
                    contentDescription = tab.title.get(LocalContext.current),
                )
            },
            label = {
                Text(
                    text = tab.title.get(LocalContext.current),
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

        DropdownMenu(
            expanded = showPaneMenu,
            onDismissRequest = { showPaneMenu = false },
        ) {
            repeat(maxPanes) { paneIndex ->
                DropdownMenuItem(
                    text = { Text("Pane ${paneIndex + 1}") },
                    onClick = {
                        onPaneAssignment(tab.id, paneIndex)
                        showPaneMenu = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Close") },
                onClick = {
                    onTabAction(WorkspaceAction.Close(tab.id))
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
            WorkspaceTab(
                id = Workspace.Id(),
                type = Workspace.Type.EXPLORER,
                title = "Explorer".toCaString(),
            ),
            WorkspaceTab(
                id = Workspace.Id(),
                type = Workspace.Type.SEARCHER,
                title = "Search".toCaString(),
            ),
            WorkspaceTab(
                id = Workspace.Id(),
                type = Workspace.Type.EDITOR,
                title = "Editor".toCaString(),
            ),
        )
        WorkspaceNavigationRail(
            tabs = tabs,
            selectedIds = listOf(tabs[0].id, tabs[1].id),
            focusedId = tabs[0].id,
            paneMode = PaneMode.DUAL,
            onPaneModeChange = {},
            onTabAction = {},
            onPaneAssignment = { _, _ -> },
        )
    }
}