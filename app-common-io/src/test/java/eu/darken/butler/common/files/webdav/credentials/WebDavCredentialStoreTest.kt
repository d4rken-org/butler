package eu.darken.butler.common.files.webdav.credentials

import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.webdav.credentials.db.WebDavCredentialEntity
import eu.darken.butler.common.files.webdav.credentials.db.WebDavCredentialsDao
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.butler.common.files.webdav.testWebDavLocation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class WebDavCredentialStoreTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private val location = testWebDavLocation(locationId)

    private val anonymous = testWebDavLocation(locationId, authType = WebDavLocation.AuthType.ANONYMOUS)

    /** XORs the payload and authenticates the AAD, enough to prove the store's own contract. */
    private class FakeCipher(var keyPresent: Boolean = true) : WebDavCredentialCipher {
        var lastPlaintext: ByteArray? = null
        var lastPlaintextAtEncryption: String? = null

        override fun encrypt(
            locationId: Uuid,
            payloadVersion: Int,
            plaintext: ByteArray,
        ): CredentialCipher.Envelope {
            lastPlaintext = plaintext
            lastPlaintextAtEncryption = plaintext.decodeToString()
            return CredentialCipher.Envelope(
                envelopeVersion = CredentialCipher.ENVELOPE_VERSION,
                keyAlias = "fake",
                iv = aad(locationId, payloadVersion),
                ciphertext = plaintext.map { (it.toInt() xor 0x42).toByte() }.toByteArray(),
            )
        }

        override fun decrypt(
            locationId: Uuid,
            payloadVersion: Int,
            envelope: CredentialCipher.Envelope,
        ): ByteArray {
            if (!keyPresent) throw WebDavCredentialUnavailableException(locationId, "no key")
            if (!envelope.iv.contentEquals(aad(locationId, payloadVersion))) {
                throw WebDavCredentialUnavailableException(locationId, "AAD mismatch")
            }
            return envelope.ciphertext.map { (it.toInt() xor 0x42).toByte() }.toByteArray()
        }

        override fun isKeyAvailable(keyAlias: String): Boolean = keyPresent

        private fun aad(locationId: Uuid, payloadVersion: Int) =
            locationId.toByteArray() + payloadVersion.toByte()
    }

    private class FakeDao : WebDavCredentialsDao {
        val rows = MutableStateFlow<List<WebDavCredentialEntity>>(emptyList())

        override fun getAll(): Flow<List<WebDavCredentialEntity>> = rows
        override suspend fun getAllOnce() = rows.value
        override suspend fun get(locationId: Uuid, credentialVersion: Int) = rows.value.firstOrNull {
            it.locationId == locationId && it.credentialVersion == credentialVersion
        }

        override suspend fun upsert(entity: WebDavCredentialEntity) {
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

    private fun create(cipher: WebDavCredentialCipher = FakeCipher(), dao: WebDavCredentialsDao = FakeDao()) =
        WebDavCredentialStore(dao, cipher)

    private fun password(value: String) = WebDavCredential.Password("alice", value.toCharArray())

    private suspend fun WebDavCredentialStore.resolvedPassword(location: WebDavLocation): String {
        val credential = resolve(location)
        credential.shouldBeInstanceOf<WebDavCredential.Password>()
        return String(credential.password)
    }

    @Test
    fun `remembered password round trips`() = runTest {
        val store = create()

        store.store(locationId, 1, password("hunter2"), remember = true)

        val resolved = store.resolve(location)
        resolved.shouldBeInstanceOf<WebDavCredential.Password>()
        resolved.username shouldBe "alice"
        String(resolved.password) shouldBe "hunter2"
    }

    @Test
    fun `a password is stored in the payload shape`() = runTest {
        val cipher = FakeCipher()
        val store = create(cipher = cipher)

        store.store(locationId, 1, password("hunter2"), remember = true)

        cipher.lastPlaintextAtEncryption shouldBe """{"username":"alice","password":"hunter2"}"""
    }

    @Test
    fun `a session credential never reaches the database`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)

        store.store(locationId, 1, password("hunter2"), remember = false)

        dao.rows.value shouldBe emptyList()
        store.resolvedPassword(location) shouldBe "hunter2"
    }

    @Test
    fun `the store keeps its own copy of what it was handed`() = runTest {
        val store = create()
        val handedIn = password("hunter2")

        store.store(locationId, 1, handedIn, remember = false)
        handedIn.wipe()

        store.resolvedPassword(location) shouldBe "hunter2"
    }

    @Test
    fun `wiping a resolved credential leaves the stored one intact`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = false)

        val first = store.resolve(location) as WebDavCredential.Password
        first.wipe()

        first.password.all { it == Char(0) } shouldBe true
        store.resolvedPassword(location) shouldBe "hunter2"
    }

    @Test
    fun `the plaintext handed to the cipher is wiped`() = runTest {
        val cipher = FakeCipher()
        val store = create(cipher = cipher)

        store.store(locationId, 1, password("hunter2"), remember = true)

        cipher.lastPlaintext!!.all { it == 0.toByte() } shouldBe true
    }

    @Test
    fun `a credential never shows its password`() {
        password("hunter2").toString() shouldBe "WebDavCredential.Password(<redacted>)"
        password("hunter2").toWebDavCredentials().toString() shouldBe "Basic(<redacted>)"
    }

    @Test
    fun `a resolved credential converts to the library's Basic credentials`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = true)

        val basic = store.resolve(location).toWebDavCredentials()

        basic.username shouldBe "alice"
        String(basic.password) shouldBe "hunter2"
    }

    @Test
    fun `an anonymous location has nothing to resolve`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = true)

        shouldThrow<IllegalArgumentException> { store.resolve(anonymous) }
    }

    @Test
    fun `a credential from an older generation is not handed out`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = true)

        shouldThrow<WebDavCredentialUnavailableException> {
            store.resolve(location.copy(credentialVersion = 2))
        }
    }

    @Test
    fun `a ciphertext moved to another location does not decrypt`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true)

        val foreignId = Uuid.parse("99999999-8888-7777-6666-555555555555")
        dao.upsert(dao.rows.value.single().copy(locationId = foreignId))

        shouldThrow<WebDavCredentialUnavailableException> {
            store.resolve(location.copy(id = foreignId))
        }
    }

    @Test
    fun `a payload without a password is unreadable`() = runTest {
        val dao = FakeDao()
        val cipher = FakeCipher()
        val store = create(cipher = cipher, dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true)
        val forged = """{"username":"alice"}""".encodeToByteArray()
        val envelope = cipher.encrypt(locationId, WebDavCredentialPayload.VERSION, forged)
        dao.upsert(dao.rows.value.single().copy(ciphertext = envelope.ciphertext))

        shouldThrow<WebDavCredentialUnavailableException> { store.resolve(location) }
    }

    @Test
    fun `a missing key leaves the row intact and reports it as unavailable`() = runTest {
        val dao = FakeDao()
        val cipher = FakeCipher()
        val store = create(cipher = cipher, dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true)

        cipher.keyPresent = false

        shouldThrow<WebDavCredentialUnavailableException> { store.resolve(location) }
        dao.rows.value.size shouldBe 1
        store.availability(location).first() shouldBe WebDavCredentialStore.Availability.KEY_UNAVAILABLE
    }

    @Test
    fun `replacing a session credential leaves copies already handed out intact`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = false)

        val handedOut = store.resolvedPassword(location)
        store.store(locationId, 1, password("other"), remember = false)

        handedOut shouldBe "hunter2"
        store.resolvedPassword(location) shouldBe "other"
    }

    @Test
    fun `availability reports a missing credential`() = runTest {
        create().availability(location).first() shouldBe WebDavCredentialStore.Availability.MISSING
    }

    @Test
    fun `availability reports stored and session credentials`() = runTest {
        val store = create()

        store.store(locationId, 1, password("hunter2"), remember = true)
        store.availability(location).first() shouldBe WebDavCredentialStore.Availability.AVAILABLE

        store.store(locationId, 2, password("hunter2"), remember = false)
        store.availability(location.copy(credentialVersion = 2)).first() shouldBe
            WebDavCredentialStore.Availability.AVAILABLE
        store.availability(location.copy(credentialVersion = 3)).first() shouldBe
            WebDavCredentialStore.Availability.MISSING
    }

    @Test
    fun `an anonymous location is always available`() = runTest {
        val cipher = FakeCipher(keyPresent = false)

        create(cipher = cipher).availability(anonymous).first() shouldBe WebDavCredentialStore.Availability.AVAILABLE
    }

    @Test
    fun `reconcile drops credentials without a location`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true)

        store.reconcile(emptyList())

        dao.rows.value shouldBe emptyList()
    }

    @Test
    fun `reconcile drops session credentials without a location`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = false)

        store.reconcile(emptyList())

        shouldThrow<WebDavCredentialUnavailableException> { store.resolve(location) }
    }

    @Test
    fun `reconcile keeps credentials of known locations`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true)

        store.reconcile(listOf(location))

        dao.rows.value.size shouldBe 1
    }

    @Test
    fun `reconcile drops credentials of anonymous locations`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true)
        store.store(locationId, 2, password("hunter2"), remember = false)

        store.reconcile(listOf(anonymous.copy(credentialVersion = 1)))

        dao.rows.value shouldBe emptyList()
        shouldThrow<WebDavCredentialUnavailableException> { store.resolve(location.copy(credentialVersion = 2)) }
    }

    @Test
    fun `reconcile drops generations no location points at`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true)
        store.store(locationId, 2, password("newpass"), remember = true)

        store.reconcile(listOf(location.copy(credentialVersion = 2)))

        dao.rows.value.map { it.credentialVersion } shouldBe listOf(2)
    }

    @Test
    fun `a new generation leaves the one the location still points at intact`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true)

        store.store(locationId, 2, password("newpass"), remember = true)

        dao.rows.value.map { it.credentialVersion }.toSet() shouldBe setOf(1, 2)
        store.resolvedPassword(location) shouldBe "hunter2"
        store.resolvedPassword(location.copy(credentialVersion = 2)) shouldBe "newpass"
    }

    @Test
    fun `retiring a session generation keeps only the current one`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = false)
        store.store(locationId, 2, password("newpass"), remember = false)

        store.dropOtherGenerations(locationId, keepVersion = 2)

        shouldThrow<WebDavCredentialUnavailableException> { store.resolve(location) }
        store.resolvedPassword(location.copy(credentialVersion = 2)) shouldBe "newpass"
    }

    @Test
    fun `retiring a stored generation keeps only the current one`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true)
        store.store(locationId, 2, password("newpass"), remember = true)

        store.dropOtherGenerations(locationId, keepVersion = 2)

        dao.rows.value.map { it.credentialVersion } shouldBe listOf(2)
    }

    @Test
    fun `removal deletes the row and the session copy`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true)
        store.store(locationId, 2, password("hunter2"), remember = false)

        store.remove(locationId)

        dao.rows.value shouldBe emptyList()
        shouldThrow<WebDavCredentialUnavailableException> { store.resolve(location) }
        shouldThrow<WebDavCredentialUnavailableException> { store.resolve(location.copy(credentialVersion = 2)) }
    }

    @Test
    fun `storing emits an eviction so open sessions can be dropped`() = runTest {
        val store = create()
        val evictions = mutableListOf<Uuid>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.evictions.collect { evictions.add(it) }
        }

        store.store(locationId, 1, password("hunter2"), remember = true)

        evictions shouldBe listOf(locationId)
        job.cancel()
    }

    @Test
    fun `ending a session forgets the session credential and emits an eviction`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = false)
        val evictions = mutableListOf<Uuid>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.evictions.collect { evictions.add(it) }
        }

        store.endSession(locationId)

        store.availability(location).first() shouldBe WebDavCredentialStore.Availability.MISSING
        shouldThrow<WebDavCredentialUnavailableException> { store.resolve(location) }
        evictions shouldBe listOf(locationId)
        job.cancel()
    }

    @Test
    fun `ending a session keeps a remembered credential`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true)

        store.endSession(locationId)

        dao.rows.value.size shouldBe 1
        store.availability(location).first() shouldBe WebDavCredentialStore.Availability.AVAILABLE
        store.resolvedPassword(location) shouldBe "hunter2"
    }
}
