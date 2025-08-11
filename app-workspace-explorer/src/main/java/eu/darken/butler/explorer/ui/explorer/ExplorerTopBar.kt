package eu.darken.butler.explorer.ui.explorer

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.WorkspaceAction
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel

@Composable
fun ExplorerTopBar(
    modifier: Modifier = Modifier,
    breadcrumbs: List<ExplorerBreadcrumb>,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onBreadcrumbClick: (ExplorerNavigation) -> Unit,
    onNavigateToPath: (String) -> Unit,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    showWorkspaceButton: Boolean,
    onWorkspaceAction: (WorkspaceAction) -> Unit,
    onNavToWorkspaceManager: () -> Unit,
) {
    TopAppBar(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
            BreadcrumbBar(
                breadcrumbs = breadcrumbs,
                onBreadcrumbClick = onBreadcrumbClick,
                onNavigateToPath = onNavigateToPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 16.dp)
            )
        },
        actions = {
            if (showWorkspaceButton) {
                WorkspaceButton(
                    modifier = Modifier,
                    state = workspaceButtonState,
                    onAction = onWorkspaceAction,
                    onNavToWorkspaceManager = onNavToWorkspaceManager,
                )
            }
        },
    )
}

@Preview2
@Composable
fun ExplorerTopBarPreview() {
    val mockBreadcrumbs = listOf(
        ExplorerBreadcrumb(
            label = "Home".toCaString(),
            icon = Icons.TwoTone.Home,
            target = ExplorerNavigation.Target.Home,
            preferIcon = true,
        ),
        ExplorerBreadcrumb(
            label = "storage".toCaString(),
            target = ExplorerNavigation.Target.Directory(RawPath.build("/storage"))
        ),
        ExplorerBreadcrumb(
            label = "emulated".toCaString(),
            target = ExplorerNavigation.Target.Directory(RawPath.build("/storage/emulated"))
        ),
        ExplorerBreadcrumb(
            label = "0".toCaString(),
            target = ExplorerNavigation.Target.Directory(RawPath.build("/storage/emulated/0"))
        )
    )
    
    PreviewWrapper {
        ExplorerTopBar(
            breadcrumbs = mockBreadcrumbs,
            scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(),
            onBreadcrumbClick = {},
            onNavigateToPath = {},
            workspaceButtonState = null,
            showWorkspaceButton = true,
            onWorkspaceAction = {},
            onNavToWorkspaceManager = {},
        )
    }
}