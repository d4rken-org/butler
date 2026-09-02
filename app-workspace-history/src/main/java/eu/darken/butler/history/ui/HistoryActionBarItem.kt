package eu.darken.butler.history.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.Deselect
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.material.icons.twotone.Share
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.history.R
import eu.darken.butler.workspace.core.operations.history.HistoryEntry
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBarItem
import eu.darken.butler.common.R as CommonR

sealed interface HistoryActionBarItem : WorkspaceActionBarItem {
    override val icon: ImageVector
    override val label: CaString
    override val isVisible: Boolean get() = true
    override val isEnabled: Boolean get() = true
    override val isDestructive: Boolean get() = false
    override val group: WorkspaceActionBarItem.Group get() = WorkspaceActionBarItem.Group.PRIMARY
    override val badge: Boolean get() = false

    /**
     * Carries the ids it would select. The ViewModel's state is a plain `Flow`, so it cannot read
     * the visible entries back synchronously when the action is clicked.
     */
    data class SelectAll(val ids: Set<String>) : HistoryActionBarItem {
        override val icon = Icons.TwoTone.SelectAll
        override val label = R.string.history_action_select_all.toCaString()
    }

    data object DeselectAll : HistoryActionBarItem {
        override val icon = Icons.TwoTone.Deselect
        override val label = R.string.history_action_deselect_all.toCaString()
    }

    data class Share(val entries: List<HistoryEntry>) : HistoryActionBarItem {
        override val icon = Icons.TwoTone.Share
        override val label = CommonR.string.general_share_action.toCaString()
    }

    data class Delete(val entries: List<HistoryEntry>) : HistoryActionBarItem {
        override val icon = Icons.TwoTone.Delete
        override val label = CommonR.string.general_delete_action.toCaString()
        override val isDestructive = true
    }
}

internal fun historyActionsFor(
    selected: List<HistoryEntry>,
    visible: List<HistoryEntry>,
): List<HistoryActionBarItem> {
    if (selected.isEmpty()) return emptyList()
    return buildList {
        if (selected.size < visible.size) {
            add(HistoryActionBarItem.SelectAll(visible.map { it.id }.toSet()))
        }
        add(HistoryActionBarItem.DeselectAll)
        add(HistoryActionBarItem.Share(selected))
        add(HistoryActionBarItem.Delete(selected))
    }
}
