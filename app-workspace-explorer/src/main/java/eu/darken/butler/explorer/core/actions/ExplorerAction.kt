package eu.darken.butler.explorer.core.actions

import androidx.compose.ui.graphics.vector.ImageVector

data class ExplorerAction(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val isVisible: Boolean = true,
    val isEnabled: Boolean = true,
    val isDestructive: Boolean = false,
    val group: Group = Group.PRIMARY,
    val badge: String? = null,
) {
    enum class Group {
        SELECTION_INFO,
        PRIMARY,
        SECONDARY,
        OVERFLOW,
    }
}