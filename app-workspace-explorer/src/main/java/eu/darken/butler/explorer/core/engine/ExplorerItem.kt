package eu.darken.butler.explorer.core.engine

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.metadata.FileMetadata
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.common.files.saf.location.SAFLocation
import eu.darken.butler.explorer.core.ExplorerNavigation
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface ExplorerItem {
    val displayName: CaString
    val id: String

    data class Shortcut(
        val shortcutId: String,
        override val displayName: CaString,
        val displayIcon: ImageVector,
        val target: ExplorerNavigation.Target,
        val subtitle: CaString? = null,
        val badge: Badge? = null,
    ) : ExplorerItem {
        override val id: String get() = "shortcut-$shortcutId"

        enum class Badge {
            PAUSED,
        }
    }

    sealed interface Storage : ExplorerItem {
        val displayIcon: ImageVector
        val target: ExplorerNavigation.Target.Directory
        val totalBytes: Long?
        val availableBytes: Long?

        data class Local(
            val localId: String,
            override val displayName: CaString,
            override val displayIcon: ImageVector,
            override val target: ExplorerNavigation.Target.Directory,
            override val totalBytes: Long? = null,
            override val availableBytes: Long? = null,
        ) : Storage {
            override val id: String get() = "local-$localId"
        }

        data class SAF(
            val location: SAFLocation,
            override val displayName: CaString,
            override val displayIcon: ImageVector,
            override val target: ExplorerNavigation.Target.Directory,
            override val totalBytes: Long? = null,
            override val availableBytes: Long? = null,
        ) : Storage {
            override val id: String get() = "saf-${location.id}"
        }
    }

    sealed interface Path : ExplorerItem {
        val path: APath<*>

        override val id: String get() = path.path
        override val displayName: CaString get() = path.userReadableName
    }

    sealed interface Lookup : Path {
        val lookup: APathLookup<*>
        val ownership: Ownership?
        val permissions: Permissions?
        val createdAt: Instant?
        val metadata: FileMetadata?

        override val path: APath<*> get() = lookup.lookedUp
        override val id: String get() = lookup.path
        override val displayName: CaString get() = lookup.userReadableName

        fun withExtendedData(
            ownership: Ownership?,
            permissions: Permissions?,
            createdAt: Instant?,
            metadata: FileMetadata? = null
        ): Path
    }

    sealed interface Directory : Lookup {
        val childCount: Int?
    }

    sealed interface File : Lookup {
        val mimeType: MimeInfo
    }

    data class Peek(
        override val path: APath<*>,
    ) : Path

    data class RegularDirectory(
        override val lookup: APathLookup<*>,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        override val createdAt: Instant? = null,
        override val childCount: Int? = null,
        override val metadata: FileMetadata? = null,
    ) : Directory {
        override fun withExtendedData(
            ownership: Ownership?,
            permissions: Permissions?,
            createdAt: Instant?,
            metadata: FileMetadata?
        ) = copy(
            ownership = ownership ?: this.ownership,
            permissions = permissions ?: this.permissions,
            createdAt = createdAt ?: this.createdAt,
            metadata = metadata ?: this.metadata,
        )
    }

    data class RegularFile(
        override val lookup: APathLookup<*>,
        override val mimeType: MimeInfo,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        override val createdAt: Instant? = null,
        override val metadata: FileMetadata? = null,
    ) : File {
        override fun withExtendedData(
            ownership: Ownership?,
            permissions: Permissions?,
            createdAt: Instant?,
            metadata: FileMetadata?
        ) = copy(
            ownership = ownership ?: this.ownership,
            permissions = permissions ?: this.permissions,
            createdAt = createdAt ?: this.createdAt,
            metadata = metadata ?: this.metadata,
        )
    }

    data class SymbolicLink(
        override val lookup: APathLookup<*>,
        override val mimeType: MimeInfo,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        override val createdAt: Instant? = null,
        val targetPath: String? = null,
        val isBroken: Boolean = false,
        override val metadata: FileMetadata? = null,
    ) : File {
        override fun withExtendedData(
            ownership: Ownership?,
            permissions: Permissions?,
            createdAt: Instant?,
            metadata: FileMetadata?
        ) = copy(
            ownership = ownership ?: this.ownership,
            permissions = permissions ?: this.permissions,
            createdAt = createdAt ?: this.createdAt,
            metadata = metadata ?: this.metadata,
        )
    }

    sealed interface Trash : ExplorerItem {
        val deletedAt: Instant

        /**
         * Root-level item directly deleted by user.
         */
        data class Root(
            val itemId: Uuid,
            override val deletedAt: Instant,
            val originalLookup: APathLookup<*>,
            val trashLookup: APathLookup<*>?,
        ) : Trash {
            val isAvailable get() = trashLookup != null
            override val id: String get() = "trash-$itemId"
            override val displayName: CaString
                get() = originalLookup.userReadableName
            val subtitle: CaString
                get() = originalLookup.parent?.userReadablePath ?: "?".toCaString()
        }

        /**
         * Item inside a trashed folder.
         * Wraps a regular Lookup item with trash context for operations.
         */
        data class Nested(
            val inner: Lookup,
            val parentRef: TrashItemReference,
            val relativePath: String,
        ) : Trash {
            override val deletedAt: Instant get() = parentRef.deletedAt
            override val id: String
                get() = "trash-nested-${parentRef.itemId}/$relativePath"
            override val displayName: CaString
                get() = inner.displayName

            /**
             * The path this item would have if fully restored.
             */
            val originalRestoredPath: APath<*>
                get() = parentRef.originalPath.child(relativePath)

            /**
             * The current physical path in the trash filesystem.
             */
            val currentTrashPath: APath<*>
                get() = parentRef.trashPath.child(relativePath)

            val lookup: APathLookup<*> get() = inner.lookup
            val isDirectory: Boolean get() = inner is Directory
            val isFile: Boolean get() = inner is File
        }
    }
}