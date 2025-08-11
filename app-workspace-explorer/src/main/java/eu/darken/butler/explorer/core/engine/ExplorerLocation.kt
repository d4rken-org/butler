package eu.darken.butler.explorer.core.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.PhoneAndroid
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.ExplorerBreadcrumb
import eu.darken.butler.explorer.core.ExplorerNavigation

sealed interface ExplorerLocation {
    val items: List<ExplorerItem>

    data class Home(
        override val items: List<ExplorerItem>,
    ) : ExplorerLocation

    data class Device(
        override val items: List<ExplorerItem>,
    ) : ExplorerLocation

    data class Directory(
        val path: APath,
        val parent: ExplorerNavigation.Target? = null,
        override val items: List<ExplorerItem.PathItem> = emptyList(),
    ) : ExplorerLocation 

}