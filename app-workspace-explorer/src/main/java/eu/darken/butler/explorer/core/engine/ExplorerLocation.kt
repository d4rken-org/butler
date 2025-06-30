package eu.darken.butler.explorer.core.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.PhoneAndroid
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath

sealed interface ExplorerLocation {
    val displayIcon: ImageVector?
    val displayName: CaString?
    val breadcrumbs: List<Breadcrumb>

    data class Breadcrumb(
        val label: CaString,
        val icon: ImageVector? = null,
        val target: Target,
        val preferIcon: Boolean = false,
    ) {
        sealed interface Target {
            data object Home : Target
            data object Device : Target
            data class Directory(val path: APath) : Target
        }
    }

    data class Home(
        val items: List<Item>,
    ) : ExplorerLocation {
        override val displayIcon: ImageVector
            get() = Icons.TwoTone.Home

        override val displayName: CaString?
            get() = "Home".toCaString()

        override val breadcrumbs: List<Breadcrumb>
            get() = listOf(CRUMB)

        data class Item(
            val icon: ImageVector,
            val label: CaString,
            val target: ExplorerLocation,
        )

        companion object {
            val CRUMB = Breadcrumb(
                label = "Home".toCaString(),
                icon = Icons.TwoTone.Home,
                target = Breadcrumb.Target.Home,
                preferIcon = true,
            )
        }
    }

    data class Device(
        val items: List<Item>,
    ) : ExplorerLocation {
        override val displayIcon: ImageVector
            get() = Icons.TwoTone.PhoneAndroid

        override val displayName: CaString?
            get() = "Device".toCaString()

        override val breadcrumbs: List<Breadcrumb>
            get() = listOf(Home.CRUMB, CRUMB)

        data class Item(
            val icon: ImageVector,
            val label: CaString,
            val target: APath,
        )

        companion object {
            val CRUMB = Breadcrumb(
                label = "Device".toCaString(),
                icon = Icons.TwoTone.PhoneAndroid,
                target = Breadcrumb.Target.Device,
                preferIcon = true,
            )
        }
    }

    data class Directory(
        val path: APath,
        val items: List<ExplorerPathItem>? = null,
        val parent: ExplorerLocation? = null,
    ) : ExplorerLocation {
        override val displayIcon: ImageVector
            get() = Icons.TwoTone.FolderOpen

        override val displayName: CaString?
            get() = path.userReadableName

        override val breadcrumbs: List<Breadcrumb>
            get() = buildList {
                // Build parent breadcrumbs based on actual navigation path
                when (parent) {
                    is Home -> add(Home.CRUMB)
                    is Device -> {
                        add(Home.CRUMB)
                        add(Device.CRUMB)
                    }
                    is Directory -> addAll(parent.breadcrumbs)
                    null -> {
                        // Default fallback if no parent is set
                        add(Home.CRUMB)
                        add(Device.CRUMB)
                    }
                }

                // Add current directory as breadcrumb
                add(
                    Breadcrumb(
                        label = path.name.toCaString(),
                        icon = Icons.TwoTone.FolderOpen,
                        target = Breadcrumb.Target.Directory(path)
                    )
                )
            }
    }

}