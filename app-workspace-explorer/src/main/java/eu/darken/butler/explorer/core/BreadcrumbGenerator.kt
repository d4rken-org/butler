package eu.darken.butler.explorer.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.PhoneAndroid
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.RawPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import javax.inject.Inject

class BreadcrumbGenerator @Inject constructor(
    private val safLocationManager: SAFLocationManager,
) {

    suspend fun getBreadcrumbs(location: ExplorerLocation): List<ExplorerBreadcrumb> = when (location) {
        is ExplorerLocation.Home -> listOf(HOME)
        is ExplorerLocation.Device -> listOf(HOME, DEVICE)
        is ExplorerLocation.Directory -> buildList {
            when (location.parent) {
                is ExplorerNavigation.Target.Home -> {
                    add(HOME)
                }
                is ExplorerNavigation.Target.Device -> {
                    add(HOME)
                    add(DEVICE)
                }
                is ExplorerNavigation.Target.Directory -> {
                    add(HOME)
                    add(DEVICE)
                }
                null -> {
                    add(HOME)
                    add(DEVICE)
                }
            }

            when (location.path) {
                is LocalPath -> location.path.segments.fold("") { accPath, segment ->
                    val newPath = when {
                        segment.isEmpty() -> "/"
                        accPath == "/" -> "$accPath$segment"
                        accPath.isEmpty() -> "/$segment"
                        else -> "$accPath/$segment"
                    }

                    add(
                        ExplorerBreadcrumb(
                            label = segment.ifEmpty { "/" }.toCaString(),
                            icon = Icons.TwoTone.FolderOpen,
                            target = ExplorerNavigation.Target.Directory(LocalPath.build(newPath))
                        )
                    )

                    newPath
                }
                is SAFPath -> {
                    // Find the SAF location to get its display name
                    val locationMatch = safLocationManager.findPermissionFor(location.path)

                    // Add breadcrumb for the SAF root location
                    if (locationMatch != null) {
                        add(
                            ExplorerBreadcrumb(
                                label = locationMatch.location.displayName,
                                icon = Icons.TwoTone.FolderShared,
                                showIcon = true,
                                target = ExplorerNavigation.Target.Directory(
                                    SAFPath.build(location.path.treeRootUri)
                                )
                            )
                        )
                    }

                    // Add breadcrumbs for each path segment
                    val accumulatedSegments = mutableListOf<String>()
                    location.path.segments.forEach { segment ->
                        accumulatedSegments.add(segment)

                        add(
                            ExplorerBreadcrumb(
                                label = segment.ifEmpty { location.path.name }.toCaString(),
                                icon = Icons.TwoTone.FolderOpen,
                                target = ExplorerNavigation.Target.Directory(
                                    SAFPath.build(
                                        location.path.treeRootUri,
                                        *accumulatedSegments.toTypedArray()
                                    )
                                )
                            )
                        )
                    }
                }
                is RawPath -> throw UnsupportedOperationException("RawPath not supported")
            }
        }
    }

    companion object {

        val DEVICE = ExplorerBreadcrumb(
            label = R.string.explorer_navigation_device.toCaString(),
            icon = Icons.TwoTone.PhoneAndroid,
            target = ExplorerNavigation.Target.Device,
            showIcon = true,
            showText = false,
        )

        val HOME = ExplorerBreadcrumb(
            label = R.string.explorer_navigation_home.toCaString(),
            icon = Icons.TwoTone.Home,
            target = ExplorerNavigation.Target.Home,
            showIcon = true,
            showText = false,
        )
    }
}