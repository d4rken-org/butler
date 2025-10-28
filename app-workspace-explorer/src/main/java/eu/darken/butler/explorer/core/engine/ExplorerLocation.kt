package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.workspace.core.permissions.WorkspaceRequirements

sealed interface ExplorerLocation {
    val locationId: String
    val items: List<ExplorerItem>?
    val info: LocationInfo?
    val setupRequirements: WorkspaceRequirements
    val progress: Progress.Data?

    val isLoading: Boolean get() = progress != null

    sealed interface LocationInfo

    data class Home(
        override val items: List<ExplorerItem>? = null,
        override val info: Info? = null,
        override val setupRequirements: WorkspaceRequirements = WorkspaceRequirements(),
        override val progress: Progress.Data? = Progress.Data(),
    ) : ExplorerLocation {

        override val locationId: String get() = "location://home"

        data class Info(
            val shortcutCount: Int,
            val totalDeviceStorage: Long? = null,
            val usedStorage: Long? = null,
        ) : LocationInfo
    }

    data class Device(
        override val items: List<ExplorerItem>? = null,
        override val info: Info? = null,
        override val setupRequirements: WorkspaceRequirements = WorkspaceRequirements(),
        override val progress: Progress.Data? = Progress.Data(),
    ) : ExplorerLocation {

        override val locationId: String get() = "location://device"

        data class Info(
            val locationCount: Int,
            val totalCapacity: Long? = null,
            val usedSpace: Long? = null,
        ) : LocationInfo
    }

    data class Directory(
        override val items: List<ExplorerItem.Path>? = null,
        override val info: Info? = null,
        override val setupRequirements: WorkspaceRequirements = WorkspaceRequirements(),
        override val progress: Progress.Data? = Progress.Data(),
        val path: APath<*>,
        val parent: ExplorerNavigation.Target? = null,
    ) : ExplorerLocation {

        override val locationId: String get() = "location://directory/${path.path}"

        data class Info(
            val fileCount: Int? = null,
            val directoryCount: Int? = null,
            val totalSize: Long? = null,
            val volumeFreeSpace: Long? = null,
            val volumeTotalSpace: Long? = null,
            val isWritable: Boolean = false,
            val isReadable: Boolean = true,
        ) : LocationInfo
    }
}