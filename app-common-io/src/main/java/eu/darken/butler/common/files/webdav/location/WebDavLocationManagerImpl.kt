package eu.darken.butler.common.files.webdav.location

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.webdav.credentials.WebDavCredential
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialStore
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager.RetrustResult
import eu.darken.butler.common.files.webdav.location.db.WebDavLocationEntity
import eu.darken.butler.common.files.webdav.location.db.WebDavLocationsDao
import eu.darken.webdav.WebDavEndpoint
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
class WebDavLocationManagerImpl @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dao: WebDavLocationsDao,
    private val credentialStore: WebDavCredentialStore,
) : WebDavLocationManager {

    override val locations: Flow<List<WebDavLocation>> = dao.getAll()
        .map { entities -> entities.map { it.toLocation() } }

    /**
     * Serializes credential writes with the start-up reconciliation, which drops every credential its
     * snapshot of the rows does not refer to: a credential is stored before the row that refers to it.
     */
    private val credentialWrites = Mutex()

    init {
        appScope.launch { reconcileCredentials() }
    }

    override suspend fun get(id: Uuid): WebDavLocation? = dao.get(id)?.toLocation()

    override suspend fun create(
        label: String?,
        url: String,
        authType: WebDavLocation.AuthType,
        username: String?,
        rememberCredential: Boolean,
        password: CharArray?,
        pinnedCertificate: TrustedCertificate?,
    ): WebDavLocation {
        requireSecretShape(authType, password)
        if (authType == WebDavLocation.AuthType.BASIC) {
            requireNotNull(password) { "A Basic location needs a password" }
        }

        val now = Clock.System.now()
        val location = WebDavLocation(
            id = Uuid.random(),
            label = label,
            url = WebDavEndpoint(url).url,
            authType = authType,
            username = username,
            rememberCredential = rememberCredential && authType == WebDavLocation.AuthType.BASIC,
            credentialVersion = 1,
            pinnedCertificate = pinnedCertificate,
            trustRevision = 1,
            createdAt = now,
            updatedAt = now,
        )
        log(TAG, INFO) { "create(): $location" }

        credentialWrites.withLock {
            if (password != null) writeCredential(location, password)
            dao.upsert(location.toEntity())
        }

        return location
    }

    override suspend fun update(
        id: Uuid,
        label: String?,
        url: String,
        authType: WebDavLocation.AuthType,
        username: String?,
        rememberCredential: Boolean,
        password: CharArray?,
        pinnedCertificate: TrustedCertificate?,
    ): WebDavLocation {
        return credentialWrites.withLock {
            val existing = get(id) ?: throw IllegalArgumentException("Unknown location: $id")
            requireSecretShape(authType, password)

            val remember = rememberCredential && authType == WebDavLocation.AuthType.BASIC
            val secretProvided = password != null
            val identityChanged = authType != existing.authType || username != existing.username
            val keepsStoredCredential = !identityChanged && remember == existing.rememberCredential
            require(authType == WebDavLocation.AuthType.ANONYMOUS || secretProvided || keepsStoredCredential) {
                "Changing the username, the sign-in method or the remember setting requires re-entering the password"
            }

            val now = Clock.System.now()
            val updated = existing.copy(
                label = label,
                url = WebDavEndpoint(url).url,
                authType = authType,
                username = username,
                rememberCredential = remember,
                credentialVersion = when {
                    secretProvided || identityChanged -> existing.credentialVersion + 1
                    else -> existing.credentialVersion
                },
                pinnedCertificate = pinnedCertificate,
                updatedAt = now,
            )
            log(TAG, INFO) { "update(): $updated" }

            // A credential has to exist before the row pointing at it, or a failed write leaves a
            // location nobody can sign in to. Anonymous access points at no credential, so the row
            // goes first and the cleanup below retires the old one.
            if (password != null) writeCredential(updated, password)

            // The trust revision and sighting are decided on the row as it is at write time: a
            // retrust or a probe can land while the credential above is being encrypted. A different
            // server was never seen at all, whatever the old one answered says nothing about it.
            val stored = dao.rewrite(id) { current ->
                val currentEndpoint = WebDavEndpoint(current.url)
                val sameOrigin = currentEndpoint.isSecure == updated.isSecure &&
                    currentEndpoint.host == updated.host &&
                    currentEndpoint.port == updated.port
                val sameServer = currentEndpoint.host == updated.host && currentEndpoint.port == updated.port
                updated.toEntity().copy(
                    trustRevision = when {
                        sameOrigin && pinnedCertificate == current.pinnedCertificate() -> current.trustRevision
                        else -> current.trustRevision + 1
                    },
                    createdAt = current.createdAt,
                    lastSeenAt = current.lastSeenAt.takeIf { sameServer },
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
        expectedUrl: String,
        expectedTrustRevision: Int,
        newCertificate: TrustedCertificate,
    ): RetrustResult {
        log(TAG, INFO) { "retrust($id, $expectedUrl, rev $expectedTrustRevision, $newCertificate)" }
        require(WebDavEndpoint(expectedUrl).isSecure) { "Only an HTTPS location can pin a certificate" }

        val changed = dao.retrust(
            locationId = id,
            url = expectedUrl,
            expectedTrustRevision = expectedTrustRevision,
            certificateDer = newCertificate.der,
            certificateFingerprint = newCertificate.fingerprint,
            updatedAt = Clock.System.now(),
        )
        val current = get(id) ?: return RetrustResult.NotFound
        return if (changed > 0) {
            RetrustResult.Retrusted(current)
        } else {
            log(TAG, WARN) { "retrust($id): now ${current.url} at rev ${current.trustRevision}, nothing pinned" }
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
        dao.markSeen(locationId = id, at = at) { row ->
            val endpoint = WebDavEndpoint(row.url)
            endpoint.host == host && endpoint.port == port
        }
    }

    private fun requireSecretShape(authType: WebDavLocation.AuthType, password: CharArray?) = when (authType) {
        WebDavLocation.AuthType.ANONYMOUS -> require(password == null) { "An anonymous location takes no password" }
        WebDavLocation.AuthType.BASIC -> Unit
    }

    private suspend fun writeCredential(location: WebDavLocation, password: CharArray) {
        credentialStore.store(
            locationId = location.id,
            credentialVersion = location.credentialVersion,
            credential = WebDavCredential.Password(
                username = requireNotNull(location.username) { "A Basic location needs a username" },
                password = password,
            ),
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

    private fun WebDavLocationEntity.pinnedCertificate(): TrustedCertificate? {
        val der = certificateDer ?: return null
        val fingerprint = checkNotNull(certificateFingerprint) { "Pinned certificate without fingerprint" }
        return TrustedCertificate(der = der, fingerprint = fingerprint)
    }

    private fun WebDavLocationEntity.toLocation() = WebDavLocation(
        id = locationId,
        label = label,
        url = url,
        authType = WebDavLocation.AuthType.valueOf(authType),
        username = username,
        rememberCredential = rememberCredential,
        credentialVersion = credentialVersion,
        pinnedCertificate = pinnedCertificate(),
        trustRevision = trustRevision,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSeenAt = lastSeenAt,
    )

    private fun WebDavLocation.toEntity() = WebDavLocationEntity(
        locationId = id,
        label = label,
        url = url,
        authType = authType.name,
        username = username,
        rememberCredential = rememberCredential,
        credentialVersion = credentialVersion,
        certificateDer = pinnedCertificate?.der,
        certificateFingerprint = pinnedCertificate?.fingerprint,
        trustRevision = trustRevision,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSeenAt = lastSeenAt,
    )

    companion object {
        private val TAG = logTag("WebDAV", "Location", "Manager")
    }
}
