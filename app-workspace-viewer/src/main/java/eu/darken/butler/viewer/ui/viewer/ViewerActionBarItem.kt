package eu.darken.butler.viewer.ui.viewer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.OpenInBrowser
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.viewer.R
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBarItem

/**
 * Workspace-level actions for the viewer, shown in the bottom action bar.
 */
sealed interface ViewerActionBarItem : WorkspaceActionBarItem {
    override val icon: ImageVector
    override val label: CaString
    override val isVisible: Boolean get() = true
    override val isEnabled: Boolean get() = true
    override val isDestructive: Boolean get() = false
    override val group: WorkspaceActionBarItem.Group get() = WorkspaceActionBarItem.Group.PRIMARY
    override val badge: Boolean get() = false

    /**
     * Hand the file to another app via the system chooser.
     */
    data object OpenWith : ViewerActionBarItem {
        override val icon = Icons.TwoTone.OpenInBrowser
        override val label = R.string.viewer_open_with_action.toCaString()
    }
}
