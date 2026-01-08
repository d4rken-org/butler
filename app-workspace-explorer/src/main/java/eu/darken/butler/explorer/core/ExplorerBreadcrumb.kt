package eu.darken.butler.explorer.core

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString

data class ExplorerBreadcrumb(
    val target: ExplorerNavigation,
    val label: CaString,
    val icon: ImageVector,
    val badgeIcon: ImageVector? = null,
) {
    override fun toString(): String = "Explorerbreadcrumb($target)"
}