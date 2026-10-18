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
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The SFTP credential vault: remembered passwords and keys encrypted at rest, session-only ones in
 * memory. A key whose passphrase is not remembered ([SftpLocation.asksForPassphrase]) is stored
 * without it, and the passphrase entered for it is only held in memory.
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
    private val heldPassphrases = ConcurrentHashMap<SessionKey, CharArray>()
    private val sessionRevision = MutableStateFlow(0)

    private val evictionEvents = MutableSharedFlow<Uuid>(extraBufferCapacity = 16)

    /** Emits a location id whenever its credential changed, so open sessions can be dropped. */
    val evictions: SharedFlow<Uuid> = evictionEvents.asSharedFlow()

    /**
     * Generations are kept apart in memory just like they are in the database: storing the next one
     * must not destroy the one the committed location row still points at.
     */
    private data class SessionKey(val locationId: Uuid, val credentialVersion: Int)

    private val SftpLocation.sessionKey: SessionKey
        get() = SessionKey(id, credentialVersion)

    /**
     * @throws SftpCredentialUnavailableException if nothing usable is stored for this location
     * @throws SftpKeyPassphraseRequiredException if the location asks for its passphrase and none is
     * held
     */
    suspend fun resolve(location: SftpLocation): SftpCredential {
        sessionCredentials[location.sessionKey]?.let { return it.copy() }

        val stored = decryptStored(location)
        if (!location.asksForPassphrase) return stored

        val key = stored.asKey(location.id)
        val held = heldPassphrases[location.sessionKey]?.copyOf()
        if (held == null) {
            key.wipe()
            throw SftpKeyPassphraseRequiredException(location.id)
        }
        return key.withPassphrase(held)
    }

    /**
     * The key of a [SftpLocation.AuthType.PRIVATE_KEY] location, e.g. to test or re-save it, with a
     * copy of [passphrase] if given, else of the passphrase held or stored for it, else none. A
     * missing passphrase is not an error here, and [passphrase] is never kept.
     *
     * @throws IllegalArgumentException if [location] signs in with a password
     * @throws SftpCredentialUnavailableException if no usable key is stored for this location
     */
    suspend fun resolveStoredKey(location: SftpLocation, passphrase: CharArray?): SftpCredential.PrivateKey {
        require(location.authType == SftpLocation.AuthType.PRIVATE_KEY) { "Not a key location: ${location.id}" }

        val key = (sessionCredentials[location.sessionKey]?.copy() ?: decryptStored(location)).asKey(location.id)
        val effective = when {
            passphrase != null -> passphrase.copyOf()
            location.asksForPassphrase -> heldPassphrases[location.sessionKey]?.copyOf()
            else -> key.passphrase?.copyOf()
        }
        return key.withPassphrase(effective)
    }

    private suspend fun decryptStored(location: SftpLocation): SftpCredential {
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
     * A remembered key with [rememberPassphrase] off is persisted without its passphrase, which is
     * held in memory instead, as if passed to [holdPassphrase].
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
        rememberPassphrase: Boolean,
    ) {
        log(TAG) {
            "store($locationId, version=$credentialVersion, remember=$remember, rememberPassphrase=$rememberPassphrase)"
        }
        val key = SessionKey(locationId, credentialVersion)
        clearSession(key)

        if (remember) {
            val heldKey = (credential as? SftpCredential.PrivateKey)?.takeIf { !rememberPassphrase }
            val plaintext = json.encodeToString(credential.toPayload(includePassphrase = heldKey == null))
                .encodeToByteArray()
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
            heldKey?.passphrase?.let { hold(key, it) }
        } else {
            dao.deleteGeneration(locationId, credentialVersion)
            sessionCredentials[key] = credential.copy()
            bumpRevision()
        }

        evictionEvents.emit(locationId)
    }

    /**
     * Keeps a copy of the passphrase entered for a key stored without one, until that generation is
     * replaced or dropped. [passphrase] stays the caller's to wipe.
     */
    fun holdPassphrase(locationId: Uuid, credentialVersion: Int, passphrase: CharArray) {
        log(TAG) { "holdPassphrase($locationId, version=$credentialVersion)" }
        hold(SessionKey(locationId, credentialVersion), passphrase)
    }

    private fun hold(key: SessionKey, passphrase: CharArray) {
        heldPassphrases.put(key, passphrase.copyOf())?.fill(Char(0))
        bumpRevision()
    }

    suspend fun remove(locationId: Uuid) {
        log(TAG) { "remove($locationId)" }
        dao.delete(locationId)
        clearSessions { it.locationId == locationId }
        evictionEvents.emit(locationId)
    }

    /** Forgets the session-only credentials of a location and evicts its sessions, stored ones stay. */
    suspend fun endSession(locationId: Uuid) {
        log(TAG) { "endSession($locationId)" }
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

    /**
     * A credential from an older generation counts as missing, and so does the key of a location that
     * asks for its passphrase while none is held.
     */
    fun availability(location: SftpLocation): Flow<Availability> = combine(
        dao.getAll().map { entities ->
            entities.firstOrNull { it.locationId == location.id && it.credentialVersion == location.credentialVersion }
        },
        sessionRevision,
    ) { entity, _ ->
        when {
            sessionCredentials.containsKey(location.sessionKey) -> Availability.AVAILABLE
            entity == null -> Availability.MISSING
            !cipher.isKeyAvailable(entity.keyAlias) -> Availability.KEY_UNAVAILABLE
            location.asksForPassphrase && !heldPassphrases.containsKey(location.sessionKey) -> Availability.MISSING
            else -> Availability.AVAILABLE
        }
    }.distinctUntilChanged()

    private fun clearSession(key: SessionKey) {
        sessionCredentials.remove(key)?.let {
            log(TAG, VERBOSE) { "Wiping session credential for $key" }
            it.wipe()
            bumpRevision()
        }
        heldPassphrases.remove(key)?.let {
            log(TAG, VERBOSE) { "Wiping held passphrase for $key" }
            it.fill(Char(0))
            bumpRevision()
        }
    }

    private fun clearSessions(matching: (SessionKey) -> Boolean) {
        (sessionCredentials.keys + heldPassphrases.keys).filter(matching).forEach { clearSession(it) }
    }

    private fun bumpRevision() = sessionRevision.update { it + 1 }

    private fun SftpCredential.asKey(locationId: Uuid): SftpCredential.PrivateKey {
        if (this is SftpCredential.PrivateKey) return this
        wipe()
        throw SftpCredentialUnavailableException(locationId, "Stored credential is not a key")
    }

    /** Takes ownership of [passphrase] and wipes the one it replaces. */
    private fun SftpCredential.PrivateKey.withPassphrase(passphrase: CharArray?): SftpCredential.PrivateKey {
        this.passphrase?.fill(Char(0))
        return SftpCredential.PrivateKey(username, keyBytes, passphrase)
    }

    private fun SftpCredential.toPayload(includePassphrase: Boolean) = when (this) {
        is SftpCredential.Password -> SftpCredentialPayload(
            username = username,
            password = String(password),
        )

        is SftpCredential.PrivateKey -> SftpCredentialPayload(
            username = username,
            privateKey = Base64.encode(keyBytes),
            passphrase = passphrase?.takeIf { includePassphrase }?.let { String(it) },
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
