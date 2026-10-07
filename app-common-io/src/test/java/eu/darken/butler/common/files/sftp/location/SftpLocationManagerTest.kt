package eu.darken.butler.common.files.sftp.location

import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.sftp.credentials.SftpCredential
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialCipher
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialEntity
import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialsDao
import eu.darken.butler.common.files.sftp.location.SftpLocationManager.RetrustResult
import eu.darken.butler.common.files.sftp.location.db.SftpLocationEntity
import eu.darken.butler.common.files.sftp.location.db.SftpLocationsDao
import eu.darken.butler.common.files.sftp.testHostKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SftpLocationManagerTest : BaseTest() {

    private class FakeLocationsDao : SftpLocationsDao {
        val rows = MutableStateFlow<List<SftpLocationEntity>>(emptyList())

        /** Number of writes to accept before failing, negative means no limit. */
        var failAfterWrites: Int = -1

        override fun getAll(): Flow<List<SftpLocationEntity>> = rows
        override suspend fun get(locationId: Uuid) = rows.value.firstOrNull { it.locationId == locationId }

        override suspend fun upsert(entity: SftpLocationEntity) {
            checkWriteAllowed()
            rows.value = rows.value.filterNot { it.locationId == entity.locationId } + entity
        }

        override suspend fun delete(locationId: Uuid) {
            checkWriteAllowed()
            rows.value = rows.value.filterNot { it.locationId == locationId }
        }

        override suspend fun markSeen(locationId: Uuid, host: String, port: Int, at: Instant) {
            checkWriteAllowed()
            rows.value = rows.value.map {
                if (it.locationId == locationId && it.host == host && it.port == port) it.copy(lastSeenAt = at) else it
            }
        }

        override suspend fun retrust(
            locationId: Uuid,
            host: String,
            port: Int,
            expectedTrustRevision: Int,
            hostKeyType: String,
            hostKeyBlob: ByteArray,
            hostKeyFingerprint: String,
            updatedAt: Instant,
        ): Int {
            checkWriteAllowed()
            var changed = 0
            rows.value = rows.value.map {
                val matches = it.locationId == locationId && it.host == host && it.port == port &&
                    it.trustRevision == expectedTrustRevision
                if (matches) {
                    changed++
                    it.copy(
                        hostKeyType = hostKeyType,
                        hostKeyBlob = hostKeyBlob,
                        hostKeyFingerprint = hostKeyFingerprint,
                        trustRevision = it.trustRevision + 1,
                        updatedAt = updatedAt,
                    )
                } else {
                    it
                }
            }
            return changed
        }

        private fun checkWriteAllowed() {
            if (failAfterWrites < 0) return
            if (failAfterWrites == 0) throw IllegalStateException("Injected database failure")
            failAfterWrites--
        }
    }

    private class FakeCredentialsDao : SftpCredentialsDao {
        val rows = MutableStateFlow<List<SftpCredentialEntity>>(emptyList())

        /** Runs inside a credential write, i.e. in the middle of a create() or update(). */
        var onUpsert: (suspend () -> Unit)? = null

        /** Runs before the credential rows are read, i.e. in the middle of a reconcile(). */
        var onGetAllOnce: (suspend () -> Unit)? = null

        override fun getAll(): Flow<List<SftpCredentialEntity>> = rows
        override suspend fun getAllOnce(): List<SftpCredentialEntity> {
            onGetAllOnce?.invoke()
            return rows.value
        }
        override suspend fun get(locationId: Uuid, credentialVersion: Int) = rows.value.firstOrNull {
            it.locationId == locationId && it.credentialVersion == credentialVersion
        }

        override suspend fun upsert(entity: SftpCredentialEntity) {
            onUpsert?.invoke()
            rows.value = rows.value.filterNot {
                it.locationId == entity.locationId && it.credentialVersion == entity.credentialVersion
            } + entity
        }

        override suspend fun delete(locationId: Uuid) {
            rows.value = rows.value.filterNot { it.locationId == locationId }
        }

        override suspend fun deleteGeneration(locationId: Uuid, credentialVersion: Int) {
            rows.value = rows.value.filterNot {
                it.locationId == locationId && it.credentialVersion == credentialVersion
            }
        }

        override suspend fun deleteOtherGenerations(locationId: Uuid, keepVersion: Int) {
            rows.value = rows.value.filterNot {
                it.locationId == locationId && it.credentialVersion != keepVersion
            }
        }
    }

    /** Copies rather than aliasing the input: the store wipes the plaintext it handed over. */
    private object PlainCipher : SftpCredentialCipher {
        override fun encrypt(locationId: Uuid, payloadVersion: Int, plaintext: ByteArray) =
            CredentialCipher.Envelope(CredentialCipher.ENVELOPE_VERSION, "fake", ByteArray(0), plaintext.copyOf())

        override fun decrypt(locationId: Uuid, payloadVersion: Int, envelope: CredentialCipher.Envelope) =
            envelope.ciphertext.copyOf()

        override fun isKeyAvailable(keyAlias: String) = true
    }

    private val locationsDao = FakeLocationsDao()
    private val credentialsDao = FakeCredentialsDao()
    private val credentialStore = SftpCredentialStore(credentialsDao, PlainCipher)

    private val keyA = testHostKey(1)
    private val keyB = testHostKey(2)

    private fun manager() = SftpLocationManagerImpl(
        appScope = TestScope(),
        dao = locationsDao,
        credentialStore = credentialStore,
    )

    private suspend fun SftpLocationManager.createSample(
        password: String = "hunter2",
        remember: Boolean = true,
        basePath: String = "",
    ) = create(
        label = "NAS",
        host = "nas.local",
        port = 22,
        username = "darken",
        basePath = basePath,
        authType = SftpLocation.AuthType.PASSWORD,
        rememberCredential = remember,
        password = password.toCharArray(),
        privateKey = null,
        passphrase = null,
        hostKey = keyA,
    )

    /** Everything but the arguments given stays as stored in [location]. */
    private suspend fun SftpLocationManager.edit(
        location: SftpLocation,
        label: String? = location.label,
        host: String = location.host,
        port: Int = location.port,
        username: String = location.username,
        basePath: String = location.basePath,
        authType: SftpLocation.AuthType = location.authType,
        rememberCredential: Boolean = location.rememberCredential,
        password: String? = null,
        privateKey: ByteArray? = null,
        passphrase: String? = null,
        hostKey: TrustedHostKey? = null,
    ) = update(
        id = location.id,
        label = label,
        host = host,
        port = port,
        username = username,
        basePath = basePath,
        authType = authType,
        rememberCredential = rememberCredential,
        password = password?.toCharArray(),
        privateKey = privateKey,
        passphrase = passphrase?.toCharArray(),
        hostKey = hostKey,
    )

    private suspend fun resolvedPassword(location: SftpLocation): String {
        val credential = credentialStore.resolve(location)
        credential.shouldBeInstanceOf<SftpCredential.Password>()
        return String(credential.password)
    }

    @Test
    fun `creating stores the location, its pin and its credential`() = runTest {
        val location = manager().createSample()

        location.credentialVersion shouldBe 1
        location.trustRevision shouldBe 1
        val row = locationsDao.rows.value.single()
        row.locationId shouldBe location.id
        row.hostKeyFingerprint shouldBe keyA.fingerprint
        row.hostKeyBlob.contentEquals(keyA.blob) shouldBe true
        credentialsDao.rows.value.single().locationId shouldBe location.id
        credentialsDao.rows.value.single().credentialVersion shouldBe 1
        manager().get(location.id)!!.hostKey shouldBe keyA
    }

    @Test
    fun `a key location stores the key and its passphrase`() = runTest {
        val keyBytes = "-----BEGIN OPENSSH PRIVATE KEY-----".encodeToByteArray()
        val location = manager().create(
            label = null,
            host = "nas.local",
            port = 2222,
            username = "darken",
            basePath = "/srv",
            authType = SftpLocation.AuthType.PRIVATE_KEY,
            rememberCredential = true,
            password = null,
            privateKey = keyBytes,
            passphrase = "secret".toCharArray(),
            hostKey = keyA,
        )

        val credential = credentialStore.resolve(location)
        credential.shouldBeInstanceOf<SftpCredential.PrivateKey>()
        credential.username shouldBe "darken"
        credential.keyBytes.contentEquals(keyBytes) shouldBe true
        String(credential.passphrase!!) shouldBe "secret"
    }

    @Test
    fun `the base path is stored verbatim`() = runTest {
        val manager = manager()
        listOf("", "/srv/media", "media/movies", " spaced ").forEach { basePath ->
            val location = manager.createSample(basePath = basePath)
            manager.get(location.id)!!.basePath shouldBe basePath
        }
    }

    @Test
    fun `a base path with NUL is rejected before anything is written`() = runTest {
        shouldThrow<IllegalArgumentException> { manager().createSample(basePath = "srv\u0000x") }

        locationsDao.rows.value shouldBe emptyList()
        credentialsDao.rows.value shouldBe emptyList()
    }

    @Test
    fun `a password location refuses a key`() = runTest {
        shouldThrow<IllegalArgumentException> {
            manager().create(
                label = null,
                host = "nas.local",
                port = 22,
                username = "darken",
                basePath = "",
                authType = SftpLocation.AuthType.PASSWORD,
                rememberCredential = true,
                password = "hunter2".toCharArray(),
                privateKey = ByteArray(4),
                passphrase = null,
                hostKey = keyA,
            )
        }
        credentialsDao.rows.value shouldBe emptyList()
    }

    @Test
    fun `credentials stored while the startup reconciliation runs survive it`() = runTest {
        val scanning = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        credentialsDao.onGetAllOnce = {
            credentialsDao.onGetAllOnce = null
            scanning.complete(Unit)
            resume.await()
        }
        val manager = SftpLocationManagerImpl(
            appScope = backgroundScope,
            dao = locationsDao,
            credentialStore = credentialStore,
        )
        // The reconciliation holds its snapshot of the (still empty) location rows
        scanning.await()

        val remembered = async { manager.createSample() }
        val sessionOnly = async { manager.createSample(remember = false) }
        // Both creates get as far as they can before the reconciliation scans the credentials
        runCurrent()
        resume.complete(Unit)
        // The reconciliation finishes before anything is asserted
        runCurrent()

        resolvedPassword(remembered.await()) shouldBe "hunter2"
        resolvedPassword(sessionOnly.await()) shouldBe "hunter2"
    }

    @Test
    fun `a location row lost to a failed write leaves only an orphaned credential`() = runTest {
        val manager = manager()
        locationsDao.failAfterWrites = 0

        shouldThrow<IllegalStateException> { manager.createSample() }

        // Credential first, location second: the crash window can only produce an orphan
        locationsDao.rows.value shouldBe emptyList()
        credentialsDao.rows.value.size shouldBe 1

        credentialStore.reconcile(emptyList())
        credentialsDao.rows.value shouldBe emptyList()
    }

    @Test
    fun `deleting removes the location and its credential`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        manager.delete(location.id)

        locationsDao.rows.value shouldBe emptyList()
        credentialsDao.rows.value shouldBe emptyList()
    }

    @Test
    fun `a delete that fails on the location row keeps the credential`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        locationsDao.failAfterWrites = 0

        shouldThrow<IllegalStateException> { manager.delete(location.id) }

        locationsDao.rows.value.size shouldBe 1
        credentialsDao.rows.value.size shouldBe 1
    }

    @Test
    fun `a new password bumps the generation and retires the previous one`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        val updated = manager.edit(location, password = "newpass")

        updated.credentialVersion shouldBe 2
        credentialsDao.rows.value.map { it.credentialVersion } shouldBe listOf(2)
        resolvedPassword(updated) shouldBe "newpass"
    }

    @Test
    fun `an edit that read the row before a new password was saved keeps the new generation`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        var labelOnly: Deferred<SftpLocation>? = null
        // While the password edit holds the lock and stores generation 2, a label-only edit starts:
        // it reads the row still at generation 1, then waits for the lock
        credentialsDao.onUpsert = {
            credentialsDao.onUpsert = null
            labelOnly = async { manager.edit(location, label = "Renamed") }
            yield()
        }

        manager.edit(location, password = "newpass")
        labelOnly!!.await()

        val final = manager.get(location.id)!!
        credentialsDao.rows.value.map { it.credentialVersion } shouldBe listOf(final.credentialVersion)
        resolvedPassword(final) shouldBe "newpass"
    }

    @Test
    fun `switching to a key needs the key and bumps the generation`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        shouldThrow<IllegalArgumentException> {
            manager.edit(location, authType = SftpLocation.AuthType.PRIVATE_KEY)
        }

        val updated = manager.edit(
            location,
            authType = SftpLocation.AuthType.PRIVATE_KEY,
            privateKey = ByteArray(8) { 7 },
        )
        updated.credentialVersion shouldBe 2
        credentialStore.resolve(updated).shouldBeInstanceOf<SftpCredential.PrivateKey>()
    }

    @Test
    fun `a passphrase cannot be changed without its key`() = runTest {
        val manager = manager()
        val location = manager.create(
            label = null,
            host = "nas.local",
            port = 22,
            username = "darken",
            basePath = "",
            authType = SftpLocation.AuthType.PRIVATE_KEY,
            rememberCredential = true,
            password = null,
            privateKey = ByteArray(8),
            passphrase = null,
            hostKey = keyA,
        )

        shouldThrow<IllegalArgumentException> { manager.edit(location, passphrase = "late") }
    }

    @Test
    fun `a location write lost between the two writes keeps the credential still in use`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        locationsDao.failAfterWrites = 0

        shouldThrow<IllegalStateException> { manager.edit(location, password = "newpass") }

        // The location still points at generation 1, so signing in must keep working
        credentialsDao.rows.value.map { it.credentialVersion }.toSet() shouldBe setOf(1, 2)
        resolvedPassword(location) shouldBe "hunter2"

        credentialStore.reconcile(listOfNotNull(manager.get(location.id)))
        credentialsDao.rows.value.map { it.credentialVersion } shouldBe listOf(1)
    }

    @Test
    fun `a lost location write keeps a session-only credential usable`() = runTest {
        val manager = manager()
        val location = manager.createSample(remember = false)
        locationsDao.failAfterWrites = 0

        shouldThrow<IllegalStateException> { manager.edit(location, password = "newpass") }

        resolvedPassword(location) shouldBe "hunter2"
        credentialStore.reconcile(listOfNotNull(manager.get(location.id)))
        resolvedPassword(location) shouldBe "hunter2"
    }

    @Test
    fun `an unchanged edit keeps the stored credential and the pin`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        val updated = manager.edit(location, label = "Renamed", basePath = "/srv")

        updated.label shouldBe "Renamed"
        updated.basePath shouldBe "/srv"
        updated.credentialVersion shouldBe 1
        updated.trustRevision shouldBe 1
        updated.hostKey shouldBe keyA
        resolvedPassword(updated) shouldBe "hunter2"
    }

    @Test
    fun `changing the username without a secret is rejected`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        shouldThrow<IllegalArgumentException> { manager.edit(location, username = "someone-else") }
    }

    @Test
    fun `changing the remember setting without a secret is rejected`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        shouldThrow<IllegalArgumentException> { manager.edit(location, rememberCredential = false) }
    }

    @Test
    fun `a new endpoint without the key accepted for it is rejected before anything is written`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        val rowBefore = locationsDao.rows.value.single()

        shouldThrow<IllegalArgumentException> { manager.edit(location, host = "other.nas", password = "newpass") }
        shouldThrow<IllegalArgumentException> { manager.edit(location, port = 2222) }

        locationsDao.rows.value.single() shouldBe rowBefore
        credentialsDao.rows.value.map { it.credentialVersion } shouldBe listOf(1)
    }

    @Test
    fun `a new endpoint is stored together with its own pin`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        val updated = manager.edit(location, host = "other.nas", port = 2222, hostKey = keyB)

        updated.host shouldBe "other.nas"
        updated.port shouldBe 2222
        updated.hostKey shouldBe keyB
        updated.trustRevision shouldBe 2
        manager.get(location.id) shouldBe updated
    }

    @Test
    fun `a new endpoint serving the same key still counts as a new trust decision`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        val updated = manager.edit(location, host = "nas.lan", hostKey = keyA)

        updated.hostKey shouldBe keyA
        updated.trustRevision shouldBe 2
    }

    @Test
    fun `the unchanged pin passed for the same endpoint keeps the trust revision`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        manager.edit(location, label = "Renamed", hostKey = keyA).trustRevision shouldBe 1
        manager.edit(location, label = "Renamed", hostKey = keyB).trustRevision shouldBe 2
    }

    @Test
    fun `an endpoint moved elsewhere while an edit is saving fails the edit`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        // Another edit lands while this one encrypts its credential
        credentialsDao.onUpsert = {
            credentialsDao.onUpsert = null
            locationsDao.rows.value = locationsDao.rows.value.map {
                it.copy(host = "other.nas", hostKeyBlob = keyB.blob, hostKeyFingerprint = keyB.fingerprint)
            }
        }

        // Saving nas.local without a key would pair it with the key accepted for other.nas
        shouldThrow<IllegalStateException> { manager.edit(location, password = "newpass") }

        val row = locationsDao.rows.value.single()
        row.host shouldBe "other.nas"
        row.hostKeyFingerprint shouldBe keyB.fingerprint
    }

    @Test
    fun `a retrust confirmed while an edit is saving survives it`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        credentialsDao.onUpsert = {
            credentialsDao.onUpsert = null
            manager.retrust(location.id, "nas.local", 22, location.trustRevision, keyB)
        }

        val updated = manager.edit(location, label = "Renamed", password = "newpass")

        updated.hostKey shouldBe keyB
        updated.trustRevision shouldBe 2
        manager.get(location.id)!!.hostKey shouldBe keyB
    }

    @Test
    fun `retrust replaces the pin of the endpoint it was confirmed for`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        val result = manager.retrust(location.id, "nas.local", 22, location.trustRevision, keyB)

        result.shouldBeInstanceOf<RetrustResult.Retrusted>()
        result.location.hostKey shouldBe keyB
        result.location.trustRevision shouldBe 2
        result.location.credentialVersion shouldBe 1
        manager.get(location.id)!!.hostKey shouldBe keyB
    }

    @Test
    fun `a stale retrust cannot pin a key onto an edited endpoint`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        // The confirmation dialog was opened for nas.local, then the location was edited
        val edited = manager.edit(location, host = "other.nas", hostKey = keyB)

        val result = manager.retrust(location.id, "nas.local", 22, location.trustRevision, testHostKey(3))

        result shouldBe RetrustResult.EndpointChanged
        manager.get(location.id) shouldBe edited
    }

    @Test
    fun `a retrust confirmed against an older pin cannot replace a newer retrust`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        val keyC = testHostKey(3)
        // Both confirmations were raised while keyA was pinned at trust revision 1; the first one wins
        manager.retrust(location.id, "nas.local", 22, location.trustRevision, keyC)
            .shouldBeInstanceOf<RetrustResult.Retrusted>()

        // The second confirmation still carries trust revision 1
        manager.retrust(location.id, "nas.local", 22, location.trustRevision, keyB)

        manager.get(location.id)!!.hostKey shouldBe keyC
    }

    @Test
    fun `retrusting a deleted location reports it as gone`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        manager.delete(location.id)

        manager.retrust(location.id, "nas.local", 22, location.trustRevision, keyB) shouldBe RetrustResult.NotFound
    }

    @Test
    fun `a session-only credential is not persisted`() = runTest {
        val manager = manager()
        val location = manager.createSample(remember = false)

        credentialsDao.rows.value shouldBe emptyList()
        resolvedPassword(location) shouldBe "hunter2"
    }

    @Test
    fun `a sighting is only recorded for the endpoint it was seen at`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        val seenAt = Instant.fromEpochMilliseconds(5_000)

        manager.recordSeen(location.id, "nas.local", 22, seenAt)
        manager.get(location.id)!!.lastSeenAt shouldBe seenAt

        manager.recordSeen(location.id, "other.nas", 22, Instant.fromEpochMilliseconds(9_000))
        manager.get(location.id)!!.lastSeenAt shouldBe seenAt
    }

    @Test
    fun `editing the endpoint forgets when the location was last seen`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        manager.recordSeen(location.id, "nas.local", 22, Instant.fromEpochMilliseconds(5_000))

        val updated = manager.edit(location, host = "other.nas", hostKey = keyB)

        updated.lastSeenAt shouldBe null
        manager.get(location.id)!!.lastSeenAt shouldBe null
    }

    @Test
    fun `a sighting recorded while an edit is saving survives it`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        val seenAt = Instant.fromEpochMilliseconds(5_000)
        credentialsDao.onUpsert = {
            credentialsDao.onUpsert = null
            manager.recordSeen(location.id, "nas.local", 22, seenAt)
        }

        val updated = manager.edit(location, label = "Renamed", password = "newpass")

        updated.lastSeenAt shouldBe seenAt
        manager.get(location.id)!!.lastSeenAt shouldBe seenAt
    }
}
