package eu.darken.butler.common.files.sftp.location

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.sftp.credentials.SftpCredential
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.location.SftpLocationManager.RetrustResult
import eu.darken.butler.common.files.sftp.location.db.SftpLocationEntity
import eu.darken.butler.common.files.sftp.location.db.SftpLocationsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Singleton
class SftpLocationManagerImpl @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dao: SftpLocationsDao,
    private val credentialStore: SftpCredentialStore,
) : SftpLocationManager {

    override val locations: Flow<List<SftpLocation>> = dao.getAll()
        .map { entities -> entities.map { it.toLocation() } }

    /**
     * Serializes credential writes with the start-up reconciliation, which drops every credential its
     * snapshot of the rows does not refer to: a credential is stored before the row that refers to it.
     */
    private val credentialWrites = Mutex()

    init {
        appScope.launch { reconcileCredentials() }
    }

    override suspend fun get(id: Uuid): SftpLocation? = dao.get(id)?.toLocation()

    override suspend fun create(
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
    ): SftpLocation {
        requireSecretShape(authType, password, privateKey, passphrase)

        val now = Clock.System.now()
        val location = SftpLocation(
            id = Uuid.random(),
            label = label,
            host = host,
            port = port,
            username = username,
            basePath = basePath,
            authType = authType,
            rememberCredential = rememberCredential,
            credentialVersion = 1,
            hostKey = hostKey,
            trustRevision = 1,
            createdAt = now,
            updatedAt = now,
        )
        log(TAG, INFO) { "create(): $location" }

        credentialWrites.withLock {
            writeCredential(location, password, privateKey, passphrase)
            dao.upsert(location.toEntity())
        }

        return location
    }

    override suspend fun update(
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
    ): SftpLocation {
        return credentialWrites.withLock {
            val existing = get(id) ?: throw IllegalArgumentException("Unknown location: $id")
            requireSecretShape(authType, password, privateKey, passphrase)

            require(hostKey != null || (host == existing.host && port == existing.port)) {
                "A new endpoint needs the host key accepted for it"
            }

            val secretProvided = password != null || privateKey != null
            val keepsStoredCredential = username == existing.username &&
                authType == existing.authType &&
                rememberCredential == existing.rememberCredential
            require(secretProvided || keepsStoredCredential) {
                "Changing the username, the sign-in method or the remember setting requires re-entering the secret"
            }

            val now = Clock.System.now()
            val updated = existing.copy(
                label = label,
                host = host,
                port = port,
                username = username,
                basePath = basePath,
                authType = authType,
                rememberCredential = rememberCredential,
                credentialVersion = if (secretProvided) existing.credentialVersion + 1 else existing.credentialVersion,
                hostKey = hostKey ?: existing.hostKey,
                updatedAt = now,
            )
            log(TAG, INFO) { "update(): $updated" }

            // A credential has to exist before the row pointing at it, or a failed write leaves a
            // location nobody can sign in to.
            if (secretProvided) writeCredential(updated, password, privateKey, passphrase)

            // The pin, trust revision and sighting are decided on the row as it is at write time: a
            // retrust or a probe can land while the credential above is being encrypted. A different
            // server was never seen at all, whatever the old one answered says nothing about it.
            val stored = dao.rewrite(id) { current ->
                val sameEndpoint = current.host == host && current.port == port
                check(sameEndpoint || hostKey != null) {
                    "The endpoint changed while saving, its host key is unconfirmed"
                }
                val pin = hostKey ?: current.hostKey()
                updated.toEntity().copy(
                    hostKeyType = pin.type,
                    hostKeyBlob = pin.blob,
                    hostKeyFingerprint = pin.fingerprint,
                    trustRevision = when {
                        sameEndpoint && pin == current.hostKey() -> current.trustRevision
                        else -> current.trustRevision + 1
                    },
                    createdAt = current.createdAt,
                    lastSeenAt = current.lastSeenAt.takeIf { sameEndpoint },
                )
            }?.toLocation() ?: throw IllegalArgumentException("Unknown location: $id")

            // Only now is the predecessor unreachable: until the row above committed, it was the
            // generation the location still pointed at.
            credentialStore.dropOtherGenerations(stored.id, stored.credentialVersion)
            stored
        }
    }

    override suspend fun retrust(
        id: Uuid,
        expectedHost: String,
        expectedPort: Int,
        expectedTrustRevision: Int,
        newKey: TrustedHostKey,
    ): RetrustResult {
        log(TAG, INFO) { "retrust($id, $expectedHost:$expectedPort, rev $expectedTrustRevision, $newKey)" }
        val changed = dao.retrust(
            locationId = id,
            host = expectedHost,
            port = expectedPort,
            expectedTrustRevision = expectedTrustRevision,
            hostKeyType = newKey.type,
            hostKeyBlob = newKey.blob,
            hostKeyFingerprint = newKey.fingerprint,
            updatedAt = Clock.System.now(),
        )
        val current = get(id) ?: return RetrustResult.NotFound
        return if (changed > 0) {
            RetrustResult.Retrusted(current)
        } else {
            log(TAG, WARN) {
                "retrust($id): now ${current.host}:${current.port} at rev ${current.trustRevision}, nothing pinned"
            }
            RetrustResult.EndpointChanged
        }
    }

    override suspend fun delete(id: Uuid) {
        log(TAG, INFO) { "delete(): $id" }
        credentialWrites.withLock {
            dao.delete(id)
            credentialStore.remove(id)
        }
    }

    override suspend fun recordSeen(id: Uuid, host: String, port: Int, at: Instant) {
        dao.markSeen(locationId = id, host = host, port = port, at = at)
    }

    private fun requireSecretShape(
        authType: SftpLocation.AuthType,
        password: CharArray?,
        privateKey: ByteArray?,
        passphrase: CharArray?,
    ) = when (authType) {
        SftpLocation.AuthType.PASSWORD -> require(privateKey == null && passphrase == null) {
            "A password location takes no key"
        }

        SftpLocation.AuthType.PRIVATE_KEY -> {
            require(password == null) { "A key location takes no password" }
            require(passphrase == null || privateKey != null) { "A passphrase comes with its key" }
        }
    }

    private suspend fun writeCredential(
        location: SftpLocation,
        password: CharArray?,
        privateKey: ByteArray?,
        passphrase: CharArray?,
    ) {
        val credential = when (location.authType) {
            SftpLocation.AuthType.PASSWORD -> SftpCredential.Password(
                username = location.username,
                password = requireNotNull(password) { "A password location needs a password" },
            )

            SftpLocation.AuthType.PRIVATE_KEY -> SftpCredential.PrivateKey(
                username = location.username,
                keyBytes = requireNotNull(privateKey) { "A key location needs a key" },
                passphrase = passphrase,
            )
        }
        credentialStore.store(
            locationId = location.id,
            credentialVersion = location.credentialVersion,
            credential = credential,
            remember = location.rememberCredential,
        )
    }

    private suspend fun reconcileCredentials() = credentialWrites.withLock {
        try {
            credentialStore.reconcile(dao.getAll().first().map { it.toLocation() })
        } catch (e: Exception) {
            log(TAG, WARN) { "Credential reconciliation failed: ${e.asLog()}" }
        }
    }

    private fun SftpLocationEntity.hostKey() = TrustedHostKey(
        type = hostKeyType,
        blob = hostKeyBlob,
        fingerprint = hostKeyFingerprint,
    )

    private fun SftpLocationEntity.toLocation() = SftpLocation(
        id = locationId,
        label = label,
        host = host,
        port = port,
        username = username,
        basePath = basePath,
        authType = SftpLocation.AuthType.valueOf(authType),
        rememberCredential = rememberCredential,
        credentialVersion = credentialVersion,
        hostKey = hostKey(),
        trustRevision = trustRevision,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSeenAt = lastSeenAt,
    )

    private fun SftpLocation.toEntity() = SftpLocationEntity(
        locationId = id,
        label = label,
        host = host,
        port = port,
        username = username,
        basePath = basePath,
        authType = authType.name,
        rememberCredential = rememberCredential,
        credentialVersion = credentialVersion,
        hostKeyType = hostKey.type,
        hostKeyBlob = hostKey.blob,
        hostKeyFingerprint = hostKey.fingerprint,
        trustRevision = trustRevision,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSeenAt = lastSeenAt,
    )

    companion object {
        private val TAG = logTag("SFTP", "Location", "Manager")
    }
}
