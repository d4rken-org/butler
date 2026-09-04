package eu.darken.butler.workspace.ui.manager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace

@Composable
fun AdaptiveWorkspaceManagerContent(
    modifier: Modifier = Modifier,
    state: WorkspaceManagerViewModel.State,
    paddingValues: PaddingValues,
    onCloseWorkspace: (Workspace.Id) -> Unit,
    onReorderWorkspaces: (List<Workspace.Id>) -> Unit,
    onSelectWorkspace: (Workspace.Id) -> Unit,
    onPauseWorkspace: (Workspace.Id) -> Unit,
    onResumeWorkspace: (Workspace.Id) -> Unit,
    onDismissBadgeExplanation: () -> Unit,
    onStartSelection: (Workspace.Id) -> Unit = {},
    onToggleSelection: (Workspace.Id) -> Unit = {},
    onRenameWorkspace: (Workspace.Id) -> Unit = {},
    onNewTabClick: () -> Unit = {},
    onTabsClick: () -> Unit = {},
    onClearSelection: () -> Unit = {},
    onFilterClick: (WorkspaceManagerFilter) -> Unit = {},
    lazyGridState: LazyGridState = rememberLazyGridState(),
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
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
            onPauseWorkspace = onPauseWorkspace,
            onResumeWorkspace = onResumeWorkspace,
            onDismissBadgeExplanation = onDismissBadgeExplanation,
            onStartSelection = onStartSelection,
            onToggleSelection = onToggleSelection,
            onRenameWorkspace = onRenameWorkspace,
            onNewTabClick = onNewTabClick,
            onTabsClick = onTabsClick,
            onClearSelection = onClearSelection,
            onFilterClick = onFilterClick,
            lazyGridState = lazyGridState,
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AdaptiveWorkspaceManagerContentListPreview() {
    val templatesId = Workspace.Id()
    val explorerId = Workspace.Id()
    val searcherId = Workspace.Id()
    Box(modifier = Modifier.size(400.dp, 800.dp)) {
        AdaptiveWorkspaceManagerContent(
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
                        paneNumber = null,
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
            paddingValues = PaddingValues(),
            onCloseWorkspace = {},
            onReorderWorkspaces = {},
            onSelectWorkspace = {},
            onPauseWorkspace = {},
            onResumeWorkspace = {},
            onDismissBadgeExplanation = {}
        )
    }
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AdaptiveWorkspaceManagerContentGridPreview() {
    val templatesId = Workspace.Id()
    val explorerId = Workspace.Id()
    val searcherId = Workspace.Id()
    val editorId = Workspace.Id()
    Box(modifier = Modifier.size(800.dp, 600.dp)) {
        AdaptiveWorkspaceManagerContent(
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
                        title = "Trash".toCaString(),
                        autoTitle = "Trash".toCaString(),
                        subtitle = "Recover deleted files".toCaString(),
                        isVisibleInPane = true,
                        paneNumber = 1,
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = searcherId,
                        topId = searcherId,
                        type = Workspace.Type.SEARCHER,
                        title = "*.log".toCaString(),
                        autoTitle = "*.log".toCaString(),
                        subtitle = "Device storage".toCaString(),
                        paneNumber = null,
                    ),
                    WorkspaceManagerViewModel.WorkspaceItem(
                        id = editorId,
                        topId = editorId,
                        type = Workspace.Type.EDITOR,
                        title = "build.gradle.kts".toCaString(),
                        autoTitle = "build.gradle.kts".toCaString(),
                        subtitle = "/storage/emulated/0/Projects/butler".toCaString(),
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
            onPauseWorkspace = {},
            onResumeWorkspace = {},
            onDismissBadgeExplanation = {}
        )
    }
}



