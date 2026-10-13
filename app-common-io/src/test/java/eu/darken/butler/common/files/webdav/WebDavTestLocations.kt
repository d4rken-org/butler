package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A pin whose 64 "DER" bytes are all [seed]; different seeds, different pins. Not a parseable
 * certificate, which nothing short of [TrustedCertificate.toServerCertificate] needs.
 */
fun testCertificate(seed: Int): TrustedCertificate {
    val der = ByteArray(64) { seed.toByte() }
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
    return TrustedCertificate(der = der, fingerprint = fingerprint)
}

fun testWebDavLocation(
    id: Uuid,
    url: String = "https://cloud.example.com/remote.php/dav/files/alice/",
    authType: WebDavLocation.AuthType = WebDavLocation.AuthType.BASIC,
    username: String? = "alice".takeIf { authType == WebDavLocation.AuthType.BASIC },
    credentialVersion: Int = 1,
    pinnedCertificate: TrustedCertificate? = null,
    trustRevision: Int = 1,
) = WebDavLocation(
    id = id,
    label = null,
    url = url,
    authType = authType,
    username = username,
    rememberCredential = false,
    credentialVersion = credentialVersion,
    pinnedCertificate = pinnedCertificate,
    trustRevision = trustRevision,
    createdAt = Instant.fromEpochMilliseconds(0),
    updatedAt = Instant.fromEpochMilliseconds(0),
)

/** Locations are edited by replacing [byId]; only reads are supported. */
class FakeWebDavLocationManager(initial: Collection<WebDavLocation>) : WebDavLocationManager {

    val byId = MutableStateFlow(initial.associateBy { it.id })

    fun put(location: WebDavLocation) {
        byId.value = byId.value + (location.id to location)
    }

    override val locations: Flow<List<WebDavLocation>> = byId.map { it.values.toList() }

    override suspend fun get(id: Uuid): WebDavLocation? = byId.value[id]

    override suspend fun create(
        label: String?,
        url: String,
        authType: WebDavLocation.AuthType,
        username: String?,
        rememberCredential: Boolean,
        password: CharArray?,
        pinnedCertificate: TrustedCertificate?,
    ): WebDavLocation = throw UnsupportedOperationException()

    override suspend fun update(
        id: Uuid,
        label: String?,
        url: String,
        authType: WebDavLocation.AuthType,
        username: String?,
        rememberCredential: Boolean,
        password: CharArray?,
        pinnedCertificate: TrustedCertificate?,
    ): WebDavLocation = throw UnsupportedOperationException()

    override suspend fun retrust(
        id: Uuid,
        expectedUrl: String,
        expectedTrustRevision: Int,
        newCertificate: TrustedCertificate,
    ): WebDavLocationManager.RetrustResult = throw UnsupportedOperationException()

    override suspend fun delete(id: Uuid) = throw UnsupportedOperationException()

    override suspend fun recordSeen(id: Uuid, host: String, port: Int, at: Instant) =
        throw UnsupportedOperationException()
}
