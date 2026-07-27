package eu.darken.butler.workspace.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.ReceiptLong
import androidx.compose.material.icons.twotone.AccountTree
import androidx.compose.material.icons.twotone.Android
import androidx.compose.material.icons.twotone.Apps
import androidx.compose.material.icons.twotone.BugReport
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.ReportProblem
import androidx.compose.material.icons.twotone.SaveAlt
import androidx.compose.material.icons.twotone.Search
import androidx.compose.material.icons.twotone.Visibility
import androidx.compose.material.icons.twotone.Workspaces
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.R
import eu.darken.butler.workspace.contracts.apps.AppsArguments
import eu.darken.butler.workspace.contracts.bugreport.BugReportArguments
import eu.darken.butler.workspace.contracts.developer.DeveloperArguments
import eu.darken.butler.workspace.contracts.editor.EditorArguments
import eu.darken.butler.workspace.contracts.explorer.ExplorerArguments
import eu.darken.butler.workspace.contracts.history.HistoryArguments
import eu.darken.butler.workspace.contracts.searcher.SearcherArguments
import eu.darken.butler.workspace.contracts.templates.TemplatesArguments

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
        Workspace.Type.HISTORY -> Icons.AutoMirrored.TwoTone.ReceiptLong
        Workspace.Type.BUG_REPORT -> Icons.TwoTone.ReportProblem
        Workspace.Type.VIEWER -> Icons.TwoTone.Visibility
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
        Workspace.Type.HISTORY -> R.string.workspace_history_label.toCaString()
        Workspace.Type.BUG_REPORT -> R.string.workspace_bugreport_label.toCaString()
        Workspace.Type.VIEWER -> R.string.workspace_viewer_label.toCaString()
    }

val Workspace.Type.defaultArguments: Workspace.Arguments?
    get() = when (this) {
        Workspace.Type.TEMPLATES -> TemplatesArguments.Default()
        Workspace.Type.EXPLORER -> ExplorerArguments.Default()
        Workspace.Type.SEARCHER -> SearcherArguments.Default()
        Workspace.Type.EDITOR -> EditorArguments.Default()
        Workspace.Type.APPS -> AppsArguments.Default()
        Workspace.Type.APP_DETAILS -> null
        Workspace.Type.SAVER -> null
        Workspace.Type.DEVELOPER -> DeveloperArguments.Default()
        Workspace.Type.HISTORY -> HistoryArguments.Default()
        Workspace.Type.BUG_REPORT -> BugReportArguments.Default()
        Workspace.Type.VIEWER -> null
    }