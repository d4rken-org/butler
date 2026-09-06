package eu.darken.butler.workspace.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import eu.darken.butler.workspace.ui.manager.WorkspaceButtonDefaults

/**
 * Shared geometry for the workspace toolbar cards.
 *
 * In single-pane layouts the cutout's workspace button floors every toolbar card at the same
 * height. Split-pane and stacked layouts have no cutout, so without this the collapsed card is
 * only as tall as whatever that toolbar happens to contain, and the workspaces disagree.
 */
object WorkspaceToolbarDefaults {

    val MinHeightCollapsed: Dp = WorkspaceButtonDefaults.sizeCompact

    val MinHeightExpanded: Dp = WorkspaceButtonDefaults.sizeDefault

    @Composable
    fun animatedMinHeight(isCollapsed: Boolean): Dp {
        val minHeight by animateDpAsState(
            targetValue = if (isCollapsed) MinHeightCollapsed else MinHeightExpanded,
            label = "toolbarMinHeight",
        )
        return minHeight
    }
}
