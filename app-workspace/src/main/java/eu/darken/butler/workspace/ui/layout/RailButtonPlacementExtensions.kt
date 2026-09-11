package eu.darken.butler.workspace.ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.core.layout.RailButtonPlacement


@Composable
fun RailButtonPlacement.label(): String {
    return when (this) {
        RailButtonPlacement.LEADING -> stringResource(R.string.workspace_settings_rail_button_leading)
        RailButtonPlacement.TRAILING -> stringResource(R.string.workspace_settings_rail_button_trailing)
    }
}

@Composable
fun RailButtonPlacement.description(): String {
    return when (this) {
        RailButtonPlacement.LEADING -> stringResource(R.string.workspace_settings_rail_button_leading_desc)
        RailButtonPlacement.TRAILING -> stringResource(R.string.workspace_settings_rail_button_trailing_desc)
    }
}

/** [landscape] picks the rail edge the glyph draws: bottom in portrait, start in landscape. */
fun RailButtonPlacement.icon(landscape: Boolean = false): ImageVector = when (this) {
    RailButtonPlacement.LEADING -> {
        if (landscape) WorkspacePanelIcons.RailButtonSideLeading else WorkspacePanelIcons.RailButtonBottomLeading
    }
    RailButtonPlacement.TRAILING -> {
        if (landscape) WorkspacePanelIcons.RailButtonSideTrailing else WorkspacePanelIcons.RailButtonBottomTrailing
    }
}
