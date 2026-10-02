package eu.darken.butler.common.files.sftp.credentials

import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialEntity
import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialsDao
import eu.darken.butler.common.files.sftp.location.SftpLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The SFTP credential vault: remembered passwords and keys encrypted at rest, session-only ones in
 * memory.
 *
 * Nothing here ever drops a location. A credential that cannot be produced surfaces as
 * [SftpCredentialUnavailableException] so the user is asked to sign in again.
 */
@Singleton
class SftpCredentialStore @Inject constructor(
    private val dao: SftpCredentialsDao,
    private val cipher: SftpCredentialCipher,
) {

    enum class Availability {
        AVAILABLE,
        MISSING,
        KEY_UNAVAILABLE,
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val sessionCredentials = ConcurrentHashMap<SessionKey, SftpCredential>()
    private val sessionRevision = MutableStateFlow(0)

    private val evictionEvents = MutableSharedFlow<Uuid>(extraBufferCapacity = 16)

    /** Emits a location id whenever its credential changed, so open sessions can be dropped. */
    val evictions: SharedFlow<Uuid> = evictionEvents.asSharedFlow()

    /**
     * Generations are kept apart in memory just like they are in the database: storing the next one
     * must not destroy the one the committed location row still points at.
     */
    private data class SessionKey(val locationId: Uuid, val credentialVersion: Int)

    /**
     * @throws SftpCredentialUnavailableException if nothing usable is stored for this location
     */
    suspend fun resolve(location: SftpLocation): SftpCredential {
        sessionCredentials[SessionKey(location.id, location.credentialVersion)]?.let { return it.copy() }

        val entity = dao.get(location.id, location.credentialVersion)
            ?: throw SftpCredentialUnavailableException(location.id, "No credential stored for this generation")

        val plaintext = cipher.decrypt(
            locationId = location.id,
            payloadVersion = entity.payloadVersion,
            envelope = CredentialCipher.Envelope(
                envelopeVersion = entity.envelopeVersion,
                keyAlias = entity.keyAlias,
                iv = entity.iv,
                ciphertext = entity.ciphertext,
            ),
        )

        return try {
            json.decodeFromString<SftpCredentialPayload>(plaintext.decodeToString()).toCredential()
        } catch (e: Exception) {
            throw SftpCredentialUnavailableException(location.id, "Credential payload is unreadable", e)
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Persists (or holds in memory) one generation of a credential and evicts the open sessions.
     * The store keeps its own copy, [credential] stays the caller's to wipe.
     *
     * Called BEFORE the matching location row is written, so a crash in between leaves an unused
     * credential row (cleaned up by [dropOtherGenerations] and [reconcile]) rather than a location
     * nobody can sign in to. Older generations survive this call for exactly that reason.
     */
    suspend fun store(
        locationId: Uuid,
        credentialVersion: Int,
        credential: SftpCredential,
        remember: Boolean,
    ) {
        log(TAG) { "store($locationId, version=$credentialVersion, remember=$remember)" }
        clearSession(SessionKey(locationId, credentialVersion))

        if (remember) {
            val plaintext = json.encodeToString(credential.toPayload()).encodeToByteArray()
            val envelope = try {
                cipher.encrypt(locationId, SftpCredentialPayload.VERSION, plaintext)
            } finally {
                plaintext.fill(0)
            }

            val now = Clock.System.now()
            val existing = dao.get(locationId, credentialVersion)
            dao.upsert(
                SftpCredentialEntity(
                    locationId = locationId,
                    credentialVersion = credentialVersion,
                    envelopeVersion = envelope.envelopeVersion,
                    payloadVersion = SftpCredentialPayload.VERSION,
                    keyAlias = envelope.keyAlias,
                    iv = envelope.iv,
                    ciphertext = envelope.ciphertext,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            )
        } else {
            dao.deleteGeneration(locationId, credentialVersion)
            sessionCredentials[SessionKey(locationId, credentialVersion)] = credential.copy()
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
     * Drops every credential no location refers to: locations that are gone (e.g. after a crash
     * between the two writes) and generations no location points at any more.
     */
    suspend fun reconcile(locations: Collection<SftpLocation>) {
        val known = locations.associateBy { it.id }
        val stale = dao.getAllOnce().filter { row ->
            known[row.locationId]?.credentialVersion != row.credentialVersion
        }
        if (stale.isNotEmpty()) {
            log(TAG, INFO) { "Dropping ${stale.size} unreferenced credential(s)" }
            stale.forEach { dao.deleteGeneration(it.locationId, it.credentialVersion) }
        }
        clearSessions { key -> known[key.locationId]?.credentialVersion != key.credentialVersion }
    }

    fun availability(locationId: Uuid): Flow<Availability> = availability(locationId, expectedVersion = null)

    /** A credential from an older generation counts as missing. */
    fun availability(location: SftpLocation): Flow<Availability> =
        availability(location.id, location.credentialVersion)

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
            it.wipe()
            sessionRevision.value++
        }
    }

    private fun clearSessions(matching: (SessionKey) -> Boolean) {
        sessionCredentials.keys.filter(matching).forEach { clearSession(it) }
    }

    private fun SftpCredential.toPayload() = when (this) {
        is SftpCredential.Password -> SftpCredentialPayload(
            username = username,
            password = String(password),
        )

        is SftpCredential.PrivateKey -> SftpCredentialPayload(
            username = username,
            privateKey = Base64.encode(keyBytes),
            passphrase = passphrase?.let { String(it) },
        )
    }

    private fun SftpCredentialPayload.toCredential(): SftpCredential = when {
        password != null && privateKey == null -> SftpCredential.Password(
            username = username,
            password = password.toCharArray(),
        )

        privateKey != null && password == null -> SftpCredential.PrivateKey(
            username = username,
            keyBytes = Base64.decode(privateKey),
            passphrase = passphrase?.toCharArray(),
        )

        else -> throw IllegalArgumentException("Payload holds neither exactly one password nor one key")
    }

    companion object {
        private val TAG = logTag("SFTP", "Credentials", "Store")
    }
}
