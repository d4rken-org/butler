package eu.darken.butler.workspace.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AccountTree
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.SaveAlt
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.apps.core.arguments.AppsArguments
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.developer.core.arguments.DeveloperArguments
import eu.darken.butler.editor.core.arguments.EditorArguments
import eu.darken.butler.explorer.core.arguments.ExplorerArguments
import eu.darken.butler.searcher.core.arguments.SearcherArguments
import eu.darken.butler.templates.core.arguments.TemplatesArguments
import eu.darken.butler.workspace.R

val Workspace.Type.icon: ImageVector
    get() = when (this) {
        Workspace.Type.TEMPLATES -> Icons.TwoTone.Workspaces
        Workspace.Type.EXPLORER -> Icons.TwoTone.AccountTree
        Workspace.Type.SEARCHER -> Icons.TwoTone.Search
        Workspace.Type.EDITOR -> Icons.TwoTone.Edit
        Workspace.Type.APPS -> Icons.TwoTone.Apps
        Workspace.Type.APP_DETAILS -> Icons.TwoTone.Android
        Workspace.Type.SAVER -> Icons.TwoTone.SaveAlt
        Workspace.Type.DEVELOPER -> Icons.TwoTone.BugReport
    }

val Workspace.Type.label: CaString
    get() = when (this) {
        Workspace.Type.TEMPLATES -> R.string.workspace_templates_label.toCaString()
        Workspace.Type.EXPLORER -> R.string.workspace_explorer_label.toCaString()
        Workspace.Type.SEARCHER -> R.string.workspace_searcher_label.toCaString()
        Workspace.Type.EDITOR -> R.string.workspace_editor_label.toCaString()
        Workspace.Type.APPS -> R.string.workspace_apps_label.toCaString()
        Workspace.Type.APP_DETAILS -> R.string.workspace_appdetails_label.toCaString()
        Workspace.Type.SAVER -> R.string.workspace_saver_label.toCaString()
        Workspace.Type.DEVELOPER -> R.string.workspace_developer_label.toCaString()
    }

val Workspace.Type.defaultArguments: Workspace.Arguments
    get() = when (this) {
        Workspace.Type.TEMPLATES -> TemplatesArguments.Default()
        Workspace.Type.EXPLORER -> ExplorerArguments.Default()
        Workspace.Type.SEARCHER -> SearcherArguments.Default()
        Workspace.Type.EDITOR -> EditorArguments.Default()
        Workspace.Type.APPS -> AppsArguments.Default()
        Workspace.Type.APP_DETAILS -> throw IllegalArgumentException("$this requires explicit arguments")
        Workspace.Type.SAVER -> throw IllegalArgumentException("$this requires explicit arguments")
        Workspace.Type.DEVELOPER -> DeveloperArguments.Default()
    }