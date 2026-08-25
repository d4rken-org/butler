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
import eu.darken.butler.common.files.smb.SmbEndpointState
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.explorer.core.ExplorerNavigation
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface ExplorerItem {
    val displayName: CaString
    val id: String

    /**
     * @param context Optional context for future use (e.g., picker mode, permissions)
     * @return true if this item can be selected in the current context
     */
    fun isSelectable(context: Any? = null): Boolean = true

    data class Shortcut(
        val shortcutId: String,
        override val displayName: CaString,
        val displayIcon: ImageVector,
        val target: ExplorerNavigation.Target,
        val subtitle: CaString? = null,
        val badge: Badge? = null,
    ) : ExplorerItem {
        override fun isSelectable(context: Any?): Boolean = false
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

        /** Shown instead of the raw path where the path is not meaningful to the user. */
        val subtitle: CaString? get() = null

        /** Whether this storage location is writable. Null means unknown (treated as writable). */
        val canWrite: Boolean?

        data class Local(
            val localId: String,
            override val displayName: CaString,
            override val displayIcon: ImageVector,
            override val target: ExplorerNavigation.Target.Directory,
            override val totalBytes: Long? = null,
            override val availableBytes: Long? = null,
            override val canWrite: Boolean? = null,
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
            override val canWrite: Boolean? get() = location.hasWritePermission
        }

        /**
         * A stored network location. Capacity stays null: reading it would mean opening a session on
         * every server just to draw the Network view, which a reachability probe does not do.
         *
         * [endpoint] arrives after the row is first drawn, [status] comes from the credential vault.
         */
        data class Network(
            val location: SmbLocation,
            override val displayName: CaString,
            override val displayIcon: ImageVector,
            override val target: ExplorerNavigation.Target.Directory,
            override val subtitle: CaString,
            val status: Status,
            val endpoint: SmbEndpointState = SmbEndpointState(),
        ) : Storage {
            override val id: String get() = "network-${location.id}"
            override val totalBytes: Long? get() = null
            override val availableBytes: Long? get() = null
            override val canWrite: Boolean get() = true

            /** Whether the row has something to flag: a credential problem, an absent server, or both. */
            val hasIssue: Boolean
                get() = status == Status.SIGN_IN_REQUIRED ||
                    endpoint.reachability == SmbEndpointState.Reachability.UNREACHABLE

            enum class Status {
                AVAILABLE,

                /** A password location whose credential the vault cannot produce. */
                SIGN_IN_REQUIRED,
            }
        }
    }

    sealed interface Path : ExplorerItem {
        val path: APath<*>

        override val id: String get() = path.toPathItemId()
        override val displayName: CaString get() = path.userReadableName

        companion object {
            /**
             * Derives the ExplorerItem ID for a path.
             * Used by [Path.id] and when matching operation results to items (e.g., for highlighting).
             * Single source of truth - change here updates both usages.
             */
            fun APath<*>.toPathItemId(): String = this.path
        }
    }

    sealed interface Lookup : Path {
        val lookup: APathLookup<*>
        val ownership: Ownership?
        val permissions: Permissions?
        val createdAt: Instant?
        val metadata: FileMetadata?

        /** Whether this item is writable. Null means unknown (treated as writable). */
        val canWrite: Boolean?

        override val path: APath<*> get() = lookup.lookedUp
        override val id: String get() = lookup.path
        override val displayName: CaString get() = lookup.userReadableName

        fun withExtendedData(
            ownership: Ownership?,
            permissions: Permissions?,
            createdAt: Instant?,
            metadata: FileMetadata? = null,
            canWrite: Boolean? = null,
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
    ) : Path {
        override val id: String get() = "peek:${path.path}"
    }

    data class RegularDirectory(
        override val lookup: APathLookup<*>,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        override val createdAt: Instant? = null,
        override val childCount: Int? = null,
        override val metadata: FileMetadata? = null,
        override val canWrite: Boolean? = null,
    ) : Directory {
        override fun withExtendedData(
            ownership: Ownership?,
            permissions: Permissions?,
            createdAt: Instant?,
            metadata: FileMetadata?,
            canWrite: Boolean?,
        ) = copy(
            ownership = ownership ?: this.ownership,
            permissions = permissions ?: this.permissions,
            createdAt = createdAt ?: this.createdAt,
            metadata = metadata ?: this.metadata,
            canWrite = canWrite ?: this.canWrite,
        )
    }

    data class RegularFile(
        override val lookup: APathLookup<*>,
        override val mimeType: MimeInfo,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        override val createdAt: Instant? = null,
        override val metadata: FileMetadata? = null,
        override val canWrite: Boolean? = null,
    ) : File {
        override fun withExtendedData(
            ownership: Ownership?,
            permissions: Permissions?,
            createdAt: Instant?,
            metadata: FileMetadata?,
            canWrite: Boolean?,
        ) = copy(
            ownership = ownership ?: this.ownership,
            permissions = permissions ?: this.permissions,
            createdAt = createdAt ?: this.createdAt,
            metadata = metadata ?: this.metadata,
            canWrite = canWrite ?: this.canWrite,
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
        override val canWrite: Boolean? = null,
    ) : File {
        override fun withExtendedData(
            ownership: Ownership?,
            permissions: Permissions?,
            createdAt: Instant?,
            metadata: FileMetadata?,
            canWrite: Boolean?,
        ) = copy(
            ownership = ownership ?: this.ownership,
            permissions = permissions ?: this.permissions,
            createdAt = createdAt ?: this.createdAt,
            metadata = metadata ?: this.metadata,
            canWrite = canWrite ?: this.canWrite,
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
            override fun isSelectable(context: Any?): Boolean = isAvailable
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

/**
 * A network location whose credential the vault cannot produce. Butler cannot open it, so it has to
 * reach the sign-in form instead of a listing, and no picker may hand it back to its caller.
 */
fun ExplorerItem.needsSignIn(): Boolean = this is ExplorerItem.Storage.Network &&
    status == ExplorerItem.Storage.Network.Status.SIGN_IN_REQUIRED