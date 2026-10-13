package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.errors.PathException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialUnavailableException
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.webdav.WebDavException
import eu.darken.webdav.WebDavException.Kind
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException

object WebDavStatusMapper {

    private val TAG = logTag("WebDAV", "StatusMapper")

    /** Walks the cause chain, so an already mapped failure still tells "gone" from "unreachable". */
    fun isMissing(error: Throwable): Boolean = error.causes().any { (it as? WebDavException)?.kind == Kind.MISSING }

    fun isTransportLost(error: Throwable): Boolean = when (error) {
        is WebDavException -> error.kind == Kind.TRANSPORT
        is SocketException, is SocketTimeoutException, is EOFException -> true
        else -> false
    }

    fun kindOf(error: Throwable): Kind? = error.causes().firstNotNullOfOrNull { (it as? WebDavException)?.kind }

    /** Failures of connecting to [location], which includes the TLS handshake and signing in. */
    fun mapConnect(error: Throwable, location: WebDavLocation): Throwable {
        if (error is CancellationException || isMapped(error)) return error
        val endpoint = location.endpointLabel
        val webdav = error as? WebDavException
        return when (webdav?.kind) {
            Kind.AUTHENTICATION -> WebDavAuthException(endpoint, error)
            Kind.ACCESS_DENIED -> WebDavAccessDeniedException(endpoint, error)
            Kind.CERTIFICATE_UNTRUSTED, Kind.CERTIFICATE_MISMATCH -> mapCertificate(webdav, location)
            Kind.NOT_DIRECTORY, Kind.UNSUPPORTED -> WebDavNotCollectionException(endpoint, error)
            Kind.MISSING -> ReadException("Path does not exist", WebDavPath.root(location.id), error)
            else -> WebDavUnreachableException(endpoint, error)
        }
    }

    /**
     * Without a [location] a certificate failure cannot name the pin it was judged against and is
     * reported as an unreachable server.
     */
    fun mapOperation(
        error: Throwable,
        path: WebDavPath,
        operation: String,
        write: Boolean,
        location: WebDavLocation? = null,
    ): Throwable {
        if (error is CancellationException || isMapped(error)) return error
        val webdav = error as? WebDavException
        return when (webdav?.kind) {
            Kind.MISSING -> ReadException("Path does not exist", path, error)
            Kind.CONFLICT -> ReadException("Parent folder does not exist", path.parent ?: path, error)
            Kind.ALREADY_EXISTS -> PathAlreadyExistsException(path = path, cause = error)
            Kind.ACCESS_DENIED -> PathPermissionDeniedException(
                path = path,
                operation = operation,
                reason = PathPermissionDeniedException.Reason.ACCESS_DENIED,
                cause = error,
            )

            Kind.AUTHENTICATION -> WebDavAuthException(location?.endpointLabel ?: path.path, error)
            Kind.CERTIFICATE_UNTRUSTED, Kind.CERTIFICATE_MISMATCH -> when (location) {
                null -> WebDavUnreachableException(path.path, error)
                else -> mapCertificate(webdav, location)
            }

            Kind.NOT_DIRECTORY -> ReadException("Not a directory", path, error)
            Kind.DISK_FULL -> WriteException("Not enough space on the server", path, error)
            Kind.LOCKED -> wrap("The file is locked on the server", path, error, write)
            Kind.CHANGED -> wrap("The file changed on the server", path, error, write)
            Kind.PROTOCOL -> wrap("The server sent an unexpected response", path, error, write)
            Kind.UNSUPPORTED -> wrap("The server does not support this operation", path, error, write)
            Kind.TRANSPORT -> wrap("The connection to the server was lost", path, error, write)
            Kind.OTHER -> wrap(error.message ?: "WebDAV operation failed", path, error, write)
            null -> when {
                isTransportLost(error) -> wrap("The connection to the server was lost", path, error, write)
                else -> wrap(error.message ?: "WebDAV operation failed", path, error, write)
            }
        }
    }

    private fun mapCertificate(error: WebDavException, location: WebDavLocation): Throwable {
        val presented = error.presentedCertificate
            ?: return WebDavUnreachableException(location.endpointLabel, error)
        return WebDavCertificateException(
            locationId = location.id,
            endpoint = location.endpointLabel,
            presented = WebDavCertificateInfo.from(presented),
            stored = location.pinnedCertificate?.let { pin ->
                try {
                    WebDavCertificateInfo.from(pin)
                } catch (e: IllegalArgumentException) {
                    log(TAG, WARN) { "Stored pin of ${location.id} is unreadable: ${e.asLog()}" }
                    null
                }
            },
            url = location.url,
            trustRevision = location.trustRevision,
            cause = error,
        )
    }

    /** Every Butler exception above is a [PathException]; mapping one again would only wrap it twice. */
    private fun isMapped(error: Throwable): Boolean =
        error is PathException || error is WebDavCredentialUnavailableException

    private fun wrap(message: String, path: WebDavPath, cause: Throwable, write: Boolean): IOException = when {
        write -> WriteException(message, path, cause)
        else -> ReadException(message, path, cause)
    }

    private fun Throwable.causes(): Sequence<Throwable> {
        val seen = mutableSetOf<Throwable>()
        return generateSequence(this) { it.cause }.takeWhile { seen.add(it) }
    }
}
