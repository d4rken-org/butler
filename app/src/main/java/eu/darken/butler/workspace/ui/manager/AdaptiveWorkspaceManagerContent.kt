package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace

@Composable
fun AdaptiveWorkspaceManagerContent(
    state: WorkspaceManagerViewModel.State,
    paddingValues: PaddingValues,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onReorderWorkspaces: (List<Workspace.Id>) -> Unit,
    onSelectWorkspace: (Workspace.Id) -> Unit,
    onDismissBadgeExplanation: () -> Unit,
    onTabsClick: () -> Unit = {},
    onOperationsFilterClick: () -> Unit = {},
    onAttentionFilterClick: () -> Unit = {},
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val screenWidth = maxWidth
        
        // Maximum content width for large screens
        val maxContentWidth = 840.dp
        val contentModifier = if (screenWidth > maxContentWidth) {
            Modifier
                .widthIn(max = maxContentWidth)
                .align(Alignment.TopCenter)
        } else {
            Modifier.fillMaxWidth()
        }

        WorkspaceManagerGridLayout(
            modifier = contentModifier,
            state = state,
            paddingValues = paddingValues,
            screenWidth = screenWidth,
            onCloseWorkspace = onCloseWorkspace,
            onReorderWorkspaces = onReorderWorkspaces,
            onSelectWorkspace = onSelectWorkspace,
            onDismissBadgeExplanation = onDismissBadgeExplanation,
            onTabsClick = onTabsClick,
            onOperationsFilterClick = onOperationsFilterClick,
            onAttentionFilterClick = onAttentionFilterClick,
        )
    }
}

@Preview2
@Composable
private fun AdaptiveWorkspaceManagerContentListPreview() {
    PreviewWrapper {
        Box(modifier = Modifier.size(400.dp, 800.dp)) {
            AdaptiveWorkspaceManagerContent(
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
                            paneNumber = null,
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
                paddingValues = PaddingValues(),
                onCloseWorkspace = {},
                onReorderWorkspaces = {},
                onSelectWorkspace = {},
                onDismissBadgeExplanation = {}
            )
        }
    }
}

@Preview2
@Composable
private fun AdaptiveWorkspaceManagerContentGridPreview() {
    PreviewWrapper {
        Box(modifier = Modifier.size(800.dp, 600.dp)) {
            AdaptiveWorkspaceManagerContent(
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
                            subtitle = "File explorer for browsing".toCaString(),
                            isSelected = true,
                            paneNumber = 1,
                        ),
                        WorkspaceManagerViewModel.WorkspaceItem(
                            id = Workspace.Id(),
                            type = Workspace.Type.SEARCHER,
                            title = "Search".toCaString(),
                            subtitle = "Search for files and folders".toCaString(),
                            paneNumber = null,
                        ),
                        WorkspaceManagerViewModel.WorkspaceItem(
                            id = Workspace.Id(),
                            type = Workspace.Type.EDITOR,
                            title = "Editor".toCaString(),
                            subtitle = "Text editor".toCaString(),
                            paneNumber = null,
                        )
                    ),
                    operationsCount = 2,
                    attentionCount = 1
                ),
                paddingValues = PaddingValues(),
                onCloseWorkspace = {},
                onReorderWorkspaces = {},
                onSelectWorkspace = {},
                onDismissBadgeExplanation = {}
            )
        }
    }
}



