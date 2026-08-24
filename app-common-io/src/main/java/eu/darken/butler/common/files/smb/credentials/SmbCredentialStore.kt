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

    private val sessionCredentials = ConcurrentHashMap<Uuid, SessionEntry>()
    private val sessionRevision = MutableStateFlow(0)

    private val evictionEvents = MutableSharedFlow<Uuid>(extraBufferCapacity = 16)

    /** Emits a location id whenever its credential changed, so open sessions can be dropped. */
    val evictions: SharedFlow<Uuid> = evictionEvents.asSharedFlow()

    private class SessionEntry(
        val credentialVersion: Int,
        val username: String,
        val domain: String?,
        val password: CharArray,
    )

    /**
     * @throws SmbCredentialUnavailableException if nothing usable is stored for this location
     */
    suspend fun resolve(location: SmbLocation): SmbCredential {
        sessionCredentials[location.id]
            ?.takeIf { it.credentialVersion == location.credentialVersion }
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
        clearSession(locationId)

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
            sessionCredentials[locationId] = SessionEntry(
                credentialVersion = credentialVersion,
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
        clearSession(locationId)
        evictionEvents.emit(locationId)
    }

    /** Retires the generations a committed location row no longer refers to. */
    suspend fun dropOtherGenerations(locationId: Uuid, keepVersion: Int) {
        dao.deleteOtherGenerations(locationId, keepVersion)
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
        sessionCredentials.keys
            .filterNot { known.containsKey(it) }
            .forEach { clearSession(it) }
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
        val session = sessionCredentials[locationId]
        when {
            session != null && (expectedVersion == null || session.credentialVersion == expectedVersion) -> {
                Availability.AVAILABLE
            }

            entity == null -> Availability.MISSING
            !cipher.isKeyAvailable(entity.keyAlias) -> Availability.KEY_UNAVAILABLE
            else -> Availability.AVAILABLE
        }
    }.distinctUntilChanged()

    private fun clearSession(locationId: Uuid) {
        sessionCredentials.remove(locationId)?.let {
            log(TAG, VERBOSE) { "Wiping session credential for $locationId" }
            it.password.fill(Char(0))
            sessionRevision.value++
        }
    }

    companion object {
        private val TAG = logTag("SMB", "Credentials", "Store")
    }
}
