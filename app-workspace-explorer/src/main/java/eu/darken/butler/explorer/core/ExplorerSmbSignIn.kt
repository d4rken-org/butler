package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.smb.isSmbSignInFailure
import kotlin.uuid.Uuid

/**
 * The network location whose sign-in this failed state is asking for, or null if it isn't asking.
 *
 * Derived from the same snapshot the error arrived in: the loaded location is cleared when a load
 * fails, so anything read next to the error rather than out of it can already be gone.
 */
fun ExplorerWorkspace.State.Ready.smbSignInLocationId(): Uuid? {
    val error = error ?: return null
    if (!error.isSmbSignInFailure()) return null
    val path = (currentTarget as? ExplorerNavigation.Target.Directory)?.path
    return (path as? SmbPath)?.locationId
}
