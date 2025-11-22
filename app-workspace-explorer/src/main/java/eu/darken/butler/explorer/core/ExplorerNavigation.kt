package eu.darken.butler.explorer.core

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.R

sealed interface ExplorerNavigation {
    sealed interface Target : ExplorerNavigation {
        val label: CaString
        val description: CaString? get() = null

        data object Home : Target {
            override val label: CaString = R.string.explorer_navigation_home.toCaString()
        }

        data object Device : Target {
            override val label: CaString = R.string.explorer_navigation_device.toCaString()
        }

        data object RecycleBin : Target {
            override val label: CaString = R.string.explorer_navigation_recyclebin.toCaString()
            override val description: CaString = R.string.explorer_navigation_recyclebin_desc.toCaString()
        }

        data class Directory(val path: APath<*>) : Target {
            override val label: CaString = path.userReadableName
            override val description: CaString = path.userReadablePath
        }
    }

    data object Forward : ExplorerNavigation
    data object Back : ExplorerNavigation
    data object Refresh : ExplorerNavigation
    data object Cancel : ExplorerNavigation
}