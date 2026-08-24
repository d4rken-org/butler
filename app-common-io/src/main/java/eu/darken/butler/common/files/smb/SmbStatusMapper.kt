package eu.darken.butler.common.files.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb.SMB1NotSupportedException
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.smb.credentials.SmbCredentialUnavailableException
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Turns smbj failures into the exception types the rest of the app already handles.
 *
 * Kept in one place because the same NT status means different things per operation: a missing path
 * is an error for a read and the expected answer for an existence probe, so callers decide via
 * [isMissing] instead of pattern-matching statuses themselves.
 */
object SmbStatusMapper {

    private val MISSING_PATH = setOf(
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        NtStatus.STATUS_NO_SUCH_FILE,
        NtStatus.STATUS_NOT_FOUND,
    )

    private val TRANSPORT_LOST = setOf(
        NtStatus.STATUS_CONNECTION_DISCONNECTED,
        NtStatus.STATUS_CONNECTION_RESET,
        NtStatus.STATUS_NETWORK_NAME_DELETED,
        NtStatus.STATUS_USER_SESSION_DELETED,
        NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
        NtStatus.STATUS_VOLUME_DISMOUNTED,
    )

    private val AUTH_REJECTED = setOf(
        NtStatus.STATUS_LOGON_FAILURE,
        NtStatus.STATUS_PASSWORD_EXPIRED,
        NtStatus.STATUS_ACCOUNT_DISABLED,
        NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
    )

    /** The path an operation addressed does not exist. */
    fun isMissing(error: Throwable): Boolean = (error as? SMBApiException)?.status in MISSING_PATH

    /** The session is dead, its generation has to be dropped before retrying. */
    fun isTransportLost(error: Throwable): Boolean = when (error) {
        is TransportException -> true
        is SMBApiException -> error.status in TRANSPORT_LOST
        is SocketException, is SocketTimeoutException, is EOFException -> true
        else -> false
    }

    /** Failures raised while opening a connection, session or share. */
    fun mapConnect(error: Throwable, endpoint: String, share: String): Throwable = when {
        error is CancellationException -> error
        // Already carries its own user-facing meaning
        error is SmbUnreachableException || error is SmbAuthException -> error
        error is SmbShareNotFoundException || error is SmbDialectNotSupportedException -> error
        error is SmbCredentialUnavailableException -> error
        error is SMB1NotSupportedException -> SmbDialectNotSupportedException(endpoint, error)
        error is UnknownHostException -> SmbUnreachableException(endpoint, error)
        error is SocketTimeoutException -> SmbUnreachableException(endpoint, error)
        error is SMBApiException && error.status in AUTH_REJECTED -> SmbAuthException(endpoint, error)
        error is SMBApiException && error.status == NtStatus.STATUS_ACCESS_DENIED -> {
            SmbAuthException(endpoint, error)
        }

        error is SMBApiException && error.status == NtStatus.STATUS_BAD_NETWORK_NAME -> {
            SmbShareNotFoundException(endpoint, share, error)
        }

        error is SMBApiException && error.status == NtStatus.STATUS_BAD_NETWORK_PATH -> {
            SmbUnreachableException(endpoint, error)
        }

        error is IOException -> SmbUnreachableException(endpoint, error)
        else -> SmbUnreachableException(endpoint, error)
    }

    /** Failures raised while operating on a path inside an already open share. */
    fun mapOperation(error: Throwable, path: APath<*>, operation: String, write: Boolean): Throwable {
        if (error is CancellationException) return error

        val status = (error as? SMBApiException)?.status
            ?: return when (error) {
                // Already carries its own user-facing meaning
                is SmbUnreachableException, is SmbAuthException -> error
                is SmbShareNotFoundException, is SmbDialectNotSupportedException -> error
                is SmbCredentialUnavailableException -> error
                else -> wrap(error.message ?: "SMB operation failed", path, error, write)
            }

        return when (status) {
            in MISSING_PATH -> ReadException("Path does not exist", path, error)
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> PathAlreadyExistsException(path = path, cause = error)
            NtStatus.STATUS_ACCESS_DENIED, NtStatus.STATUS_CANNOT_DELETE -> PathPermissionDeniedException(
                path = path,
                operation = operation,
                reason = PathPermissionDeniedException.Reason.ACCESS_DENIED,
                cause = error,
            )

            NtStatus.STATUS_NOT_A_DIRECTORY -> ReadException("Not a directory", path, error)
            NtStatus.STATUS_FILE_IS_A_DIRECTORY -> wrap("Path is a directory", path, error, write)
            NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> WriteException("Directory is not empty", path, error)
            NtStatus.STATUS_DISK_FULL -> WriteException("Not enough space on the server", path, error)
            NtStatus.STATUS_SHARING_VIOLATION -> wrap("File is in use on the server", path, error, write)
            NtStatus.STATUS_DELETE_PENDING -> wrap("File is being deleted on the server", path, error, write)
            NtStatus.STATUS_OBJECT_NAME_INVALID -> wrap("The server rejected this name", path, error, write)
            NtStatus.STATUS_NAME_TOO_LONG -> wrap("The name is too long for the server", path, error, write)
            in AUTH_REJECTED -> SmbAuthException(path.path, error)
            else -> wrap(error.message ?: "SMB operation failed", path, error, write)
        }
    }

    private fun wrap(message: String, path: APath<*>, cause: Throwable, write: Boolean): Throwable = when {
        write -> WriteException(message, path, cause)
        else -> ReadException(message, path, cause)
    }
}
