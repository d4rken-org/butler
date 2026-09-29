package eu.darken.butler.explorer.core.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import eu.darken.butler.common.compose.icons.SmbShare
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.network.NetworkLocation

internal val NetworkLocation.icon: ImageVector
    get() = when (this) {
        is NetworkLocation.Smb -> Icons.TwoTone.SmbShare
        is NetworkLocation.Sftp -> Icons.TwoTone.Terminal
    }

internal val NetworkLocation.rootPath: APath<*>
    get() = when (this) {
        is NetworkLocation.Smb -> location.rootPath
        is NetworkLocation.Sftp -> SftpPath.root(id)
    }
