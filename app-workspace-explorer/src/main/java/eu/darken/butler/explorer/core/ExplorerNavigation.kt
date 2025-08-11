package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.APath

sealed interface ExplorerNavigation {
    sealed interface Target : ExplorerNavigation {
        data object Home : Target
        data object Device : Target
        data class Directory(val path: APath) : Target
    }

    data object Forward : ExplorerNavigation
    data object Back : ExplorerNavigation
    data object Refresh : ExplorerNavigation
    data object Cancel : ExplorerNavigation
}