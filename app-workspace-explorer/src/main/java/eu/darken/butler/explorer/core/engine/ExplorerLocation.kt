package eu.darken.butler.explorer.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.ExplorerNavigation
import eu.darken.butler.permissions.core.PathRequirements

sealed interface ExplorerLocation {
    val locationId: String
    val items: List<ExplorerItem>?
    val info: LocationInfo?
    val setupRequirements: PathRequirements
    val progress: Progress.Data?

    val isLoading: Boolean get() = progress != null

    sealed interface LocationInfo

    data class Home(
        override val items: List<ExplorerItem>? = null,
        override val info: Info? = null,
        override val setupRequirements: PathRequirements = PathRequirements(),
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
        override val setupRequirements: PathRequirements = PathRequirements(),
        override val progress: Progress.Data? = Progress.Data(),
    ) : ExplorerLocation {

        override val locationId: String get() = "location://device"

        data class Info(
            val locationCount: Int,
            val totalCapacity: Long? = null,
            val usedSpace: Long? = null,
        ) : LocationInfo
    }

    data class Network(
        override val items: List<ExplorerItem>? = null,
        override val info: Info? = null,
        override val setupRequirements: PathRequirements = PathRequirements(),
        override val progress: Progress.Data? = Progress.Data(),
    ) : ExplorerLocation {

        override val locationId: String get() = "location://network"

        data class Info(
            val locationCount: Int,
        ) : LocationInfo
    }

    data class Directory(
        override val items: List<ExplorerItem.Path>? = null,
        override val info: Info? = null,
        override val setupRequirements: PathRequirements = PathRequirements(),
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

    sealed interface Trash : ExplorerLocation {
        /**
         * Root trash view showing all deleted items.
         */
        data class Root(
            override val items: List<ExplorerItem>? = null,
            override val info: Info? = null,
            override val setupRequirements: PathRequirements = PathRequirements(),
            override val progress: Progress.Data? = Progress.Data(),
        ) : Trash {

            override val locationId: String get() = "location://trash"

            data class Info(
                val itemCount: Int,
                val totalSize: Long,
                val oldestItem: kotlin.time.Instant? = null,
            ) : LocationInfo

            data class StorageInfo(
                val label: String,
                val itemCount: Int,
                val size: Long,
            )
        }

        /**
         * Nested view inside a trashed folder.
         * Read-only: no create/paste/rename operations allowed.
         */
        data class Nested(
            override val items: List<ExplorerItem>? = null,
            override val info: Info? = null,
            override val setupRequirements: PathRequirements = PathRequirements(),
            override val progress: Progress.Data? = Progress.Data(),
            val parentItem: TrashItemReference,
            val currentPath: APath<*>,
            val relativePath: String,
            val parent: ExplorerNavigation.Target? = null,
        ) : Trash {

            override val locationId: String
                get() = "location://trash/${parentItem.itemId}/$relativePath"

            data class Info(
                val fileCount: Int? = null,
                val directoryCount: Int? = null,
                val totalSize: Long? = null,
            ) : LocationInfo
        }
    }
}