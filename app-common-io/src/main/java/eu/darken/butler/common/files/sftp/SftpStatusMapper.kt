package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialUnavailableException
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.ssh.SshException
import eu.darken.ssh.SshException.Kind
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException

object SftpStatusMapper {

    /** Walks the cause chain, so an already mapped failure still tells "gone" from "unreachable". */
    fun isMissing(error: Throwable): Boolean = error.causes().any { (it as? SshException)?.kind == Kind.MISSING }

    fun isTransportLost(error: Throwable): Boolean = when (error) {
        is SshException -> error.kind == Kind.TRANSPORT
        is SocketException, is SocketTimeoutException, is EOFException -> true
        else -> false
    }

    /** Failures of connecting to [location] and signing in, pinned to its stored host key. */
    fun mapConnect(error: Throwable, location: SftpLocation): Throwable {
        if (error is CancellationException || isMapped(error)) return error
        val endpoint = location.endpointLabel
        val ssh = error as? SshException
        return when (ssh?.kind) {
            Kind.AUTHENTICATION, Kind.ACCESS_DENIED -> SftpAuthException(endpoint, error)
            Kind.HOST_KEY_UNKNOWN -> ssh.presentedHostKey
                ?.let { SftpHostKeyUnknownException(endpoint, it, error) }
                ?: SftpUnreachableException(endpoint, error)

            Kind.HOST_KEY_MISMATCH -> ssh.presentedHostKey
                ?.let {
                    SftpHostKeyChangedException(
                        endpoint = endpoint,
                        locationId = location.id,
                        host = location.host,
                        port = location.port,
                        trustRevision = location.trustRevision,
                        storedKey = location.hostKey,
                        presentedKey = TrustedHostKey.from(it),
                        cause = error,
                    )
                }
                ?: SftpUnreachableException(endpoint, error)

            Kind.KEY_FORMAT -> SftpKeyFormatException(endpoint, error)
            Kind.KEY_PASSPHRASE -> SftpKeyPassphraseException(endpoint, error)
            else -> SftpUnreachableException(endpoint, error)
        }
    }

    /** Failures of resolving a location's base path, which happens once per session. */
    fun mapRoot(error: Throwable, endpoint: String, basePath: String, root: APath<*>): Throwable {
        if (error is CancellationException || isMapped(error)) return error
        return when ((error as? SshException)?.kind) {
            Kind.ACCESS_DENIED -> SftpAccessDeniedException(endpoint, basePath, error)
            else -> mapOperation(error, root, "resolveRoot", write = false)
        }
    }

    fun mapOperation(error: Throwable, path: APath<*>, operation: String, write: Boolean): Throwable {
        if (error is CancellationException || isMapped(error)) return error
        return when ((error as? SshException)?.kind) {
            Kind.MISSING -> ReadException("Path does not exist", path, error)
            Kind.ALREADY_EXISTS -> PathAlreadyExistsException(path = path, cause = error)
            Kind.ACCESS_DENIED -> PathPermissionDeniedException(
                path = path,
                operation = operation,
                reason = PathPermissionDeniedException.Reason.ACCESS_DENIED,
                cause = error,
            )

            Kind.NOT_DIRECTORY -> ReadException("Not a directory", path, error)
            Kind.IS_DIRECTORY -> wrap("Path is a directory", path, error, write)
            Kind.DIRECTORY_NOT_EMPTY -> WriteException("Directory is not empty", path, error)
            Kind.DISK_FULL -> WriteException("Not enough space on the server", path, error)
            Kind.AUTHENTICATION -> SftpAuthException(path.path, error)
            Kind.UNSUPPORTED -> wrap("The server does not support this operation", path, error, write)
            Kind.TRANSPORT -> wrap("The connection to the server was lost", path, error, write)
            else -> wrap(error.message ?: "SFTP operation failed", path, error, write)
        }
    }

    private fun isMapped(error: Throwable): Boolean = error is SftpUnreachableException ||
        error is SftpAuthException || error is SftpHostKeyUnknownException ||
        error is SftpHostKeyChangedException || error is SftpKeyFormatException ||
        error is SftpKeyPassphraseException || error is SftpAccessDeniedException ||
        error is SftpCredentialUnavailableException || error is SftpProRequiredException

    private fun wrap(message: String, path: APath<*>, cause: Throwable, write: Boolean): Throwable = when {
        write -> WriteException(message, path, cause)
        else -> ReadException(message, path, cause)
    }

    private fun Throwable.causes(): Sequence<Throwable> {
        val seen = mutableSetOf<Throwable>()
        return generateSequence(this) { it.cause }.takeWhile { seen.add(it) }
    }
}
