package eu.darken.butler.workspace.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AccountTree
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.R

val Workspace.Type.icon: ImageVector
    get() = when (this) {
        Workspace.Type.TEMPLATES -> Icons.TwoTone.Workspaces
        Workspace.Type.EXPLORER -> Icons.TwoTone.AccountTree
        Workspace.Type.SEARCHER -> Icons.TwoTone.Search
        Workspace.Type.EDITOR -> Icons.TwoTone.Edit
        Workspace.Type.APPS -> Icons.TwoTone.Apps
        Workspace.Type.APP_DETAILS -> Icons.TwoTone.Android
    }

val Workspace.Type.label: CaString
    get() = when (this) {
        Workspace.Type.TEMPLATES -> R.string.workspace_templates_label.toCaString()
        Workspace.Type.EXPLORER -> R.string.workspace_explorer_label.toCaString()
        Workspace.Type.SEARCHER -> R.string.workspace_searcher_label.toCaString()
        Workspace.Type.EDITOR -> R.string.workspace_editor_label.toCaString()
        Workspace.Type.APPS -> R.string.workspace_apps_label.toCaString()
        Workspace.Type.APP_DETAILS -> R.string.workspace_appdetails_label.toCaString()
    }