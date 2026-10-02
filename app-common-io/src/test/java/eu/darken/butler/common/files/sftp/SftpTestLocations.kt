package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun testSftpLocation(
    id: Uuid,
    host: String = "nas.local",
    port: Int = SftpLocation.DEFAULT_PORT,
    username: String = "darken",
    basePath: String = "",
    authType: SftpLocation.AuthType = SftpLocation.AuthType.PASSWORD,
    credentialVersion: Int = 1,
    hostKey: TrustedHostKey = testHostKey(1),
    trustRevision: Int = 1,
) = SftpLocation(
    id = id,
    label = null,
    host = host,
    port = port,
    username = username,
    basePath = basePath,
    authType = authType,
    rememberCredential = false,
    credentialVersion = credentialVersion,
    hostKey = hostKey,
    trustRevision = trustRevision,
    createdAt = Instant.fromEpochMilliseconds(0),
    updatedAt = Instant.fromEpochMilliseconds(0),
)

/** Locations are edited by replacing [byId]; only reads are supported. */
class FakeSftpLocationManager(initial: Collection<SftpLocation>) : SftpLocationManager {

    val byId = MutableStateFlow(initial.associateBy { it.id })

    fun put(location: SftpLocation) {
        byId.value = byId.value + (location.id to location)
    }

    override val locations: Flow<List<SftpLocation>> = byId.map { it.values.toList() }

    override suspend fun get(id: Uuid): SftpLocation? = byId.value[id]

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
    ): SftpLocation = throw UnsupportedOperationException()

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
    ): SftpLocation = throw UnsupportedOperationException()

    override suspend fun retrust(
        id: Uuid,
        expectedHost: String,
        expectedPort: Int,
        expectedTrustRevision: Int,
        newKey: TrustedHostKey,
    ): SftpLocationManager.RetrustResult = throw UnsupportedOperationException()

    override suspend fun delete(id: Uuid) = throw UnsupportedOperationException()

    override suspend fun recordSeen(id: Uuid, host: String, port: Int, at: Instant) =
        throw UnsupportedOperationException()
}
