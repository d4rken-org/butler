package eu.darken.butler.explorer.core.engine

import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import eu.darken.butler.explorer.core.ExplorerNavigation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    sealed interface PathItem : ExplorerItem {
        val lookup: APathLookup<*>
        val ownership: Ownership?
        val permissions: Permissions?

        override val id: String get() = lookup.path
        override val displayName: CaString get() = caString { lookup.name }

        fun withExtendedData(ownership: Ownership?, permissions: Permissions?): PathItem
    }

    sealed interface DirectoryItem : PathItem {
        val childCount: Int?
    }

    sealed interface FileItem : PathItem {
        val mimeType: String
    }

    data class RegularDirectory(
        override val lookup: APathLookup<*>,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        override val childCount: Int? = null
    ) : DirectoryItem {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }

    data class RegularFile(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null
    ) : FileItem {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }

    data class SymbolicLink(
        override val lookup: APathLookup<*>,
        override val mimeType: String,
        override val ownership: Ownership? = null,
        override val permissions: Permissions? = null,
        val targetPath: String? = null,
        val isBroken: Boolean = false
    ) : FileItem {
        override fun withExtendedData(ownership: Ownership?, permissions: Permissions?) = copy(
            ownership = ownership,
            permissions = permissions
        )
    }
}


// TODO: This would use a proper date formatter in a real implementation
private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestamp))
}