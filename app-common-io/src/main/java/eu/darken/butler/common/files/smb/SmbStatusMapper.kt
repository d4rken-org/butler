package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.smb.credentials.SmbCredentialUnavailableException
import eu.darken.smb.SmbException
import eu.darken.smb.SmbException.Kind
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.net.SocketException
import java.net.SocketTimeoutException

object SmbStatusMapper {
    fun isMissing(error: Throwable): Boolean = (error as? SmbException)?.kind == Kind.MISSING

    fun isTransportLost(error: Throwable): Boolean = when (error) {
        is SmbException -> error.kind == Kind.TRANSPORT
        is SocketException, is SocketTimeoutException, is EOFException -> true
        else -> false
    }

    fun mapAuthenticate(error: Throwable, endpoint: String): Throwable = when ((error as? SmbException)?.kind) {
        Kind.ACCESS_DENIED, Kind.AUTHENTICATION -> SmbAuthException(endpoint, error)
        else -> error
    }

    fun mapConnectShare(error: Throwable, endpoint: String, share: String): Throwable =
        when ((error as? SmbException)?.kind) {
            Kind.ACCESS_DENIED, Kind.SHARE_ACCESS_DENIED -> SmbShareAccessDeniedException(endpoint, share, error)
            else -> error
        }

    fun mapConnect(error: Throwable, endpoint: String, share: String): Throwable {
        if (error is CancellationException || isMapped(error)) return error
        return when ((error as? SmbException)?.kind) {
            Kind.AUTHENTICATION, Kind.ACCESS_DENIED -> SmbAuthException(endpoint, error)
            Kind.SHARE_ACCESS_DENIED -> SmbShareAccessDeniedException(endpoint, share, error)
            Kind.SHARE_MISSING -> SmbShareNotFoundException(endpoint, share, error)
            Kind.UNSUPPORTED_DIALECT -> SmbDialectNotSupportedException(endpoint, error)
            else -> SmbUnreachableException(endpoint, error)
        }
    }

    fun mapOperation(error: Throwable, path: APath<*>, operation: String, write: Boolean): Throwable {
        if (error is CancellationException || isMapped(error)) return error
        val smb = error as? SmbException
        return when (smb?.kind) {
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
            Kind.SHARING_VIOLATION -> wrap("File is in use on the server", path, error, write)
            Kind.AUTHENTICATION -> SmbAuthException(path.path, error)
            Kind.UNSUPPORTED_DIALECT -> SmbDialectNotSupportedException(path.path, error)
            else -> {
                val message = when (smb?.status) {
                    0xc0000056L -> "File is being deleted on the server"
                    0xc0000033L -> "The server rejected this name"
                    0xc0000106L -> "The name is too long for the server"
                    else -> error.message ?: "SMB operation failed"
                }
                wrap(message, path, error, write)
            }
        }
    }

    private fun isMapped(error: Throwable): Boolean = error is SmbUnreachableException || error is SmbAuthException ||
        error is SmbShareNotFoundException || error is SmbDialectNotSupportedException ||
        error is SmbShareAccessDeniedException || error is SmbCredentialUnavailableException

    private fun wrap(message: String, path: APath<*>, cause: Throwable, write: Boolean): Throwable = when {
        write -> WriteException(message, path, cause)
        else -> ReadException(message, path, cause)
    }
}
