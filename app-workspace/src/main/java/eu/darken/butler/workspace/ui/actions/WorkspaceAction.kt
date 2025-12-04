package eu.darken.butler.workspace.ui.actions

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString

/**
 * Base interface for workspace actions shown in the action bar
 */
interface WorkspaceAction {
    val icon: ImageVector
    val label: CaString
    val isVisible: Boolean get() = true
    val isEnabled: Boolean get() = true
    val isDestructive: Boolean get() = false
    val group: Group get() = Group.PRIMARY
    val badge: Boolean get() = false
    val supportsLongPress: Boolean get() = false

    enum class Group {
        /**
         * Primary actions are shown first and prioritized when space is limited
         */
        PRIMARY,

        /**
         * Secondary actions are shown after primary and overflow first
         */
        SECONDARY,
    }
}
