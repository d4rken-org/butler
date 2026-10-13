package eu.darken.butler.common.files.webdav.credentials

import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.webdav.credentials.db.WebDavCredentialEntity
import eu.darken.butler.common.files.webdav.credentials.db.WebDavCredentialsDao
import eu.darken.butler.common.files.webdav.location.WebDavLocation
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
 * The WebDAV credential vault: remembered passwords encrypted at rest, session-only ones in memory.
 * Anonymous locations have no credential at all.
 *
 * Nothing here ever falls back to anonymous access or drops a location. A credential that cannot be
 * produced surfaces as [WebDavCredentialUnavailableException] so the user is asked to sign in again.
 */
@Singleton
class WebDavCredentialStore @Inject constructor(
    private val dao: WebDavCredentialsDao,
    private val cipher: WebDavCredentialCipher,
) {

    enum class Availability {
        AVAILABLE,
        MISSING,
        KEY_UNAVAILABLE,
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val sessionCredentials = ConcurrentHashMap<SessionKey, WebDavCredential>()
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
     * @throws IllegalArgumentException for an anonymous location, which has nothing to resolve
     * @throws WebDavCredentialUnavailableException if nothing usable is stored for this location
     */
    suspend fun resolve(location: WebDavLocation): WebDavCredential {
        require(location.authType == WebDavLocation.AuthType.BASIC) { "An anonymous location has no credential" }

        sessionCredentials[SessionKey(location.id, location.credentialVersion)]?.let { return it.copy() }

        val entity = dao.get(location.id, location.credentialVersion)
            ?: throw WebDavCredentialUnavailableException(location.id, "No credential stored for this generation")

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
            json.decodeFromString<WebDavCredentialPayload>(plaintext.decodeToString()).let {
                WebDavCredential.Password(username = it.username, password = it.password.toCharArray())
            }
        } catch (e: Exception) {
            throw WebDavCredentialUnavailableException(location.id, "Credential payload is unreadable", e)
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
        credential: WebDavCredential,
        remember: Boolean,
    ) {
        log(TAG) { "store($locationId, version=$credentialVersion, remember=$remember)" }
        clearSession(SessionKey(locationId, credentialVersion))

        if (remember) {
            val plaintext = json.encodeToString(credential.toPayload()).encodeToByteArray()
            val envelope = try {
                cipher.encrypt(locationId, WebDavCredentialPayload.VERSION, plaintext)
            } finally {
                plaintext.fill(0)
            }

            val now = Clock.System.now()
            val existing = dao.get(locationId, credentialVersion)
            dao.upsert(
                WebDavCredentialEntity(
                    locationId = locationId,
                    credentialVersion = credentialVersion,
                    envelopeVersion = envelope.envelopeVersion,
                    payloadVersion = WebDavCredentialPayload.VERSION,
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
     * between the two writes), anonymous ones, and generations no location points at any more.
     */
    suspend fun reconcile(locations: Collection<WebDavLocation>) {
        val referenced = locations
            .filter { it.authType == WebDavLocation.AuthType.BASIC }
            .associate { it.id to it.credentialVersion }
        val stale = dao.getAllOnce().filter { row -> referenced[row.locationId] != row.credentialVersion }
        if (stale.isNotEmpty()) {
            log(TAG, INFO) { "Dropping ${stale.size} unreferenced credential(s)" }
            stale.forEach { dao.deleteGeneration(it.locationId, it.credentialVersion) }
        }
        clearSessions { key -> referenced[key.locationId] != key.credentialVersion }
    }

    fun availability(locationId: Uuid): Flow<Availability> = availability(locationId, expectedVersion = null)

    /** Anonymous locations need no credential, and a credential from an older generation counts as missing. */
    fun availability(location: WebDavLocation): Flow<Availability> = when (location.authType) {
        WebDavLocation.AuthType.ANONYMOUS -> flowOf(Availability.AVAILABLE)
        WebDavLocation.AuthType.BASIC -> availability(location.id, location.credentialVersion)
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
            it.wipe()
            sessionRevision.value++
        }
    }

    private fun clearSessions(matching: (SessionKey) -> Boolean) {
        sessionCredentials.keys.filter(matching).forEach { clearSession(it) }
    }

    private fun WebDavCredential.toPayload() = when (this) {
        is WebDavCredential.Password -> WebDavCredentialPayload(
            username = username,
            password = String(password),
        )
    }

    companion object {
        private val TAG = logTag("WebDAV", "Credentials", "Store")
    }
}
