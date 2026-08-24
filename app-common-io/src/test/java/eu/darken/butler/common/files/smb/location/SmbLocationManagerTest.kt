package eu.darken.butler.common.files.smb.location

import eu.darken.butler.common.files.smb.credentials.SmbCredentialCipher
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.credentials.SmbCredentialUnavailableException
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialEntity
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialsDao
import eu.darken.butler.common.files.smb.location.db.SmbLocationEntity
import eu.darken.butler.common.files.smb.location.db.SmbLocationsDao
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class SmbLocationManagerTest : BaseTest() {

    private class FakeLocationsDao : SmbLocationsDao {
        val rows = MutableStateFlow<List<SmbLocationEntity>>(emptyList())

        /** Number of writes to accept before failing, negative means no limit. */
        var failAfterWrites: Int = -1

        override fun getAll(): Flow<List<SmbLocationEntity>> = rows
        override suspend fun get(locationId: Uuid) = rows.value.firstOrNull { it.locationId == locationId }

        override suspend fun upsert(entity: SmbLocationEntity) {
            checkWriteAllowed()
            rows.value = rows.value.filterNot { it.locationId == entity.locationId } + entity
        }

        override suspend fun delete(locationId: Uuid) {
            checkWriteAllowed()
            rows.value = rows.value.filterNot { it.locationId == locationId }
        }

        private fun checkWriteAllowed() {
            if (failAfterWrites < 0) return
            if (failAfterWrites == 0) throw IllegalStateException("Injected database failure")
            failAfterWrites--
        }
    }

    private class FakeCredentialsDao : SmbCredentialsDao {
        val rows = MutableStateFlow<List<SmbCredentialEntity>>(emptyList())

        override fun getAll(): Flow<List<SmbCredentialEntity>> = rows
        override suspend fun getAllOnce() = rows.value
        override suspend fun get(locationId: Uuid, credentialVersion: Int) = rows.value.firstOrNull {
            it.locationId == locationId && it.credentialVersion == credentialVersion
        }

        override suspend fun upsert(entity: SmbCredentialEntity) {
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
    private object PlainCipher : SmbCredentialCipher {
        override fun encrypt(locationId: Uuid, payloadVersion: Int, plaintext: ByteArray) =
            SmbCredentialCipher.Envelope(SmbCredentialCipher.ENVELOPE_VERSION, "fake", ByteArray(0), plaintext.copyOf())

        override fun decrypt(locationId: Uuid, payloadVersion: Int, envelope: SmbCredentialCipher.Envelope) =
            envelope.ciphertext

        override fun isKeyAvailable(keyAlias: String) = true
    }

    private val locationsDao = FakeLocationsDao()
    private val credentialsDao = FakeCredentialsDao()
    private val credentialStore = SmbCredentialStore(credentialsDao, PlainCipher)

    private fun manager() = SmbLocationManagerImpl(
        appScope = TestScope(),
        dao = locationsDao,
        credentialStore = credentialStore,
    )

    private suspend fun SmbLocationManager.createSample(
        username: String = "darken",
        password: String? = "hunter2",
        remember: Boolean = true,
    ) = create(
        label = "NAS",
        host = "nas.local",
        port = 445,
        share = "media",
        basePath = listOf("movies"),
        domain = null,
        username = username,
        authType = SmbLocation.AuthType.PASSWORD,
        rememberCredential = remember,
        password = password?.toCharArray(),
    )

    @Test
    fun `creating stores the location and its credential`() = runTest {
        val location = manager().createSample()

        locationsDao.rows.value.single().locationId shouldBe location.id
        credentialsDao.rows.value.single().locationId shouldBe location.id
        credentialsDao.rows.value.single().credentialVersion shouldBe 1
    }

    @Test
    fun `a base path round trips through the database`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        manager.get(location.id)!!.basePath shouldBe listOf("movies")
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
    fun `startup reconciliation drops orphaned credentials`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        // Simulate the location row disappearing without its credential
        locationsDao.rows.value = emptyList()

        credentialStore.reconcile(emptyList())

        credentialsDao.rows.value shouldBe emptyList()
        credentialStore.availability(location).first() shouldBe SmbCredentialStore.Availability.MISSING
    }

    @Test
    fun `deleting removes the location before its credential`() = runTest {
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

        // Nothing was removed, so the location is still usable
        locationsDao.rows.value.size shouldBe 1
        credentialsDao.rows.value.size shouldBe 1
    }

    @Test
    fun `a new password bumps the generation`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        val updated = manager.update(
            id = location.id,
            label = location.label,
            host = location.host,
            port = location.port,
            share = location.share,
            basePath = location.basePath,
            domain = null,
            username = location.username,
            authType = SmbLocation.AuthType.PASSWORD,
            rememberCredential = true,
            password = "newpass".toCharArray(),
        )

        updated.credentialVersion shouldBe 2
        credentialsDao.rows.value.single().credentialVersion shouldBe 2
        String(credentialStore.resolve(updated).password) shouldBe "newpass"
    }

    @Test
    fun `a new generation retires the previous one`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        manager.update(
            id = location.id,
            label = location.label,
            host = location.host,
            port = location.port,
            share = location.share,
            basePath = location.basePath,
            domain = null,
            username = location.username,
            authType = SmbLocation.AuthType.PASSWORD,
            rememberCredential = true,
            password = "newpass".toCharArray(),
        )

        credentialsDao.rows.value.map { it.credentialVersion } shouldBe listOf(2)
    }

    @Test
    fun `a location write lost between the two writes keeps the credential still in use`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        locationsDao.failAfterWrites = 0

        shouldThrow<IllegalStateException> {
            manager.update(
                id = location.id,
                label = location.label,
                host = location.host,
                port = location.port,
                share = location.share,
                basePath = location.basePath,
                domain = null,
                username = location.username,
                authType = SmbLocation.AuthType.PASSWORD,
                rememberCredential = true,
                password = "newpass".toCharArray(),
            )
        }

        // The location still points at generation 1, so signing in must keep working
        credentialsDao.rows.value.map { it.credentialVersion }.toSet() shouldBe setOf(1, 2)
        String(credentialStore.resolve(location).password) shouldBe "hunter2"

        credentialStore.reconcile(listOfNotNull(manager.get(location.id)))
        credentialsDao.rows.value.map { it.credentialVersion } shouldBe listOf(1)
    }

    @Test
    fun `an unchanged edit keeps the stored credential`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        val updated = manager.update(
            id = location.id,
            label = "Renamed",
            host = location.host,
            port = location.port,
            share = location.share,
            basePath = location.basePath,
            domain = null,
            username = location.username,
            authType = SmbLocation.AuthType.PASSWORD,
            rememberCredential = true,
            password = null,
        )

        updated.credentialVersion shouldBe 1
        String(credentialStore.resolve(updated).password) shouldBe "hunter2"
    }

    @Test
    fun `changing the username without a password is rejected`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        shouldThrow<IllegalArgumentException> {
            manager.update(
                id = location.id,
                label = location.label,
                host = location.host,
                port = location.port,
                share = location.share,
                basePath = location.basePath,
                domain = null,
                username = "someone-else",
                authType = SmbLocation.AuthType.PASSWORD,
                rememberCredential = true,
                password = null,
            )
        }
    }

    @Test
    fun `switching to guest drops the stored credential`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        val updated = manager.update(
            id = location.id,
            label = location.label,
            host = location.host,
            port = location.port,
            share = location.share,
            basePath = location.basePath,
            domain = null,
            username = null,
            authType = SmbLocation.AuthType.GUEST,
            rememberCredential = false,
            password = null,
        )

        credentialsDao.rows.value shouldBe emptyList()
        credentialStore.availability(updated).first() shouldBe SmbCredentialStore.Availability.AVAILABLE
    }

    @Test
    fun `a session-only credential is not persisted`() = runTest {
        val manager = manager()
        val location = manager.createSample(remember = false)

        credentialsDao.rows.value shouldBe emptyList()
        String(credentialStore.resolve(location).password) shouldBe "hunter2"
    }

    @Test
    fun `a location whose credential was never stored reports it as missing`() = runTest {
        val manager = manager()
        val location = manager.createSample(remember = false)
        credentialStore.remove(location.id)

        shouldThrow<SmbCredentialUnavailableException> { credentialStore.resolve(location) }
    }
}
