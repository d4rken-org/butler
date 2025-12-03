package eu.darken.butler.explorer.core

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Delete
import androidx.compose.material.icons.twotone.FolderOpen
import androidx.compose.material.icons.twotone.FolderShared
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.PauseCircle
import androidx.compose.material.icons.twotone.PhoneAndroid
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.explorer.R
import eu.darken.butler.explorer.core.engine.ExplorerLocation
import javax.inject.Inject

class BreadcrumbGenerator @Inject constructor(
    private val safLocationManager: SAFLocationManager,
    private val trashSettings: TrashSettings,
) {

    private suspend fun getTrashBreadcrumb(): ExplorerBreadcrumb {
        val trashEnabled = trashSettings.enabled.value()
        return ExplorerBreadcrumb(
            label = R.string.explorer_navigation_trash.toCaString(),
            icon = Icons.TwoTone.Delete,
            badgeIcon = if (!trashEnabled) Icons.TwoTone.PauseCircle else null,
            target = ExplorerNavigation.Target.Trash.Root,
            showIcon = true,
            showText = false,
        )
    }

    suspend fun getBreadcrumbs(location: ExplorerLocation): List<ExplorerBreadcrumb> = when (location) {
        is ExplorerLocation.Home -> listOf(HOME)
        is ExplorerLocation.Device -> listOf(HOME, DEVICE)
        is ExplorerLocation.Trash.Root -> listOf(HOME, getTrashBreadcrumb())
        is ExplorerLocation.Trash.Nested -> buildList {
            add(HOME)
            add(getTrashBreadcrumb())

            // Breadcrumb for the root trashed item
            add(
                ExplorerBreadcrumb(
                    label = location.parentItem.displayName,
                    icon = Icons.TwoTone.FolderOpen,
                    showIcon = true,
                    target = ExplorerNavigation.Target.Trash.Nested(location.parentItem, ""),
                )
            )

            // Breadcrumbs for nested path segments
            if (location.relativePath.isNotEmpty()) {
                var accumulated = ""
                location.relativePath.split("/").forEach { segment ->
                    accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
                    add(
                        ExplorerBreadcrumb(
                            label = segment.toCaString(),
                            icon = Icons.TwoTone.FolderOpen,
                            target = ExplorerNavigation.Target.Trash.Nested(location.parentItem, accumulated),
                        )
                    )
                }
            }
        }
        is ExplorerLocation.Directory -> buildList {
            when (location.parent) {
                is ExplorerNavigation.Target.Home -> {
                    add(HOME)
                }
                is ExplorerNavigation.Target.Device -> {
                    add(HOME)
                    add(DEVICE)
                }
                is ExplorerNavigation.Target.Trash -> {
                    add(HOME)
                    add(getTrashBreadcrumb())
                }
                is ExplorerNavigation.Target.Trash.Nested -> {
                    add(HOME)
                    add(getTrashBreadcrumb())
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
                            label = if (segment.isEmpty()) {
                                R.string.explorer_navigation_root.toCaString()
                            } else {
                                segment.toCaString()
                            },
                            icon = Icons.TwoTone.FolderOpen,
                            showIcon = segment.isEmpty(), // Show icon for root breadcrumb
                            target = ExplorerNavigation.Target.Directory(LocalPath.build(newPath))
                        )
                    )

                    newPath
                }
                is SAFPath -> {
                    // Find the SAF location to get its display name
                    val locationMatch = safLocationManager.findPermissionFor(location.path)

                    if (locationMatch != null) {
                        // Add breadcrumb for the SAF root location
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

                        // Add breadcrumbs for segments beyond the permission root
                        // Use missingSegments to avoid duplicating segments already in the SAF location label
                        val accumulatedSegments = mutableListOf<String>()
                        locationMatch.missingSegments.forEach { segment ->
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
                    } else {
                        // Fallback: No permission match found, show all segments
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
                }
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