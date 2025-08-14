package eu.darken.butler.explorer.core

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString

data class ExplorerBreadcrumb(
    val label: CaString,
    val icon: ImageVector? = null,
    val preferIcon: Boolean = false,
    val target: ExplorerNavigation,
)