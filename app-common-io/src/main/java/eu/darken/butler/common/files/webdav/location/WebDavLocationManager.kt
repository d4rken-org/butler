package eu.darken.butler.common.files.webdav.location

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Stores the WebDAV servers the user added, and keeps them in sync with the credential vault.
 *
 * The two live in separate databases, so every mutation follows a fixed order: writes store the
 * credential first and the location second, deletes remove the location first and the credential
 * second. A crash in between therefore only ever leaves an orphaned credential row, which
 * [WebDavLocationManagerImpl] drops on the next start.
 *
 * URLs are stored normalized as [eu.darken.webdav.WebDavEndpoint.url] produces them, `username` is
 * null for [WebDavLocation.AuthType.ANONYMOUS], whose `rememberCredential` is ignored. The caller
 * keeps ownership of the password array and may wipe it once the call returned.
 */
interface WebDavLocationManager {

    val locations: Flow<List<WebDavLocation>>

    suspend fun get(id: Uuid): WebDavLocation?

    /**
     * @param password required for [WebDavLocation.AuthType.BASIC], null for anonymous access
     * @param pinnedCertificate the certificate the user accepted in the connection test, null when
     * the system trusted the server or [url] is plain HTTP
     * @throws IllegalArgumentException if the URL, username or password do not fit [authType]
     */
    suspend fun create(
        label: String?,
        url: String,
        authType: WebDavLocation.AuthType,
        username: String?,
        rememberCredential: Boolean,
        password: CharArray?,
        pinnedCertificate: TrustedCertificate?,
    ): WebDavLocation

    /**
     * A null password keeps the stored credential, which is only valid while the username, the
     * authentication type and the remember setting stay the same. Switching to anonymous access
     * needs no password and retires the stored one.
     *
     * @param pinnedCertificate always the new pin, as decided by the connection test the edit ran
     * against [url]; null removes the current one
     * @throws IllegalArgumentException if the location is unknown, or the password is missing or
     * does not fit [authType]
     */
    suspend fun update(
        id: Uuid,
        label: String?,
        url: String,
        authType: WebDavLocation.AuthType,
        username: String?,
        rememberCredential: Boolean,
        password: CharArray?,
        pinnedCertificate: TrustedCertificate?,
    ): WebDavLocation

    /**
     * Pins [newCertificate] after the user accepted it for [expectedUrl], the URL the confirmation was
     * shown for, judged against the pin of [expectedTrustRevision]. Serves both an untrusted
     * certificate with nothing pinned yet and one that replaced the pin.
     *
     * @throws IllegalArgumentException if [expectedUrl] is not an HTTPS URL
     */
    suspend fun retrust(
        id: Uuid,
        expectedUrl: String,
        expectedTrustRevision: Int,
        newCertificate: TrustedCertificate,
    ): RetrustResult

    suspend fun delete(id: Uuid)

    /**
     * Remembers that [host]:[port] answered at [at].
     *
     * The endpoint is passed along rather than looked up: a result that arrives after the user
     * edited the location describes a server it no longer stands for, and must then change nothing.
     */
    suspend fun recordSeen(id: Uuid, host: String, port: Int, at: Instant)

    sealed interface RetrustResult {
        data class Retrusted(val location: WebDavLocation) : RetrustResult

        /**
         * The location was edited to another URL, or its pin changed since the confirmation was shown:
         * the confirmed certificate was judged against a pin that is no longer stored.
         */
        data object EndpointChanged : RetrustResult

        data object NotFound : RetrustResult
    }
}
