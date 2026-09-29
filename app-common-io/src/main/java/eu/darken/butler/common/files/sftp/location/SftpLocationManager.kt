package eu.darken.butler.common.files.sftp.location

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Stores the SSH servers the user added, and keeps them in sync with the credential vault.
 *
 * The two live in separate databases, so every mutation follows a fixed order: writes store the
 * credential first and the location second, deletes remove the location first and the credential
 * second. A crash in between therefore only ever leaves an orphaned credential row, which
 * [SftpLocationManagerImpl] drops on the next start.
 *
 * Secrets are passed as `(password)` for [SftpLocation.AuthType.PASSWORD] and as
 * `(privateKey, passphrase)` for [SftpLocation.AuthType.PRIVATE_KEY], where `privateKey` is the key
 * file's bytes. The caller keeps ownership of every array and may wipe it once the call returned.
 */
interface SftpLocationManager {

    val locations: Flow<List<SftpLocation>>

    suspend fun get(id: Uuid): SftpLocation?

    /**
     * @param hostKey the key the user accepted for [host]:[port]
     * @throws IllegalArgumentException if the secret does not match [authType]
     */
    suspend fun create(
        label: String?,
        host: String,
        port: Int,
        username: String,
        basePath: String,
        authType: SftpLocation.AuthType,
        rememberCredential: Boolean,
        password: CharArray?,
        privateKey: ByteArray?,
        passphrase: CharArray?,
        hostKey: TrustedHostKey,
    ): SftpLocation

    /**
     * A null secret keeps the stored credential, which is only valid while the username, the
     * authentication type and the remember setting stay the same. A passphrase alone cannot be
     * changed, it comes with its key.
     *
     * @param hostKey required when [host] or [port] differ from the stored endpoint: the key the user
     * accepted for the NEW endpoint. May be null otherwise, which keeps the current pin.
     * @throws IllegalArgumentException if the endpoint changes without a host key, or the secret is
     * missing or does not match [authType]
     */
    suspend fun update(
        id: Uuid,
        label: String?,
        host: String,
        port: Int,
        username: String,
        basePath: String,
        authType: SftpLocation.AuthType,
        rememberCredential: Boolean,
        password: CharArray?,
        privateKey: ByteArray?,
        passphrase: CharArray?,
        hostKey: TrustedHostKey?,
    ): SftpLocation

    /**
     * Pins [newKey] after the user accepted a changed host key for [expectedHost]:[expectedPort],
     * the endpoint the confirmation was shown for, judged against the pin of [expectedTrustRevision].
     */
    suspend fun retrust(
        id: Uuid,
        expectedHost: String,
        expectedPort: Int,
        expectedTrustRevision: Int,
        newKey: TrustedHostKey,
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
        data class Retrusted(val location: SftpLocation) : RetrustResult

        /**
         * The location was edited to point at another server, or its pin changed since the confirmation
         * was shown: the confirmed key was judged against a pin that is no longer stored.
         */
        data object EndpointChanged : RetrustResult

        data object NotFound : RetrustResult
    }
}
