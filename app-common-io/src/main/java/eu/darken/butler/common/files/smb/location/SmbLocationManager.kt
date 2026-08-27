package eu.darken.butler.common.files.smb.location

import eu.darken.butler.common.files.extensions.Segments
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Stores the network locations the user added, and keeps them in sync with the credential vault.
 *
 * The two live in separate databases, so every mutation follows a fixed order: writes store the
 * credential first and the location second, deletes remove the location first and the credential
 * second. A crash in between therefore only ever leaves an orphaned credential row, which
 * [SmbLocationManagerImpl] drops on the next start.
 */
interface SmbLocationManager {

    val locations: Flow<List<SmbLocation>>

    suspend fun get(id: Uuid): SmbLocation?

    /**
     * @param password required for [SmbLocation.AuthType.PASSWORD], ignored for guest locations
     */
    suspend fun create(
        label: String?,
        host: String,
        port: Int,
        share: String,
        basePath: Segments,
        domain: String?,
        username: String?,
        authType: SmbLocation.AuthType,
        rememberCredential: Boolean,
        password: CharArray?,
    ): SmbLocation

    /**
     * A null [password] keeps the stored credential, which is only valid while the username stays
     * the same: a new username with no password would silently reuse the old user's password.
     *
     * @throws IllegalArgumentException if the username changes without a password
     */
    suspend fun update(
        id: Uuid,
        label: String?,
        host: String,
        port: Int,
        share: String,
        basePath: Segments,
        domain: String?,
        username: String?,
        authType: SmbLocation.AuthType,
        rememberCredential: Boolean,
        password: CharArray?,
    ): SmbLocation

    suspend fun delete(id: Uuid)

    /**
     * Remembers that [host]:[port] answered at [at].
     *
     * The endpoint is passed along rather than looked up: a result that arrives after the user
     * edited the location describes a server it no longer stands for, and must then change nothing.
     */
    suspend fun recordSeen(id: Uuid, host: String, port: Int, at: Instant)
}
