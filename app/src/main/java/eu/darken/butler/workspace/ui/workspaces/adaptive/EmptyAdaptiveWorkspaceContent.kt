package eu.darken.butler.workspace.ui.workspaces.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import eu.darken.butler.R
import eu.darken.butler.common.compose.Preview2
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.common.EnhancedEmptyWorkspaceState

@Composable
internal fun EmptyAdaptiveWorkspaceContent(
    modifier: Modifier = Modifier,
    paneNumber: Int,
) {
    val tips = listOf(
        stringResource(R.string.workspace_adaptive_tip_multiple_panes),
        stringResource(R.string.workspace_adaptive_tip_different_folders),
        stringResource(R.string.workspace_adaptive_tip_switch_layouts),
        stringResource(R.string.workspace_adaptive_tip_copy_between_panes),
    )

    EnhancedEmptyWorkspaceState(
        modifier = modifier,
        title = stringResource(eu.darken.butler.common.R.string.app_name),
        subtitle = "Pane $paneNumber is ready for content",
        tips = tips,
        actions = emptyList(), // No action buttons - keep it simple
        showAnimatedIcon = true,
        contentAlignment = Alignment.Center
    )
}

@Preview2
@Composable
private fun EmptyWorkspaceContentPreview() {
    PreviewWrapper {
        EmptyAdaptiveWorkspaceContent(
            paneNumber = 2,
        )
    }
}
