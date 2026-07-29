package eu.darken.butler.apps.ui.details.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Block
import androidx.compose.material.icons.twotone.CheckCircle
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.apps.R
import eu.darken.butler.apps.core.details.components.ComponentEnabledState
import eu.darken.butler.apps.core.details.components.ComponentEntry
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.workspace.ui.actions.WorkspaceActionBarItem

sealed interface ComponentsActionBarItem : WorkspaceActionBarItem {
    override val icon: ImageVector
    override val label: CaString
    override val isVisible: Boolean get() = true
    override val isEnabled: Boolean get() = true
    override val isDestructive: Boolean get() = false
    override val group: WorkspaceActionBarItem.Group get() = WorkspaceActionBarItem.Group.PRIMARY
    override val badge: Boolean get() = false

    data class Disable(
        val entries: List<ComponentEntry>,
    ) : ComponentsActionBarItem {
        override val icon = Icons.TwoTone.Block
        override val label = R.string.apps_action_disable.toCaString()
        override val isVisible: Boolean
            get() = entries.isNotEmpty() && entries.all { it.enabledState == ComponentEnabledState.ENABLED }
    }

    data class Enable(
        val entries: List<ComponentEntry>,
    ) : ComponentsActionBarItem {
        override val icon = Icons.TwoTone.CheckCircle
        override val label = R.string.apps_action_enable.toCaString()

        // The explicit `none { UNRESOLVED }` is load-bearing: withEnabledStates() accepts partial
        // maps, so a mixed DISABLED+UNRESOLVED selection is representable, and `any { DISABLED }`
        // alone would offer to act on entries whose current direction is unknown.
        override val isVisible: Boolean
            get() = entries.isNotEmpty() &&
                entries.none { it.enabledState == ComponentEnabledState.UNRESOLVED } &&
                entries.any { it.enabledState == ComponentEnabledState.DISABLED }
    }
}
