package eu.darken.butler.apps.ui.apps.elements

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Launch
import androidx.compose.material.icons.automirrored.twotone.OpenInNew
import androidx.compose.material.icons.automirrored.twotone.Sort
import androidx.compose.material.icons.automirrored.twotone.ViewList
import androidx.compose.material.icons.twotone.Block
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.DeleteSweep
import androidx.compose.material.icons.twotone.Deselect
import androidx.compose.material.icons.twotone.FilterAlt
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.GetApp
import androidx.compose.material.icons.twotone.GridView
import androidx.compose.material.icons.twotone.Info
import androidx.compose.material.icons.twotone.Refresh
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.material.icons.twotone.Share
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.engine.AppItem
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.contracts.apps.AppsViewStyle
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBarItem

sealed interface AppsActionBarItem : WorkspaceActionBarItem {
    override val icon: ImageVector
    override val label: CaString
    override val isVisible: Boolean get() = true
    override val isEnabled: Boolean get() = true
    override val isDestructive: Boolean get() = false
    override val group: WorkspaceActionBarItem.Group get() = WorkspaceActionBarItem.Group.PRIMARY
    override val badge: Boolean get() = false

    // Selection management actions
    data object SelectAll : AppsActionBarItem {
        override val icon = Icons.TwoTone.SelectAll
        override val label = R.string.apps_action_select_all.toCaString()
    }

    data object DeselectAll : AppsActionBarItem {
        override val icon = Icons.TwoTone.Deselect
        override val label = R.string.apps_action_deselect_all.toCaString()
    }

    data object Refresh : AppsActionBarItem {
        override val icon = Icons.TwoTone.Refresh
        override val label = R.string.apps_action_refresh.toCaString()
    }

    data object Sort : AppsActionBarItem {
        override val icon = Icons.AutoMirrored.TwoTone.Sort
        override val label = R.string.apps_action_sort.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }

    data object Filter : AppsActionBarItem {
        override val icon = Icons.TwoTone.FilterAlt
        override val label = R.string.apps_action_filter.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }

    data class UpdateViewStyle(
        val viewStyle: AppsViewStyle,
    ) : AppsActionBarItem {
        override val icon: ImageVector
            get() = when (viewStyle) {
                is AppsViewStyle.List -> Icons.AutoMirrored.TwoTone.ViewList
                is AppsViewStyle.Grid -> Icons.TwoTone.GridView
            }
        override val label: CaString
            get() = when (viewStyle) {
                is AppsViewStyle.List -> R.string.apps_action_view_list.toCaString()
                is AppsViewStyle.Grid -> R.string.apps_action_view_grid.toCaString()
            }
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }

    // Batch actions (work on multiple apps)
    data class Disable(
        val apps: List<AppItem>,
    ) : AppsActionBarItem {
        override val icon = Icons.TwoTone.Block
        override val label = R.string.apps_action_disable.toCaString()
        override val isDestructive = false
        override val isVisible: Boolean get() = apps.all { it.isEnabled }
    }

    data class Enable(
        val apps: List<AppItem>,
    ) : AppsActionBarItem {
        override val icon = Icons.TwoTone.CheckCircle
        override val label = R.string.apps_action_enable.toCaString()
        override val isVisible: Boolean get() = apps.any { !it.isEnabled }
    }

    data class Uninstall(
        val apps: List<AppItem>,
    ) : AppsActionBarItem {
        override val icon = Icons.TwoTone.Delete
        override val label = R.string.apps_action_uninstall.toCaString()
        override val isDestructive = true
    }

    data class ClearData(
        val apps: List<AppItem>,
    ) : AppsActionBarItem {
        override val icon = Icons.TwoTone.DeleteSweep
        override val label = R.string.apps_action_clear_data.toCaString()
        override val isDestructive = true
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }

    data class ExportApk(
        val apps: List<AppItem>,
    ) : AppsActionBarItem {
        override val icon = Icons.TwoTone.GetApp
        override val label = R.string.apps_action_export_apk.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }

    data class Share(
        val apps: List<AppItem>,
    ) : AppsActionBarItem {
        override val icon = Icons.TwoTone.Share
        override val label = R.string.apps_action_share.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
        override val isVisible: Boolean get() = apps.size <= 5 // Reasonable limit for sharing
    }

    data class OpenInTab(
        val apps: List<AppItem>,
    ) : AppsActionBarItem {
        override val icon = Icons.AutoMirrored.TwoTone.OpenInNew
        override val label = R.string.apps_action_open_in_tab.toCaString()
        override val isVisible: Boolean get() = apps.isNotEmpty()
    }

    // Single-app actions (shown in details dialog or quick actions)
    data class Launch(
        val app: AppItem,
    ) : AppsActionBarItem {
        override val icon = Icons.AutoMirrored.TwoTone.Launch
        override val label = R.string.apps_action_launch.toCaString()
    }

    data class OpenInfo(
        val app: AppItem,
    ) : AppsActionBarItem {
        override val icon = Icons.TwoTone.Info
        override val label = R.string.apps_action_open_info.toCaString()
    }

    data class BrowsePath(
        val app: AppItem,
        val path: APath<*>,
    ) : AppsActionBarItem {
        override val icon = Icons.TwoTone.FolderOpen
        override val label = R.string.apps_action_browse_path.toCaString()
    }
}