package eu.darken.butler.explorer.core

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString

data class ExplorerBreadcrumb(
    val label: CaString,
    val icon: ImageVector? = null,
    val showText: Boolean = true,
    val showIcon: Boolean = false,
    val target: ExplorerNavigation,
) {
    override fun toString(): String = "Explorerbreadcrumb($target)"
}