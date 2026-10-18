package eu.darken.butler.common.files.network

import android.content.Context
import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.sftp.SftpConnectionPool
import eu.darken.butler.common.files.sftp.credentials.SftpCredential
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialCipher
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialEntity
import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialsDao
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.location.SftpLocationManager
import eu.darken.butler.common.files.sftp.location.TrustedHostKey
import eu.darken.butler.common.files.sftp.testHostKey
import eu.darken.butler.common.files.smb.SmbConnectionPool
import eu.darken.butler.common.files.smb.credentials.SmbCredentialCipher
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialEntity
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialsDao
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import eu.darken.butler.common.files.webdav.FakeWebDavLocationManager
import eu.darken.butler.common.files.webdav.WebDavClientPool
import eu.darken.butler.common.files.webdav.credentials.WebDavCredential
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialCipher
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialStore
import eu.darken.butler.common.files.webdav.credentials.db.WebDavCredentialEntity
import eu.darken.butler.common.files.webdav.credentials.db.WebDavCredentialsDao
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
            authType: SftpLocation.AuthType, rememberCredential: Boolean, rememberPassphrase: Boolean,
            password: CharArray?, privateKey: ByteArray?, passphrase: CharArray?, hostKey: TrustedHostKey,
        ): SftpLocation = throw NotImplementedError()

        override suspend fun update(
            id: Uuid, label: String?, host: String, port: Int, username: String, basePath: String,
            authType: SftpLocation.AuthType, rememberCredential: Boolean, rememberPassphrase: Boolean,
            password: CharArray?, privateKey: ByteArray?, passphrase: CharArray?, hostKey: TrustedHostKey?,
            expectedCredentialVersion: Int?,
        ): SftpLocation = throw NotImplementedError()

        override suspend fun holdPassphrase(id: Uuid, credentialVersion: Int, passphrase: CharArray) =
            throw NotImplementedError()

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

    private class FakeWebDavDao : WebDavCredentialsDao {
        val rows = MutableStateFlow<List<WebDavCredentialEntity>>(emptyList())
        override fun getAll(): Flow<List<WebDavCredentialEntity>> = rows
        override suspend fun getAllOnce() = rows.value
        override suspend fun get(locationId: Uuid, credentialVersion: Int) = rows.value.firstOrNull {
            it.locationId == locationId && it.credentialVersion == credentialVersion
        }

        override suspend fun upsert(entity: WebDavCredentialEntity) {
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

    private class FakeWebDavCipher(var keyPresent: Boolean = true) : WebDavCredentialCipher {
        override fun encrypt(locationId: Uuid, payloadVersion: Int, plaintext: ByteArray) =
            CredentialCipher.Envelope(CredentialCipher.ENVELOPE_VERSION, "webdav", ByteArray(0), plaintext.copyOf())

        override fun decrypt(locationId: Uuid, payloadVersion: Int, envelope: CredentialCipher.Envelope) =
            envelope.ciphertext.copyOf()

        override fun isKeyAvailable(keyAlias: String) = keyPresent
    }

    private val context = mockk<Context>()
    private val smbLocations = FakeSmbLocations()
    private val sftpLocations = FakeSftpLocations()
    private val webDavLocations = FakeWebDavLocationManager(emptyList())
    private val smbCipher = FakeSmbCipher()
    private val sftpCipher = FakeSftpCipher()
    private val webDavCipher = FakeWebDavCipher()
    private val smbStore = SmbCredentialStore(FakeSmbDao(), smbCipher)
    private val sftpStore = SftpCredentialStore(FakeSftpDao(), sftpCipher)
    private val webDavStore = WebDavCredentialStore(FakeWebDavDao(), webDavCipher)
    private val smbPool = mockk<SmbConnectionPool>(relaxed = true)
    private val sftpPool = mockk<SftpConnectionPool>(relaxed = true)
    private val webDavPool = mockk<WebDavClientPool>(relaxed = true)

    private val repo = NetworkLocationRepo(
        smbLocationManager = smbLocations,
        sftpLocationManager = sftpLocations,
        webDavLocationManager = webDavLocations,
        smbCredentialStore = smbStore,
        sftpCredentialStore = sftpStore,
        webDavCredentialStore = webDavStore,
        smbConnectionPool = smbPool,
        sftpConnectionPool = sftpPool,
        webDavClientPool = webDavPool,
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

        sftpStore.store(
            sftpLocation.id,
            1,
            SftpCredential.Password("darken", "hunter2".toCharArray()),
            remember = true,
            rememberPassphrase = true,
        )
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.AVAILABLE

        sftpCipher.keyPresent = false
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.KEY_UNAVAILABLE
    }

    private val webDavLocation = WebDavLocation(
        id = Uuid.parse("44444444-4444-4444-4444-444444444444"),
        label = null,
        url = "https://cloud.example.com:8443/remote.php/dav/files/alice/",
        authType = WebDavLocation.AuthType.BASIC,
        username = "alice",
        rememberCredential = true,
        credentialVersion = 1,
        pinnedCertificate = null,
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(1_500),
        updatedAt = Instant.fromEpochMilliseconds(1_500),
        lastSeenAt = Instant.fromEpochMilliseconds(9_000),
    )

    @Test
    fun `WebDAV locations are listed with the others in the order they were added`() = runTest {
        smbLocations.locations.value = listOf(smbLocation)
        sftpLocations.locations.value = listOf(sftpLocation)
        webDavLocations.put(webDavLocation)

        repo.locations.first() shouldBe listOf(
            NetworkLocation.Sftp(sftpLocation),
            NetworkLocation.WebDav(webDavLocation),
            NetworkLocation.Smb(smbLocation),
        )

        webDavLocations.byId.value = emptyMap()
        repo.locations.first() shouldBe listOf(NetworkLocation.Sftp(sftpLocation), NetworkLocation.Smb(smbLocation))
    }

    @Test
    fun `the shared view exposes a WebDAV location's own values`() = runTest {
        webDavLocations.put(webDavLocation)

        val webDav = repo.locations.first().single()

        webDav.id shouldBe webDavLocation.id
        webDav.displayName.get(context) shouldBe "cloud.example.com"
        webDav.endpointLabel shouldBe "alice@cloud.example.com:8443/remote.php/dav/files/alice"
        webDav.host shouldBe "cloud.example.com"
        webDav.port shouldBe 8443
        webDav.lastSeenAt shouldBe Instant.fromEpochMilliseconds(9_000)
    }

    @Test
    fun `availability of a WebDAV location comes from the WebDAV vault`() = runTest {
        val location = NetworkLocation.WebDav(webDavLocation)
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.MISSING

        // A credential stored for the SFTP location with the same id must not count
        val sftpCredential = SftpCredential.Password("alice", "hunter2".toCharArray())
        sftpStore.store(webDavLocation.id, 1, sftpCredential, remember = true, rememberPassphrase = true)
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.MISSING

        val webDavCredential = WebDavCredential.Password("alice", "hunter2".toCharArray())
        webDavStore.store(webDavLocation.id, 1, webDavCredential, remember = true)
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.AVAILABLE

        webDavCipher.keyPresent = false
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.KEY_UNAVAILABLE
    }

    @Test
    fun `an anonymous WebDAV location needs no credential`() = runTest {
        val anonymous = webDavLocation.copy(
            authType = WebDavLocation.AuthType.ANONYMOUS,
            username = null,
            rememberCredential = false,
        )

        repo.credentialAvailability(NetworkLocation.WebDav(anonymous)).first() shouldBe
            NetworkCredentialAvailability.AVAILABLE
    }

    @Test
    fun `disconnecting an SMB location ends its session before evicting its pool`() = runTest {
        val location = NetworkLocation.Smb(smbLocation.copy(rememberCredential = false))
        smbStore.store(smbLocation.id, 1, "darken", null, "hunter2".toCharArray(), remember = false)
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.AVAILABLE
        var availabilityAtEviction: NetworkCredentialAvailability? = null
        coEvery { smbPool.evict(smbLocation.id) } coAnswers {
            availabilityAtEviction = repo.credentialAvailability(location).first()
        }

        repo.disconnect(location)

        availabilityAtEviction shouldBe NetworkCredentialAvailability.MISSING
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.MISSING
        coVerify(exactly = 1) { smbPool.evict(smbLocation.id) }
        coVerify(exactly = 0) { sftpPool.evict(any()) }
        coVerify(exactly = 0) { webDavPool.evict(any()) }
    }

    @Test
    fun `disconnecting an SFTP location ends its session before evicting its pool`() = runTest {
        val location = NetworkLocation.Sftp(sftpLocation.copy(rememberCredential = false))
        val credential = SftpCredential.Password("darken", "hunter2".toCharArray())
        sftpStore.store(sftpLocation.id, 1, credential, remember = false, rememberPassphrase = true)
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.AVAILABLE
        var availabilityAtEviction: NetworkCredentialAvailability? = null
        coEvery { sftpPool.evict(sftpLocation.id) } coAnswers {
            availabilityAtEviction = repo.credentialAvailability(location).first()
        }

        repo.disconnect(location)

        availabilityAtEviction shouldBe NetworkCredentialAvailability.MISSING
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.MISSING
        coVerify(exactly = 1) { sftpPool.evict(sftpLocation.id) }
        coVerify(exactly = 0) { smbPool.evict(any()) }
        coVerify(exactly = 0) { webDavPool.evict(any()) }
    }

    @Test
    fun `disconnecting a WebDAV location ends its session before evicting its pool`() = runTest {
        val location = NetworkLocation.WebDav(webDavLocation.copy(rememberCredential = false))
        val credential = WebDavCredential.Password("alice", "hunter2".toCharArray())
        webDavStore.store(webDavLocation.id, 1, credential, remember = false)
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.AVAILABLE
        var availabilityAtEviction: NetworkCredentialAvailability? = null
        coEvery { webDavPool.evict(webDavLocation.id) } coAnswers {
            availabilityAtEviction = repo.credentialAvailability(location).first()
        }

        repo.disconnect(location)

        availabilityAtEviction shouldBe NetworkCredentialAvailability.MISSING
        repo.credentialAvailability(location).first() shouldBe NetworkCredentialAvailability.MISSING
        coVerify(exactly = 1) { webDavPool.evict(webDavLocation.id) }
        coVerify(exactly = 0) { smbPool.evict(any()) }
        coVerify(exactly = 0) { sftpPool.evict(any()) }
    }

    @Test
    fun `disconnect returns only once the eviction finished`() = runTest {
        val released = CompletableDeferred<Unit>()
        coEvery { smbPool.evict(any()) } coAnswers { released.await() }
        coEvery { sftpPool.evict(any()) } coAnswers { released.await() }
        coEvery { webDavPool.evict(any()) } coAnswers { released.await() }

        val disconnects = listOf(
            NetworkLocation.Smb(smbLocation),
            NetworkLocation.Sftp(sftpLocation),
            NetworkLocation.WebDav(webDavLocation),
        ).map { launch { repo.disconnect(it) } }
        runCurrent()

        disconnects.map { it.isCompleted } shouldBe listOf(false, false, false)

        released.complete(Unit)
        runCurrent()

        disconnects.map { it.isCompleted } shouldBe listOf(true, true, true)
    }
}
