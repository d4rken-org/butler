package eu.darken.butler.explorer.core

import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.sftp.SftpHostKeyChangedException
import eu.darken.butler.common.files.sftp.isSftpSignInFailure
import eu.darken.butler.common.files.smb.isSmbSignInFailure
import kotlin.uuid.Uuid

/** A network location a failed load asks the user to sign in to again, per protocol. */
sealed interface NetworkSignInRequest {
    val locationId: Uuid

    data class Smb(override val locationId: Uuid) : NetworkSignInRequest

    data class Sftp(override val locationId: Uuid) : NetworkSignInRequest
}

/**
 * The network location whose sign-in this failed state is asking for, or null if it isn't asking.
 *
 * Derived from the same snapshot the error arrived in: the loaded location is cleared when a load
 * fails, so anything read next to the error rather than out of it can already be gone.
 */
fun ExplorerWorkspace.State.Ready.networkSignInRequest(): NetworkSignInRequest? {
    val error = error ?: return null
    return when (val path = (currentTarget as? ExplorerNavigation.Target.Directory)?.path) {
        is SmbPath -> NetworkSignInRequest.Smb(path.locationId).takeIf { error.isSmbSignInFailure() }
        is SftpPath -> NetworkSignInRequest.Sftp(path.locationId).takeIf { error.isSftpSignInFailure() }
        else -> null
    }
}

/**
 * The changed SFTP host key behind this failure, found below the wrappers generic operations add.
 * A trust decision rather than a sign-in: see [NetworkSignInRequest] for the credential side.
 */
fun Throwable.sftpHostKeyChange(): SftpHostKeyChangedException? {
    val seen = mutableSetOf<Throwable>()
    return generateSequence(this) { it.cause }
        .takeWhile { seen.add(it) }
        .firstNotNullOfOrNull { it as? SftpHostKeyChangedException }
}
