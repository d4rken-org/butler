package eu.darken.butler.explorer.core

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.R

sealed interface ExplorerNavigation {
    sealed interface Target : ExplorerNavigation {
        val label: CaString

        data object Home : Target {
            override val label: CaString = R.string.explorer_nav_home.toCaString()
        }

        data object Device : Target {
            override val label: CaString = R.string.explorer_nav_device.toCaString()
        }

        data class Directory(val path: APath) : Target {
            override val label: CaString = path.userReadableName
        }
    }

    data object Forward : ExplorerNavigation
    data object Back : ExplorerNavigation
    data object Refresh : ExplorerNavigation
    data object Cancel : ExplorerNavigation
}