package eu.darken.butler.explorer.core.engine

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.explorer.core.ExplorerNavigation
import kotlin.time.Instant

sealed interface ExplorerItem {
    val displayName: CaString
    val id: String

    data class Shortcut(
        val shortcutId: String,
        override val displayName: CaString,
        val displayIcon: ImageVector,
        val target: ExplorerNavigation,
    ) : ExplorerItem {
        override val id: String get() = shortcutId
    }

    sealed interface Path : ExplorerItem {
        val path: APath

        override val id: String get() = path.path
        override val displayName: CaString get() = path.userReadableName
    }

    sealed interface Lookup : Path {
        val lookup: APathLookup<*>
        val ownership: Ownership?
        val permissions: Permissions?
        val createdAt: Instant?

        override val path: APath get() = lookup.lookedUp
        override val id: String get() = lookup.path
        override val displayName: CaString get() = lookup.userReadableName

        fun withExtendedData(ownership: Ownership?, permissions: Permissions?, createdAt: Instant?): Path
    }

    sealed interface Directory : Lookup {
        val childCount: Int?
    }

    sealed interface File : Lookup {
        val mimeType: String
    }

    data class Peek(
        override val path: APath,
    ) : Path

    data class RegularDirectory(
        override val lookup: APathLookup<*>,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        override val createdAt: Instant? = null,
        override val childCount: Int? = null
    ) : Directory {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?, createdAt: Instant?) = copy(
            ownership = ownership,
            permissions = permissions,
            createdAt = createdAt
        )
    }

    data class RegularFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        override val createdAt: Instant? = null,
    ) : File {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?, createdAt: Instant?) = copy(
            ownership = ownership,
            permissions = permissions,
            createdAt = createdAt
        )
    }

    data class SymbolicLink(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        override val createdAt: Instant? = null,
        val targetPath: String? = null,
        val isBroken: Boolean = false
    ) : File {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?, createdAt: Instant?) = copy(
            ownership = ownership,
            permissions = permissions,
            createdAt = createdAt
        )
    }
}