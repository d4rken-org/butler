package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.webdav.CertificatePolicy
import eu.darken.webdav.WebDavCredentials
import eu.darken.webdav.WebDavEndpoint
import eu.darken.webdav.WebDavException
import eu.darken.webdav.WebDavException.Kind
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks what the add/edit form entered before anything is stored: the server's certificate, the
 * sign-in, and that the URL is a WebDAV collection the account can open.
 */
@Singleton
class WebDavConnectionTester @Inject constructor(
    private val connectorFactory: WebDavConnectorFactory,
) {

    sealed interface Result {
        data object Success : Result

        /** Nothing was pinned and the system does not trust the chain for this host: [presented] awaits confirmation. */
        data class CertificateUntrusted(val presented: WebDavCertificateInfo) : Result

        data class CertificateMismatch(
            val expected: WebDavCertificateInfo,
            val presented: WebDavCertificateInfo,
        ) : Result

        data object AuthenticationFailed : Result

        data object AccessDenied : Result

        /** The URL names a file, or a server that does not speak WebDAV there. */
        data object NotACollection : Result

        data object Missing : Result

        data class Unreachable(val cause: Throwable) : Result

        data class Failed(val cause: Throwable) : Result
    }

    /**
     * Free for everyone, so a user can check their server before buying: it only connects, which
     * already asks for [url] as a collection, and disconnects.
     *
     * [url] must be valid as [WebDavLocationInput.parse] checks it. [username] and [password] are
     * required for [WebDavLocation.AuthType.BASIC] and ignored otherwise; the caller keeps ownership of
     * the array. A null [trustedCertificate] accepts only what the system trusts, any other value is
     * pinned.
     */
    suspend fun test(
        url: String,
        authType: WebDavLocation.AuthType,
        username: String?,
        password: CharArray?,
        trustedCertificate: TrustedCertificate?,
    ): Result {
        val endpoint = WebDavEndpoint(url)

        val credentials = when (authType) {
            WebDavLocation.AuthType.ANONYMOUS -> WebDavCredentials.Anonymous
            WebDavLocation.AuthType.BASIC -> WebDavCredentials.Basic(
                requireNotNull(username) { "A username is required" },
                requireNotNull(password) { "A password is required" },
            )
        }

        val policy = trustedCertificate
            ?.let { pin ->
                try {
                    CertificatePolicy.Pinned(pin.toServerCertificate())
                } catch (e: IllegalArgumentException) {
                    log(TAG, WARN) { "Ignoring an unreadable pin for $endpoint: ${e.asLog()}" }
                    null
                }
            }
            ?: CertificatePolicy.System

        val session = try {
            connectorFactory.create().connect(endpoint, credentials, policy)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return connectFailure(error, policy).also { log(TAG, WARN) { "$endpoint: $it - ${error.asLog()}" } }
        }
        runCatching { session.disconnect() }
        return Result.Success.also { log(TAG, INFO) { "$endpoint: $it" } }
    }

    private fun connectFailure(error: Exception, policy: CertificatePolicy): Result {
        val webdav = error as? WebDavException
            ?: return if (error is IOException) Result.Unreachable(error) else Result.Failed(error)
        val presented = webdav.presentedCertificate?.let { WebDavCertificateInfo.from(it) }
        return when (webdav.kind) {
            Kind.CERTIFICATE_UNTRUSTED -> presented?.let { Result.CertificateUntrusted(it) }
            Kind.CERTIFICATE_MISMATCH -> {
                val expected = (policy as? CertificatePolicy.Pinned)?.certificate?.let { WebDavCertificateInfo.from(it) }
                if (presented != null && expected != null) Result.CertificateMismatch(expected, presented) else null
            }

            Kind.AUTHENTICATION -> Result.AuthenticationFailed
            Kind.ACCESS_DENIED -> Result.AccessDenied
            Kind.NOT_DIRECTORY, Kind.UNSUPPORTED -> Result.NotACollection
            Kind.MISSING -> Result.Missing
            Kind.TRANSPORT -> Result.Unreachable(error)
            else -> null
        } ?: Result.Failed(error)
    }

    companion object {
        private val TAG = logTag("WebDAV", "ConnectionTester")
    }
}
