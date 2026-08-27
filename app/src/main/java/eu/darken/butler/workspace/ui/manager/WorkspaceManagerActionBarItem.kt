package eu.darken.butler.workspace.ui.manager

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Close
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material.icons.twotone.SelectAll
import eu.darken.butler.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBarItem

/**
 * The tab manager's floating action bar. Only selection actions exist today; the bar is hosted
 * unconditionally so non-selection entries (sort, view, filter) can join it later, but it stays
 * hidden while this list is empty - [eu.darken.butler.workspace.ui.actions.WorkspaceActionBar]
 * draws a full-width elevated card even when handed nothing.
 */
sealed interface WorkspaceManagerActionBarItem : WorkspaceActionBarItem {

    data class SelectAll(
        override val isEnabled: Boolean,
    ) : WorkspaceManagerActionBarItem {
        override val icon = Icons.TwoTone.SelectAll
        override val label = R.string.workspace_manager_selection_select_all.toCaString()
        override val group = WorkspaceActionBarItem.Group.SECONDARY
    }

    /**
     * Enabled while at least one selected tab is pausable, not while all of them are: the focused
     * tab can never be paused, so requiring every tab to be eligible would leave this permanently
     * dead after a select-all.
     */
    data class PauseSelected(
        override val isEnabled: Boolean,
    ) : WorkspaceManagerActionBarItem {
        override val icon = Icons.TwoTone.PauseCircle
        override val label = R.string.workspace_manager_selection_pause_action.toCaString()
    }

    data object CloseSelected : WorkspaceManagerActionBarItem {
        override val icon = Icons.TwoTone.Close
        override val label = R.string.workspace_manager_selection_close_action.toCaString()
        override val isDestructive = true
    }
}
