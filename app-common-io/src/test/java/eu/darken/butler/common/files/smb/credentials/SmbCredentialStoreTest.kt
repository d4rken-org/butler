package eu.darken.butler.common.files.smb.credentials

import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialEntity
import eu.darken.butler.common.files.smb.credentials.db.SmbCredentialsDao
import eu.darken.butler.common.files.smb.location.SmbLocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SmbCredentialStoreTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private val location = SmbLocation(
        id = locationId,
        label = "NAS",
        host = "nas.local",
        share = "media",
        username = "darken",
        authType = SmbLocation.AuthType.PASSWORD,
        rememberCredential = true,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    /** XORs the payload and authenticates the AAD, enough to prove the store's own contract. */
    private class FakeCipher(var keyPresent: Boolean = true) : SmbCredentialCipher {
        override fun encrypt(
            locationId: Uuid,
            payloadVersion: Int,
            plaintext: ByteArray,
        ): SmbCredentialCipher.Envelope = SmbCredentialCipher.Envelope(
            envelopeVersion = SmbCredentialCipher.ENVELOPE_VERSION,
            keyAlias = "fake",
            iv = aad(locationId, payloadVersion),
            ciphertext = plaintext.map { (it.toInt() xor 0x42).toByte() }.toByteArray(),
        )

        override fun decrypt(
            locationId: Uuid,
            payloadVersion: Int,
            envelope: SmbCredentialCipher.Envelope,
        ): ByteArray {
            if (!keyPresent) throw SmbCredentialUnavailableException(locationId, "no key")
            if (!envelope.iv.contentEquals(aad(locationId, payloadVersion))) {
                throw SmbCredentialUnavailableException(locationId, "AAD mismatch")
            }
            return envelope.ciphertext.map { (it.toInt() xor 0x42).toByte() }.toByteArray()
        }

        override fun isKeyAvailable(keyAlias: String): Boolean = keyPresent

        private fun aad(locationId: Uuid, payloadVersion: Int) =
            locationId.toByteArray() + payloadVersion.toByte()
    }

    private class FakeDao : SmbCredentialsDao {
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

    private fun create(cipher: SmbCredentialCipher = FakeCipher(), dao: SmbCredentialsDao = FakeDao()) =
        SmbCredentialStore(dao, cipher)

    @Test
    fun `remembered credential round trips`() = runTest {
        val store = create()

        store.store(locationId, 1, "darken", "WORKGROUP", "hunter2".toCharArray(), remember = true)

        val resolved = store.resolve(location)
        resolved.username shouldBe "darken"
        resolved.domain shouldBe "WORKGROUP"
        String(resolved.password) shouldBe "hunter2"
    }

    @Test
    fun `a session credential never reaches the database`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)

        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = false)

        dao.rows.value shouldBe emptyList()
        String(store.resolve(location).password) shouldBe "hunter2"
    }

    @Test
    fun `a credential from an older generation is not handed out`() = runTest {
        val store = create()
        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = true)

        shouldThrow<SmbCredentialUnavailableException> {
            store.resolve(location.copy(credentialVersion = 2))
        }
    }

    @Test
    fun `a ciphertext moved to another location does not decrypt`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = true)

        val foreignId = Uuid.parse("99999999-8888-7777-6666-555555555555")
        val stolen = dao.rows.value.single().copy(locationId = foreignId)
        dao.upsert(stolen)

        shouldThrow<SmbCredentialUnavailableException> {
            store.resolve(location.copy(id = foreignId))
        }
    }

    @Test
    fun `a missing key leaves the row intact and reports it as unavailable`() = runTest {
        val dao = FakeDao()
        val cipher = FakeCipher()
        val store = create(cipher = cipher, dao = dao)
        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = true)

        cipher.keyPresent = false

        shouldThrow<SmbCredentialUnavailableException> { store.resolve(location) }
        dao.rows.value.size shouldBe 1
        store.availability(location).first() shouldBe SmbCredentialStore.Availability.KEY_UNAVAILABLE
    }

    @Test
    fun `replacing a session credential zeroes the previous array`() = runTest {
        val store = create()
        val original = "hunter2".toCharArray()
        store.store(locationId, 1, "darken", null, original, remember = false)

        val handedOut = store.resolve(location).password
        store.store(locationId, 1, "darken", null, "other".toCharArray(), remember = false)

        // The caller's copy survives, the store's own copy is wiped
        String(handedOut) shouldBe "hunter2"
        String(store.resolve(location).password) shouldBe "other"
    }

    @Test
    fun `guest locations are always available`() = runTest {
        val store = create()
        val guest = location.copy(authType = SmbLocation.AuthType.GUEST)
        store.availability(guest).first() shouldBe SmbCredentialStore.Availability.AVAILABLE
    }

    @Test
    fun `availability reports a missing credential`() = runTest {
        val store = create()
        store.availability(location).first() shouldBe SmbCredentialStore.Availability.MISSING
    }

    @Test
    fun `reconcile drops credentials without a location`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = true)

        store.reconcile(emptyList())

        dao.rows.value shouldBe emptyList()
    }

    @Test
    fun `reconcile keeps credentials of known locations`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = true)

        store.reconcile(listOf(location))

        dao.rows.value.size shouldBe 1
    }

    @Test
    fun `a new generation leaves the one the location still points at intact`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = true)

        store.store(locationId, 2, "darken", null, "newpass".toCharArray(), remember = true)

        dao.rows.value.map { it.credentialVersion }.toSet() shouldBe setOf(1, 2)
        String(store.resolve(location).password) shouldBe "hunter2"
        String(store.resolve(location.copy(credentialVersion = 2)).password) shouldBe "newpass"
    }

    @Test
    fun `retiring a generation keeps only the current one`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = true)
        store.store(locationId, 2, "darken", null, "newpass".toCharArray(), remember = true)

        store.dropOtherGenerations(locationId, keepVersion = 2)

        dao.rows.value.map { it.credentialVersion } shouldBe listOf(2)
    }

    @Test
    fun `reconcile drops generations no location points at`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = true)
        store.store(locationId, 2, "darken", null, "newpass".toCharArray(), remember = true)

        store.reconcile(listOf(location.copy(credentialVersion = 2)))

        dao.rows.value.map { it.credentialVersion } shouldBe listOf(2)
    }

    @Test
    fun `reconcile drops credentials of guest locations`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = true)

        store.reconcile(listOf(location.copy(authType = SmbLocation.AuthType.GUEST)))

        dao.rows.value shouldBe emptyList()
    }

    @Test
    fun `removal deletes the row and the session copy`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = true)

        store.remove(locationId)

        dao.rows.value shouldBe emptyList()
        shouldThrow<SmbCredentialUnavailableException> { store.resolve(location) }
    }

    @Test
    fun `storing emits an eviction so open sessions can be dropped`() = runTest {
        val store = create()
        val evictions = mutableListOf<Uuid>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.evictions.collect { evictions.add(it) }
        }

        store.store(locationId, 1, "darken", null, "hunter2".toCharArray(), remember = true)

        evictions shouldBe listOf(locationId)
        job.cancel()
    }
}
