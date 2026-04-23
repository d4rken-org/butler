package eu.darken.butler.apps.ui.apps.elements

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewWrapper as ComposePreviewWrapper
import androidx.compose.ui.unit.dp
import eu.darken.butler.apps.R
import eu.darken.butler.common.compose.ButlerPreviewWrapper
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceButton

@Composable
fun AppsTopBar(
    workspaceId: Workspace.Id,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    showWorkspaceButton: Boolean,
) {
    TopAppBar(
        modifier = modifier,
        scrollBehavior = scrollBehavior,
        windowInsets = WindowInsets(0, 0, 0, 0),
        title = {
            Text(text = stringResource(R.string.apps_title))
        },
        actions = {
            if (showWorkspaceButton) {
                WorkspaceButton(
                    modifier = Modifier.padding(end = 8.dp),
                    buttonSize = 40.dp,
                    currentWorkspaceId = workspaceId,
                )
            }
        },
    )
}

@Preview2
@ComposePreviewWrapper(ButlerPreviewWrapper::class)
@Composable
private fun AppsTopBarPreview() {
    AppsTopBar(
        workspaceId = Workspace.Id(),
        scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(),
        showWorkspaceButton = true,
    )
}
