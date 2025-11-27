package eu.darken.butler.saver.ui.saver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.saver.R
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.ui.manager.WorkspaceActionHandler
import eu.darken.butler.workspace.ui.manager.WorkspaceButton
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonViewModel

@Composable
internal fun SaverHeader(
    modifier: Modifier = Modifier,
    subtitle: String?,
    workspaceButtonState: WorkspaceButtonViewModel.State?,
    workspaceId: Workspace.Id,
    workspaceActionHandler: WorkspaceActionHandler?,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.saver_workspace_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            subtitle?.let {
                Text(
                    text = stringResource(R.string.saver_shared_from, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WorkspaceButton(
            state = workspaceButtonState,
            currentWorkspaceId = workspaceId,
            workspaceActionHandler = workspaceActionHandler,
        )
    }
}

@Preview2
@Composable
private fun SaverHeaderPreview() {
    PreviewWrapper {
        SaverHeader(
            subtitle = "org.telegram.messenger",
            workspaceButtonState = null,
            workspaceId = Workspace.Id(),
            workspaceActionHandler = null,
        )
    }
}

@Preview2
@Composable
private fun SaverHeaderNoSubtitlePreview() {
    PreviewWrapper {
        SaverHeader(
            subtitle = null,
            workspaceButtonState = null,
            workspaceId = Workspace.Id(),
            workspaceActionHandler = null,
        )
    }
}
