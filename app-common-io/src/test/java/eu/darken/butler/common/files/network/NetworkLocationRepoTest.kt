package eu.darken.butler.common.files.network

import android.content.Context
import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.sftp.credentials.SftpCredential
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialCipher
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialEntity
import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialsDao
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.common.files.sftp.testHostKey
import eu.darken.butler.common.files.smb.credentials.SmbCredentialCipher
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialEntity
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialsDao
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class NetworkLocationRepoTest : BaseTest() {

    private class FakeSmbLocations : SmbLocationManager {
        override val locations = MutableStateFlow<List<SmbLocation>>(emptyList())
        override suspend fun get(id: Uuid) = locations.value.firstOrNull { it.id == id }
        override suspend fun create(
            label: String?, host: String, port: Int, share: String, basePath: Segments, domain: String?,
            username: String?, authType: SmbLocation.AuthType, rememberCredential: Boolean, password: CharArray?,
        ): SmbLocation = throw NotImplementedError()

        override suspend fun update(
            id: Uuid, label: String?, host: String, port: Int, share: String, basePath: Segments, domain: String?,
            username: String?, authType: SmbLocation.AuthType, rememberCredential: Boolean, password: CharArray?,
        ): SmbLocation = throw NotImplementedError()

        override suspend fun delete(id: Uuid) = throw NotImplementedError()
        override suspend fun recordSeen(id: Uuid, host: String, port: Int, at: Instant) = throw NotImplementedError()
    }

    private class FakeSftpLocations : SftpLocationManager {
        override val locations = MutableStateFlow<List<SftpLocation>>(emptyList())
        override suspend fun get(id: Uuid) = locations.value.firstOrNull { it.id == id }
        override suspend fun create(
            label: String?, host: String, port: Int, username: String, basePath: String,
            authType: SftpLocation.AuthType, rememberCredential: Boolean, password: CharArray?,
            privateKey: ByteArray?, passphrase: CharArray?, hostKey: TrustedHostKey,
        ): SftpLocation = throw NotImplementedError()

        override suspend fun update(
            id: Uuid, label: String?, host: String, port: Int, username: String, basePath: String,
            authType: SftpLocation.AuthType, rememberCredential: Boolean, password: CharArray?,
            privateKey: ByteArray?, passphrase: CharArray?, hostKey: TrustedHostKey?,
        ): SftpLocation = throw NotImplementedError()

        override suspend fun retrust(
            id: Uuid,
            expectedHost: String,
            expectedPort: Int,
            expectedTrustRevision: Int,
            newKey: TrustedHostKey,
        ) = throw NotImplementedError()

        override suspend fun delete(id: Uuid) = throw NotImplementedError()
        override suspend fun recordSeen(id: Uuid, host: String, port: Int, at: Instant) = throw NotImplementedError()
    }

    private class FakeSmbDao : SmbCredentialsDao {
        val rows = MutableStateFlow<List<SmbCredentialEntity>>(emptyList())
        override fun getAll(): Flow<List<SmbCredentialEntity>> = rows
        override suspend fun getAllOnce() = rows.value
        override suspend fun get(locationId: Uuid, credentialVersion: Int) = rows.value.firstOrNull {
            it.locationId == locationId && it.credentialVersion == credentialVersion
        }

        override suspend fun upsert(entity: SmbCredentialEntity) {
            rows.value = rows.value + entity
        }

        override suspend fun delete(locationId: Uuid) = Unit
        override suspend fun deleteGeneration(locationId: Uuid, credentialVersion: Int) = Unit
        override suspend fun deleteOtherGenerations(locationId: Uuid, keepVersion: Int) = Unit
    }

    private class FakeSftpDao : SftpCredentialsDao {
        val rows = MutableStateFlow<List<SftpCredentialEntity>>(emptyList())
        override fun getAll(): Flow<List<SftpCredentialEntity>> = rows
        override suspend fun getAllOnce() = rows.value
        override suspend fun get(locationId: Uuid, credentialVersion: Int) = rows.value.firstOrNull {
            it.locationId == locationId && it.credentialVersion == credentialVersion
        }

        override suspend fun upsert(entity: SftpCredentialEntity) {
            rows.value = rows.value + entity
        }

        override suspend fun delete(locationId: Uuid) = Unit
        override suspend fun deleteGeneration(locationId: Uuid, credentialVersion: Int) = Unit
        override suspend fun deleteOtherGenerations(locationId: Uuid, keepVersion: Int) = Unit
    }

    private class FakeSmbCipher(var keyPresent: Boolean = true) : SmbCredentialCipher {
        override fun encrypt(locationId: Uuid, payloadVersion: Int, plaintext: ByteArray) =
            SmbCredentialCipher.Envelope(SmbCredentialCipher.ENVELOPE_VERSION, "smb", ByteArray(0), plaintext.copyOf())

        override fun decrypt(locationId: Uuid, payloadVersion: Int, envelope: SmbCredentialCipher.Envelope) =
            envelope.ciphertext.copyOf()

        override fun isKeyAvailable(keyAlias: String) = keyPresent
    }

    private class FakeSftpCipher(var keyPresent: Boolean = true) : SftpCredentialCipher {
        override fun encrypt(locationId: Uuid, payloadVersion: Int, plaintext: ByteArray) =
            CredentialCipher.Envelope(CredentialCipher.ENVELOPE_VERSION, "sftp", ByteArray(0), plaintext.copyOf())

        override fun decrypt(locationId: Uuid, payloadVersion: Int, envelope: CredentialCipher.Envelope) =
            envelope.ciphertext.copyOf()

        override fun isKeyAvailable(keyAlias: String) = keyPresent
    }

    private val context = mockk<Context>()
    private val smbLocations = FakeSmbLocations()
    private val sftpLocations = FakeSftpLocations()
    private val smbCipher = FakeSmbCipher()
    private val sftpCipher = FakeSftpCipher()
    private val smbStore = SmbCredentialStore(FakeSmbDao(), smbCipher)
    private val sftpStore = SftpCredentialStore(FakeSftpDao(), sftpCipher)

    private val repo = NetworkLocationRepo(
        smbLocationManager = smbLocations,
        sftpLocationManager = sftpLocations,
        smbCredentialStore = smbStore,
        sftpCredentialStore = sftpStore,
    )

    private val smbLocation = SmbLocation(
        id = Uuid.parse("11111111-1111-1111-1111-111111111111"),
        label = null,
        host = "nas.local",
        share = "media",
        username = "darken",
        authType = SmbLocation.AuthType.PASSWORD,
        rememberCredential = true,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(2_000),
        updatedAt = Instant.fromEpochMilliseconds(2_000),
        lastSeenAt = Instant.fromEpochMilliseconds(7_000),
    )

    private val sftpLocation = SftpLocation(
        id = Uuid.parse("22222222-2222-2222-2222-222222222222"),
        label = "Server",
        host = "ssh.local",
        port = 2222,
        username = "darken",
        basePath = "/srv",
        authType = SftpLocation.AuthType.PASSWORD,
        rememberCredential = true,
        credentialVersion = 1,
        hostKey = testHostKey(1),
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(1_000),
        updatedAt = Instant.fromEpochMilliseconds(1_000),
    )

    @Test
    fun `both protocols are listed together in the order they were added`() = runTest {
        val laterSftp = sftpLocation.copy(
            id = Uuid.parse("33333333-3333-3333-3333-333333333333"),
            createdAt = Instant.fromEpochMilliseconds(3_000),
        )
        smbLocations.locations.value = listOf(smbLocation)
        sftpLocations.locations.value = listOf(sftpLocation, laterSftp)

        repo.locations.first() shouldBe listOf(
            NetworkLocation.Sftp(sftpLocation),
            NetworkLocation.Smb(smbLocation),
            NetworkLocation.Sftp(laterSftp),
        )
    }

    @Test
    fun `the shared view exposes each protocol's own values`() = runTest {
        smbLocations.locations.value = listOf(smbLocation)
        sftpLocations.locations.value = listOf(sftpLocation)

        val (sftp, smb) = repo.locations.first()

        smb.id shouldBe smbLocation.id
        smb.displayName.get(context) shouldBe "media"
        smb.endpointLabel shouldBe "nas.local/media"
        smb.host shouldBe "nas.local"
        smb.port shouldBe 445
        smb.lastSeenAt shouldBe Instant.fromEpochMilliseconds(7_000)

        sftp.id shouldBe sftpLocation.id
        sftp.displayName.get(context) shouldBe "Server"
        sftp.endpointLabel shouldBe "darken@ssh.local:2222/srv"
        sftp.host shouldBe "ssh.local"
        sftp.port shouldBe 2222
        sftp.lastSeenAt shouldBe null
    }

    @Test
    fun `an edit in either protocol reaches the combined list`() = runTest {
        smbLocations.locations.value = listOf(smbLocation)
        repo.locations.first() shouldBe listOf(NetworkLocation.Smb(smbLocation))

        sftpLocations.locations.value = listOf(sftpLocation)
        repo.locations.first().size shouldBe 2

        smbLocations.locations.value = emptyList()
        repo.locations.first() shouldBe listOf(NetworkLocation.Sftp(sftpLocation))
    }

    @Test
    fun `availability of an SMB location comes from the SMB vault`() = runTest {
        val location = NetworkLocation.Smb(smbLocation)
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.MISSING

        smbStore.store(smbLocation.id, 1, "darken", null, "hunter2".toCharArray(), remember = true)
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.AVAILABLE

        smbCipher.keyPresent = false
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.KEY_UNAVAILABLE

        val guest = NetworkLocation.Smb(smbLocation.copy(authType = SmbLocation.AuthType.GUEST))
        repo.credentialAvailability(guest).first() shouldBe NetworkCredentialAvailability.AVAILABLE
    }

    @Test
    fun `availability of an SFTP location comes from the SFTP vault`() = runTest {
        val location = NetworkLocation.Sftp(sftpLocation)
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.MISSING

        // A credential stored for the SMB location with the same id must not count
        smbStore.store(sftpLocation.id, 1, "darken", null, "hunter2".toCharArray(), remember = true)
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.MISSING

        sftpStore.store(sftpLocation.id, 1, SftpCredential.Password("darken", "hunter2".toCharArray()), remember = true)
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.AVAILABLE

        sftpCipher.keyPresent = false
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.KEY_UNAVAILABLE
    }
}
