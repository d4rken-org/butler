package eu.darken.butler.explorer.core

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.TrashItemReference

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

        data object Network : Target {
            override val label: CaString = R.string.explorer_navigation_network.toCaString()
        }

        sealed interface Trash : Target {
            data object Root : Trash {
                override val label: CaString = R.string.explorer_navigation_trash.toCaString()
                override val description: CaString = R.string.explorer_navigation_trash_desc.toCaString()
            }

            /**
             * Navigation target for browsing inside a trashed folder.
             * @param parentItem Reference to the root-level trash item containing this path
             * @param relativePath Path relative to parentItem's trash location (empty for root of trashed folder)
             */
            data class Nested(
                val parentItem: TrashItemReference,
                val relativePath: String = "",
            ) : Trash {
                override val label: CaString
                    get() = if (relativePath.isEmpty()) {
                        parentItem.displayName
                    } else {
                        relativePath.substringAfterLast("/").toCaString()
                    }
                override val description: CaString = R.string.explorer_navigation_trash_nested_desc.toCaString()
            }
        }

        data class Directory(val path: APath<*>) : Target {
            override val label: CaString = path.userReadablePath
        }
    }

    data object Forward : ExplorerNavigation
    data object Back : ExplorerNavigation
    data object Refresh : ExplorerNavigation
    data object Cancel : ExplorerNavigation
}