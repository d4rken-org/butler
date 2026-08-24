package eu.darken.butler.common.files.smb.credentials

import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialEntity
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialsDao
import eu.darken.butler.common.files.smb.location.SmbLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The credential vault: remembered passwords encrypted at rest, session-only passwords in memory.
 *
 * Nothing here ever falls back to guest access or drops a location. A credential that cannot be
 * produced surfaces as [SmbCredentialUnavailableException] so the user is asked to sign in again.
 */
@Singleton
class SmbCredentialStore @Inject constructor(
    private val dao: SmbCredentialsDao,
    private val cipher: SmbCredentialCipher,
) {

    enum class Availability {
        AVAILABLE,
        MISSING,
        KEY_UNAVAILABLE,
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val sessionCredentials = ConcurrentHashMap<SessionKey, SessionEntry>()
    private val sessionRevision = MutableStateFlow(0)

    private val evictionEvents = MutableSharedFlow<Uuid>(extraBufferCapacity = 16)

    /** Emits a location id whenever its credential changed, so open sessions can be dropped. */
    val evictions: SharedFlow<Uuid> = evictionEvents.asSharedFlow()

    /**
     * Generations are kept apart in memory just like they are in the database: storing the next one
     * must not destroy the one the committed location row still points at.
     */
    private data class SessionKey(val locationId: Uuid, val credentialVersion: Int)

    private class SessionEntry(
        val username: String,
        val domain: String?,
        val password: CharArray,
    )

    /**
     * @throws SmbCredentialUnavailableException if nothing usable is stored for this location
     */
    suspend fun resolve(location: SmbLocation): SmbCredential {
        sessionCredentials[SessionKey(location.id, location.credentialVersion)]
            ?.let { return SmbCredential(it.username, it.domain, it.password.copyOf()) }

        val entity = dao.get(location.id, location.credentialVersion)
            ?: throw SmbCredentialUnavailableException(location.id, "No credential stored for this generation")

        val plaintext = cipher.decrypt(
            locationId = location.id,
            payloadVersion = entity.payloadVersion,
            envelope = SmbCredentialCipher.Envelope(
                envelopeVersion = entity.envelopeVersion,
                keyAlias = entity.keyAlias,
                iv = entity.iv,
                ciphertext = entity.ciphertext,
            ),
        )

        val payload = try {
            json.decodeFromString<SmbCredentialPayload>(plaintext.decodeToString())
        } catch (e: Exception) {
            throw SmbCredentialUnavailableException(location.id, "Credential payload is unreadable", e)
        } finally {
            plaintext.fill(0)
        }

        return SmbCredential(payload.username, payload.domain, payload.password.toCharArray())
    }

    /**
     * Persists (or holds in memory) one generation of a credential and evicts the open sessions.
     *
     * Called BEFORE the matching location row is written, so a crash in between leaves an unused
     * credential row (cleaned up by [dropOtherGenerations] and [reconcile]) rather than a location
     * nobody can sign in to. Older generations survive this call for exactly that reason.
     */
    suspend fun store(
        locationId: Uuid,
        credentialVersion: Int,
        username: String,
        domain: String?,
        password: CharArray,
        remember: Boolean,
    ) {
        log(TAG) { "store($locationId, version=$credentialVersion, remember=$remember)" }
        clearSession(SessionKey(locationId, credentialVersion))

        if (remember) {
            val payload = SmbCredentialPayload(
                username = username,
                domain = domain,
                password = String(password),
            )
            val plaintext = json.encodeToString(payload).encodeToByteArray()
            val envelope = try {
                cipher.encrypt(locationId, SmbCredentialPayload.VERSION, plaintext)
            } finally {
                plaintext.fill(0)
            }

            val now = Clock.System.now()
            val existing = dao.get(locationId, credentialVersion)
            dao.upsert(
                SmbCredentialEntity(
                    locationId = locationId,
                    credentialVersion = credentialVersion,
                    envelopeVersion = envelope.envelopeVersion,
                    payloadVersion = SmbCredentialPayload.VERSION,
                    keyAlias = envelope.keyAlias,
                    iv = envelope.iv,
                    ciphertext = envelope.ciphertext,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            )
        } else {
            dao.deleteGeneration(locationId, credentialVersion)
            sessionCredentials[SessionKey(locationId, credentialVersion)] = SessionEntry(
                username = username,
                domain = domain,
                password = password.copyOf(),
            )
            sessionRevision.value++
        }

        evictionEvents.emit(locationId)
    }

    suspend fun remove(locationId: Uuid) {
        log(TAG) { "remove($locationId)" }
        dao.delete(locationId)
        clearSessions { it.locationId == locationId }
        evictionEvents.emit(locationId)
    }

    /** Retires the generations a committed location row no longer refers to, stored and in memory. */
    suspend fun dropOtherGenerations(locationId: Uuid, keepVersion: Int) {
        dao.deleteOtherGenerations(locationId, keepVersion)
        clearSessions { it.locationId == locationId && it.credentialVersion != keepVersion }
    }

    /**
     * Drops every credential row no location refers to: locations that are gone (e.g. after a crash
     * between the two writes), generations no location points at any more, and guest locations,
     * which never authenticate.
     */
    suspend fun reconcile(locations: Collection<SmbLocation>) {
        val known = locations.associateBy { it.id }
        val stale = dao.getAllOnce().filter { row ->
            val location = known[row.locationId]
            location == null ||
                location.authType == SmbLocation.AuthType.GUEST ||
                location.credentialVersion != row.credentialVersion
        }
        if (stale.isNotEmpty()) {
            log(TAG, INFO) { "Dropping ${stale.size} unreferenced credential(s)" }
            stale.forEach { dao.deleteGeneration(it.locationId, it.credentialVersion) }
        }
        clearSessions { key ->
            val location = known[key.locationId]
            location == null ||
                location.authType == SmbLocation.AuthType.GUEST ||
                location.credentialVersion != key.credentialVersion
        }
    }

    fun availability(locationId: Uuid): Flow<Availability> = availability(locationId, expectedVersion = null)

    /** Guest locations need no credential, and a credential from an older generation counts as missing. */
    fun availability(location: SmbLocation): Flow<Availability> = when (location.authType) {
        SmbLocation.AuthType.GUEST -> flowOf(Availability.AVAILABLE)
        SmbLocation.AuthType.PASSWORD -> availability(location.id, location.credentialVersion)
    }

    private fun availability(locationId: Uuid, expectedVersion: Int?): Flow<Availability> = combine(
        dao.getAll().map { entities ->
            entities.firstOrNull {
                it.locationId == locationId && (expectedVersion == null || it.credentialVersion == expectedVersion)
            }
        },
        sessionRevision,
    ) { entity, _ ->
        val hasSession = sessionCredentials.keys.any {
            it.locationId == locationId && (expectedVersion == null || it.credentialVersion == expectedVersion)
        }
        when {
            hasSession -> Availability.AVAILABLE

            entity == null -> Availability.MISSING
            !cipher.isKeyAvailable(entity.keyAlias) -> Availability.KEY_UNAVAILABLE
            else -> Availability.AVAILABLE
        }
    }.distinctUntilChanged()

    private fun clearSession(key: SessionKey) {
        sessionCredentials.remove(key)?.let {
            log(TAG, VERBOSE) { "Wiping session credential for $key" }
            it.password.fill(Char(0))
            sessionRevision.value++
        }
    }

    private fun clearSessions(matching: (SessionKey) -> Boolean) {
        sessionCredentials.keys.filter(matching).forEach { clearSession(it) }
    }

    companion object {
        private val TAG = logTag("SMB", "Credentials", "Store")
    }
}
