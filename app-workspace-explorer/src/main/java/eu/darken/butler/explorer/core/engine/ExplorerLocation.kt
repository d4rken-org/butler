package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.permissions.WorkspacePermissions

sealed interface ExplorerLocation {
    val items: List<ExplorerItem>
    val info: LocationInfo?
    val permissionState: WorkspacePermissions

    sealed interface LocationInfo

    data class Home(
        override val items: List<ExplorerItem>,
        override val info: Info? = null,
        override val permissionState: WorkspacePermissions = WorkspacePermissions(),
    ) : ExplorerLocation {
        data class Info(
            val shortcutCount: Int,
            val totalDeviceStorage: Long? = null,
            val usedStorage: Long? = null,
        ) : LocationInfo
    }

    data class Device(
        override val items: List<ExplorerItem>,
        override val info: Info? = null,
        override val permissionState: WorkspacePermissions = WorkspacePermissions(),
    ) : ExplorerLocation {
        data class Info(
            val storageCount: Int,
            val totalCapacity: Long? = null,
            val usedSpace: Long? = null,
        ) : LocationInfo
    }

    data class Directory(
        val path: APath,
        val parent: ExplorerNavigation.Target? = null,
        override val items: List<ExplorerItem.PathItem> = emptyList(),
        override val info: Info? = null,
        override val permissionState: WorkspacePermissions = WorkspacePermissions(),
    ) : ExplorerLocation {
        data class Info(
            val fileCount: Int,
            val directoryCount: Int,
            val totalSize: Long? = null,
            val volumeFreeSpace: Long? = null,
            val volumeTotalSpace: Long? = null,
            val isWritable: Boolean = false,
            val isReadable: Boolean = true,
        ) : LocationInfo
    }
}