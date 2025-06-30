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
    ) : ExplorerLocation {
        override val displayIcon: ImageVector
            get() = Icons.TwoTone.FolderOpen

        override val displayName: CaString?
            get() = path.userReadableName

        override val breadcrumbs: List<Breadcrumb>
            get() = buildList {
                add(Home.CRUMB)
                add(Device.CRUMB)

                // Add path segments
                val segments = path.segments
                segments.forEachIndexed { index, segment ->
                    val segmentPath = path.child(*segments.take(index + 1).toTypedArray())
                    add(
                        Breadcrumb(
                            label = segment.toCaString(),
                            icon = if (index == segments.lastIndex) Icons.TwoTone.FolderOpen else null,
                            target = Breadcrumb.Target.Directory(segmentPath)
                        )
                    )
                }
            }
    }

}