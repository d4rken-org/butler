package eu.darken.butler.common.files.smb.location

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.smb.SmbLocationInput
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.db.SmbLocationEntity
import eu.darken.butler.common.files.smb.location.db.SmbLocationsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.uuid.Uuid

@Singleton
class SmbLocationManagerImpl @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dao: SmbLocationsDao,
    private val credentialStore: SmbCredentialStore,
) : SmbLocationManager {

    override val locations: Flow<List<SmbLocation>> = dao.getAll()
        .map { entities -> entities.map { it.toLocation() } }

    init {
        appScope.launch { reconcileCredentials() }
    }

    override suspend fun get(id: Uuid): SmbLocation? = dao.get(id)?.toLocation()

    override suspend fun create(
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
    ): SmbLocation {
        val now = Clock.System.now()
        val location = SmbLocation(
            id = Uuid.random(),
            label = label,
            host = host,
            port = port,
            share = share,
            basePath = basePath,
            domain = domain,
            username = username,
            authType = authType,
            rememberCredential = rememberCredential,
            credentialVersion = 1,
            createdAt = now,
            updatedAt = now,
        )
        log(TAG, INFO) { "create(): $location" }

        writeCredential(location, password)
        dao.upsert(location.toEntity())

        return location
    }

    override suspend fun update(
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
    ): SmbLocation {
        val existing = dao.get(id)?.toLocation() ?: throw IllegalArgumentException("Unknown location: $id")

        // The domain is part of the stored credential, so changing it invalidates it just like a
        // changed username does.
        val domainChanged = SmbLocationInput.normalizeDomain(domain) !=
            SmbLocationInput.normalizeDomain(existing.domain)

        val credentialChanged = password != null ||
            domainChanged ||
            authType != existing.authType ||
            rememberCredential != existing.rememberCredential

        val keepsStoredCredential = username == existing.username &&
            !domainChanged &&
            rememberCredential == existing.rememberCredential
        require(password != null || authType == SmbLocation.AuthType.GUEST || keepsStoredCredential) {
            "Changing the username, the domain or the remember setting requires re-entering the password"
        }

        val updated = existing.copy(
            label = label,
            host = host,
            port = port,
            share = share,
            basePath = basePath,
            domain = domain,
            username = username,
            authType = authType,
            rememberCredential = rememberCredential,
            credentialVersion = if (credentialChanged) existing.credentialVersion + 1 else existing.credentialVersion,
            updatedAt = Clock.System.now(),
        )
        log(TAG, INFO) { "update(): $updated" }

        when (updated.authType) {
            // A credential has to exist before the row pointing at it, or a failed write leaves a
            // location nobody can sign in to.
            SmbLocation.AuthType.PASSWORD -> {
                if (credentialChanged) writeCredential(updated, password)
                dao.upsert(updated.toEntity())
            }
            // Guest is the other way round: the row that stops referring to the credential goes
            // first, so a failed write keeps the password location signed in. An interrupted
            // cleanup is what reconcile() drops.
            SmbLocation.AuthType.GUEST -> {
                dao.upsert(updated.toEntity())
                if (credentialChanged) writeCredential(updated, password)
            }
        }
        // Only now is the predecessor unreachable: until the row above committed, it was the
        // generation the location still pointed at.
        credentialStore.dropOtherGenerations(updated.id, updated.credentialVersion)

        return updated
    }

    override suspend fun delete(id: Uuid) {
        log(TAG, INFO) { "delete(): $id" }
        dao.delete(id)
        credentialStore.remove(id)
    }

    override suspend fun setLabel(id: Uuid, label: String?) {
        val existing = dao.get(id) ?: return
        dao.upsert(existing.copy(label = label, updatedAt = Clock.System.now()))
    }

    private suspend fun writeCredential(location: SmbLocation, password: CharArray?) {
        when (location.authType) {
            SmbLocation.AuthType.GUEST -> credentialStore.remove(location.id)
            SmbLocation.AuthType.PASSWORD -> {
                requireNotNull(password) { "A password location needs a password" }
                credentialStore.store(
                    locationId = location.id,
                    credentialVersion = location.credentialVersion,
                    username = location.username.orEmpty(),
                    domain = location.domain,
                    password = password,
                    remember = location.rememberCredential,
                )
            }
        }
    }

    private suspend fun reconcileCredentials() {
        try {
            credentialStore.reconcile(dao.getAll().first().map { it.toLocation() })
        } catch (e: Exception) {
            log(TAG, WARN) { "Credential reconciliation failed: ${e.asLog()}" }
        }
    }

    private fun SmbLocationEntity.toLocation() = SmbLocation(
        id = locationId,
        label = label,
        host = host,
        port = port,
        share = share,
        basePath = SmbLocationInput.splitPath(basePath),
        domain = domain,
        username = username,
        authType = SmbLocation.AuthType.valueOf(authType),
        rememberCredential = rememberCredential,
        credentialVersion = credentialVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun SmbLocation.toEntity() = SmbLocationEntity(
        locationId = id,
        label = label,
        host = host,
        port = port,
        share = share,
        basePath = basePath.joinToString("/"),
        domain = domain,
        username = username,
        authType = authType.name,
        rememberCredential = rememberCredential,
        credentialVersion = credentialVersion,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        private val TAG = logTag("SMB", "Location", "Manager")
    }
}
