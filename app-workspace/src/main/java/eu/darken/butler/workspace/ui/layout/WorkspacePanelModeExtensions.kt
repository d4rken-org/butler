package eu.darken.butler.workspace.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.layout.WorkspacePanelMode


@Composable
fun WorkspacePanelMode.label(): String {
    return when (this) {
        WorkspacePanelMode.AUTO -> stringResource(R.string.workspace_settings_layout_mode_auto)
        WorkspacePanelMode.SINGLE -> stringResource(R.string.workspace_settings_layout_mode_single)
        WorkspacePanelMode.DUAL_VERTICAL -> stringResource(R.string.workspace_settings_layout_mode_dual_vertical)
        WorkspacePanelMode.DUAL_HORIZONTAL -> stringResource(R.string.workspace_settings_layout_mode_dual_horizontal)
        WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT -> stringResource(R.string.workspace_settings_layout_mode_triple_sidebar_left)
        WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT -> stringResource(R.string.workspace_settings_layout_mode_triple_sidebar_right)
        WorkspacePanelMode.QUAD_GRID -> stringResource(R.string.workspace_settings_layout_mode_quad_grid)
    }
}

@Composable
fun WorkspacePanelMode.description(): String {
    return when (this) {
        WorkspacePanelMode.AUTO -> stringResource(R.string.workspace_settings_layout_mode_auto_desc)
        WorkspacePanelMode.SINGLE -> stringResource(R.string.workspace_settings_layout_mode_single_desc)
        WorkspacePanelMode.DUAL_VERTICAL -> stringResource(R.string.workspace_settings_layout_mode_dual_vertical_desc)
        WorkspacePanelMode.DUAL_HORIZONTAL -> stringResource(R.string.workspace_settings_layout_mode_dual_horizontal_desc)
        WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT -> stringResource(R.string.workspace_settings_layout_mode_triple_sidebar_left_desc)
        WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT -> stringResource(R.string.workspace_settings_layout_mode_triple_sidebar_right_desc)
        WorkspacePanelMode.QUAD_GRID -> stringResource(R.string.workspace_settings_layout_mode_quad_grid_desc)
    }
}

fun WorkspacePanelMode.icon(): ImageVector = when (this) {
    WorkspacePanelMode.AUTO -> WorkspacePanelIcons.Auto
    WorkspacePanelMode.SINGLE -> WorkspacePanelIcons.Single
    WorkspacePanelMode.DUAL_VERTICAL -> WorkspacePanelIcons.DualVertical
    WorkspacePanelMode.DUAL_HORIZONTAL -> WorkspacePanelIcons.DualHorizontal
    WorkspacePanelMode.TRIPLE_SIDEBAR_LEFT -> WorkspacePanelIcons.TripleSidebarLeft
    WorkspacePanelMode.TRIPLE_SIDEBAR_RIGHT -> WorkspacePanelIcons.TripleSidebarRight
    WorkspacePanelMode.QUAD_GRID -> WorkspacePanelIcons.QuadGrid
}