package eu.darken.butler.common.files.sftp.credentials

import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialEntity
import eu.darken.butler.common.files.sftp.credentials.db.SftpCredentialsDao
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.sftp.testHostKey
import eu.darken.ssh.SshCredentials
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SftpCredentialStoreTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-2222-3333-4444-555555555555")

    private val location = SftpLocation(
        id = locationId,
        label = "NAS",
        host = "nas.local",
        username = "darken",
        authType = SftpLocation.AuthType.PASSWORD,
        rememberCredential = true,
        credentialVersion = 1,
        hostKey = testHostKey(1),
        trustRevision = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private val keyBytes = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n".encodeToByteArray()

    /** XORs the payload and authenticates the AAD, enough to prove the store's own contract. */
    private class FakeCipher(var keyPresent: Boolean = true) : SftpCredentialCipher {
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
            if (!keyPresent) throw SftpCredentialUnavailableException(locationId, "no key")
            if (!envelope.iv.contentEquals(aad(locationId, payloadVersion))) {
                throw SftpCredentialUnavailableException(locationId, "AAD mismatch")
            }
            return envelope.ciphertext.map { (it.toInt() xor 0x42).toByte() }.toByteArray()
        }

        override fun isKeyAvailable(keyAlias: String): Boolean = keyPresent

        private fun aad(locationId: Uuid, payloadVersion: Int) =
            locationId.toByteArray() + payloadVersion.toByte()
    }

    private class FakeDao : SftpCredentialsDao {
        val rows = MutableStateFlow<List<SftpCredentialEntity>>(emptyList())

        /** Deletes leave the rows in place, so what the store keeps in memory can be checked on its own. */
        var keepRows = false

        override fun getAll(): Flow<List<SftpCredentialEntity>> = rows
        override suspend fun getAllOnce() = rows.value
        override suspend fun get(locationId: Uuid, credentialVersion: Int) = rows.value.firstOrNull {
            it.locationId == locationId && it.credentialVersion == credentialVersion
        }

        override suspend fun upsert(entity: SftpCredentialEntity) {
            rows.value = rows.value.filterNot {
                it.locationId == entity.locationId && it.credentialVersion == entity.credentialVersion
            } + entity
        }

        override suspend fun delete(locationId: Uuid) {
            if (keepRows) return
            rows.value = rows.value.filterNot { it.locationId == locationId }
        }

        override suspend fun deleteGeneration(locationId: Uuid, credentialVersion: Int) {
            if (keepRows) return
            rows.value = rows.value.filterNot {
                it.locationId == locationId && it.credentialVersion == credentialVersion
            }
        }

        override suspend fun deleteOtherGenerations(locationId: Uuid, keepVersion: Int) {
            if (keepRows) return
            rows.value = rows.value.filterNot {
                it.locationId == locationId && it.credentialVersion != keepVersion
            }
        }
    }

    private fun create(cipher: SftpCredentialCipher = FakeCipher(), dao: SftpCredentialsDao = FakeDao()) =
        SftpCredentialStore(dao, cipher)

    private fun password(value: String) = SftpCredential.Password("darken", value.toCharArray())

    private fun key(passphrase: String? = null) =
        SftpCredential.PrivateKey("darken", keyBytes.copyOf(), passphrase?.toCharArray())

    private suspend fun SftpCredentialStore.resolvedPassword(location: SftpLocation): String {
        val credential = resolve(location)
        credential.shouldBeInstanceOf<SftpCredential.Password>()
        return String(credential.password)
    }

    @Test
    fun `remembered password round trips`() = runTest {
        val store = create()

        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)

        val resolved = store.resolve(location)
        resolved.shouldBeInstanceOf<SftpCredential.Password>()
        resolved.username shouldBe "darken"
        String(resolved.password) shouldBe "hunter2"
    }

    @Test
    fun `remembered key and passphrase round trip`() = runTest {
        val store = create()

        store.store(locationId, 1, key("secret"), remember = true, rememberPassphrase = true)

        val resolved = store.resolve(location)
        resolved.shouldBeInstanceOf<SftpCredential.PrivateKey>()
        resolved.username shouldBe "darken"
        resolved.keyBytes.contentEquals(keyBytes) shouldBe true
        String(resolved.passphrase!!) shouldBe "secret"
    }

    @Test
    fun `remembered key without a passphrase round trips`() = runTest {
        val store = create()

        store.store(locationId, 1, key(), remember = true, rememberPassphrase = true)

        val resolved = store.resolve(location)
        resolved.shouldBeInstanceOf<SftpCredential.PrivateKey>()
        resolved.keyBytes.contentEquals(keyBytes) shouldBe true
        resolved.passphrase shouldBe null
    }

    @Test
    fun `a session credential never reaches the database`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)

        store.store(locationId, 1, key("secret"), remember = false, rememberPassphrase = true)

        dao.rows.value shouldBe emptyList()
        val resolved = store.resolve(location)
        resolved.shouldBeInstanceOf<SftpCredential.PrivateKey>()
        resolved.keyBytes.contentEquals(keyBytes) shouldBe true
        String(resolved.passphrase!!) shouldBe "secret"
    }

    @Test
    fun `the store keeps its own copy of what it was handed`() = runTest {
        val store = create()
        val handedIn = SftpCredential.PrivateKey("darken", keyBytes.copyOf(), "secret".toCharArray())

        store.store(locationId, 1, handedIn, remember = false, rememberPassphrase = true)
        handedIn.wipe()

        val resolved = store.resolve(location)
        resolved.shouldBeInstanceOf<SftpCredential.PrivateKey>()
        resolved.keyBytes.contentEquals(keyBytes) shouldBe true
        String(resolved.passphrase!!) shouldBe "secret"
    }

    @Test
    fun `wiping a resolved credential leaves the stored one intact`() = runTest {
        val store = create()
        store.store(locationId, 1, key("secret"), remember = false, rememberPassphrase = true)

        val first = store.resolve(location) as SftpCredential.PrivateKey
        first.wipe()

        first.keyBytes.all { it == 0.toByte() } shouldBe true
        first.passphrase!!.all { it == Char(0) } shouldBe true
        val second = store.resolve(location) as SftpCredential.PrivateKey
        second.keyBytes.contentEquals(keyBytes) shouldBe true
    }

    @Test
    fun `the plaintext handed to the cipher is wiped`() = runTest {
        val cipher = FakeCipher()
        val store = create(cipher = cipher)

        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)

        cipher.lastPlaintext!!.all { it == 0.toByte() } shouldBe true
    }

    @Test
    fun `a key is stored as base64 of the key file`() = runTest {
        val cipher = FakeCipher()
        val store = create(cipher = cipher)

        store.store(
            locationId,
            1,
            SftpCredential.PrivateKey("darken", byteArrayOf(1, 2, 3, -1)),
            remember = true,
            rememberPassphrase = true,
        )

        cipher.lastPlaintextAtEncryption shouldBe """{"username":"darken","privateKey":"AQID/w=="}"""
    }

    @Test
    fun `a resolved credential converts to the SSH library's credentials`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)
        store.store(locationId, 2, key("secret"), remember = true, rememberPassphrase = true)

        val viaPassword = store.resolve(location).toSshCredentials()
        viaPassword.shouldBeInstanceOf<SshCredentials.Password>()
        viaPassword.username shouldBe "darken"
        String(viaPassword.password) shouldBe "hunter2"

        val viaKey = store.resolve(location.copy(credentialVersion = 2)).toSshCredentials()
        viaKey.shouldBeInstanceOf<SshCredentials.PrivateKey>()
        viaKey.username shouldBe "darken"
        viaKey.keyBytes.contentEquals(keyBytes) shouldBe true
        String(viaKey.passphrase!!) shouldBe "secret"
    }

    @Test
    fun `a credential from an older generation is not handed out`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)

        shouldThrow<SftpCredentialUnavailableException> {
            store.resolve(location.copy(credentialVersion = 2))
        }
    }

    @Test
    fun `a ciphertext moved to another location does not decrypt`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)

        val foreignId = Uuid.parse("99999999-8888-7777-6666-555555555555")
        dao.upsert(dao.rows.value.single().copy(locationId = foreignId))

        shouldThrow<SftpCredentialUnavailableException> {
            store.resolve(location.copy(id = foreignId))
        }
    }

    @Test
    fun `a payload holding both a password and a key is unreadable`() = runTest {
        val dao = FakeDao()
        val cipher = FakeCipher()
        val store = create(cipher = cipher, dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)
        val forged = """{"username":"darken","password":"a","privateKey":"AAAA"}""".encodeToByteArray()
        val envelope = cipher.encrypt(locationId, SftpCredentialPayload.VERSION, forged)
        dao.upsert(dao.rows.value.single().copy(ciphertext = envelope.ciphertext))

        shouldThrow<SftpCredentialUnavailableException> { store.resolve(location) }
    }

    @Test
    fun `a missing key leaves the row intact and reports it as unavailable`() = runTest {
        val dao = FakeDao()
        val cipher = FakeCipher()
        val store = create(cipher = cipher, dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)

        cipher.keyPresent = false

        shouldThrow<SftpCredentialUnavailableException> { store.resolve(location) }
        dao.rows.value.size shouldBe 1
        store.availability(location).first() shouldBe SftpCredentialStore.Availability.KEY_UNAVAILABLE
    }

    @Test
    fun `replacing a session credential leaves copies already handed out intact`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = false, rememberPassphrase = true)

        val handedOut = store.resolvedPassword(location)
        store.store(locationId, 1, password("other"), remember = false, rememberPassphrase = true)

        handedOut shouldBe "hunter2"
        store.resolvedPassword(location) shouldBe "other"
    }

    @Test
    fun `availability reports a missing credential`() = runTest {
        create().availability(location).first() shouldBe SftpCredentialStore.Availability.MISSING
    }

    @Test
    fun `availability reports stored and session credentials`() = runTest {
        val store = create()

        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)
        store.availability(location).first() shouldBe SftpCredentialStore.Availability.AVAILABLE

        store.store(locationId, 2, password("hunter2"), remember = false, rememberPassphrase = true)
        store.availability(location.copy(credentialVersion = 2)).first() shouldBe
            SftpCredentialStore.Availability.AVAILABLE
        store.availability(location.copy(credentialVersion = 3)).first() shouldBe
            SftpCredentialStore.Availability.MISSING
    }

    @Test
    fun `reconcile drops credentials without a location`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)

        store.reconcile(emptyList())

        dao.rows.value shouldBe emptyList()
    }

    @Test
    fun `reconcile drops session credentials without a location`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = false, rememberPassphrase = true)

        store.reconcile(emptyList())

        shouldThrow<SftpCredentialUnavailableException> { store.resolve(location) }
    }

    @Test
    fun `reconcile keeps credentials of known locations`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)

        store.reconcile(listOf(location))

        dao.rows.value.size shouldBe 1
    }

    @Test
    fun `reconcile drops generations no location points at`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)
        store.store(locationId, 2, password("newpass"), remember = true, rememberPassphrase = true)

        store.reconcile(listOf(location.copy(credentialVersion = 2)))

        dao.rows.value.map { it.credentialVersion } shouldBe listOf(2)
    }

    @Test
    fun `a new generation leaves the one the location still points at intact`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)

        store.store(locationId, 2, password("newpass"), remember = true, rememberPassphrase = true)

        dao.rows.value.map { it.credentialVersion }.toSet() shouldBe setOf(1, 2)
        store.resolvedPassword(location) shouldBe "hunter2"
        store.resolvedPassword(location.copy(credentialVersion = 2)) shouldBe "newpass"
    }

    @Test
    fun `retiring a session generation keeps only the current one`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = false, rememberPassphrase = true)
        store.store(locationId, 2, password("newpass"), remember = false, rememberPassphrase = true)

        store.dropOtherGenerations(locationId, keepVersion = 2)

        shouldThrow<SftpCredentialUnavailableException> { store.resolve(location) }
        store.resolvedPassword(location.copy(credentialVersion = 2)) shouldBe "newpass"
    }

    @Test
    fun `retiring a stored generation keeps only the current one`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)
        store.store(locationId, 2, password("newpass"), remember = true, rememberPassphrase = true)

        store.dropOtherGenerations(locationId, keepVersion = 2)

        dao.rows.value.map { it.credentialVersion } shouldBe listOf(2)
    }

    @Test
    fun `removal deletes the row and the session copy`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)
        store.store(locationId, 2, password("hunter2"), remember = false, rememberPassphrase = true)

        store.remove(locationId)

        dao.rows.value shouldBe emptyList()
        shouldThrow<SftpCredentialUnavailableException> { store.resolve(location) }
        shouldThrow<SftpCredentialUnavailableException> { store.resolve(location.copy(credentialVersion = 2)) }
    }

    @Test
    fun `storing emits an eviction so open sessions can be dropped`() = runTest {
        val store = create()
        val evictions = mutableListOf<Uuid>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.evictions.collect { evictions.add(it) }
        }

        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)

        evictions shouldBe listOf(locationId)
        job.cancel()
    }

    // region Keys that ask for their passphrase

    private val askingLocation = location.copy(authType = SftpLocation.AuthType.PRIVATE_KEY, rememberPassphrase = false)

    private val keyBase64 = Base64.encode(keyBytes)

    private suspend fun SftpCredentialStore.resolvedPassphrase(location: SftpLocation): String? {
        val credential = resolve(location)
        credential.shouldBeInstanceOf<SftpCredential.PrivateKey>()
        return credential.passphrase?.concatToString()
    }

    @Test
    fun `a key asking for its passphrase is stored without it, which is held in memory`() = runTest {
        val cipher = FakeCipher()
        val store = create(cipher = cipher)

        store.store(locationId, 1, key("secret"), remember = true, rememberPassphrase = false)

        cipher.lastPlaintextAtEncryption shouldBe """{"username":"darken","privateKey":"$keyBase64"}"""
        store.resolvedPassphrase(askingLocation) shouldBe "secret"
    }

    @Test
    fun `each resolve hands out its own copy of the held passphrase`() = runTest {
        val store = create()
        store.store(locationId, 1, key("secret"), remember = true, rememberPassphrase = false)

        val first = store.resolve(askingLocation)
        first.wipe()

        store.resolvedPassphrase(askingLocation) shouldBe "secret"
    }

    @Test
    fun `a key asking for its passphrase is not resolved without one`() = runTest {
        val store = create()
        store.store(locationId, 1, key(), remember = true, rememberPassphrase = false)

        val error = shouldThrow<SftpKeyPassphraseRequiredException> { store.resolve(askingLocation) }
        error.locationId shouldBe locationId
    }

    @Test
    fun `a held passphrase is a copy of the one handed in`() = runTest {
        val store = create()
        store.store(locationId, 1, key(), remember = true, rememberPassphrase = false)
        val typed = "typed".toCharArray()

        store.holdPassphrase(locationId, 1, typed)
        typed.fill(Char(0))

        store.resolvedPassphrase(askingLocation) shouldBe "typed"
    }

    @Test
    fun `a passphrase is only held for its own generation`() = runTest {
        val store = create()
        store.store(locationId, 1, key(), remember = true, rememberPassphrase = false)
        store.store(locationId, 2, key(), remember = true, rememberPassphrase = false)

        store.holdPassphrase(locationId, 2, "typed".toCharArray())

        shouldThrow<SftpKeyPassphraseRequiredException> { store.resolve(askingLocation) }
        store.resolvedPassphrase(askingLocation.copy(credentialVersion = 2)) shouldBe "typed"
    }

    @Test
    fun `the stored key comes with the passphrase given, else the held one, else none`() = runTest {
        val store = create()
        store.store(locationId, 1, key(), remember = true, rememberPassphrase = false)

        store.resolveStoredKey(askingLocation, passphrase = null).let {
            it.keyBytes.contentEquals(keyBytes) shouldBe true
            it.passphrase shouldBe null
        }

        val typed = "typed".toCharArray()
        val withTyped = store.resolveStoredKey(askingLocation, passphrase = typed)
        typed.fill(Char(0))
        withTyped.passphrase!!.concatToString() shouldBe "typed"

        store.holdPassphrase(locationId, 1, "held".toCharArray())
        store.resolveStoredKey(askingLocation, passphrase = null).passphrase!!.concatToString() shouldBe "held"
        store.resolveStoredKey(askingLocation, "typed".toCharArray()).passphrase!!.concatToString() shouldBe "typed"
    }

    @Test
    fun `a passphrase given for the stored key is never kept`() = runTest {
        val store = create()
        store.store(locationId, 1, key(), remember = true, rememberPassphrase = false)

        store.resolveStoredKey(askingLocation, "typed".toCharArray())

        shouldThrow<SftpKeyPassphraseRequiredException> { store.resolve(askingLocation) }
        store.availability(askingLocation).first() shouldBe SftpCredentialStore.Availability.MISSING
    }

    @Test
    fun `the stored key of a location that remembers its passphrase comes with it`() = runTest {
        val store = create()
        val keyLocation = askingLocation.copy(rememberPassphrase = true)
        store.store(locationId, 1, key("secret"), remember = true, rememberPassphrase = true)
        store.store(locationId, 2, key("session"), remember = false, rememberPassphrase = true)

        store.resolveStoredKey(keyLocation, passphrase = null).passphrase!!.concatToString() shouldBe "secret"
        store.resolveStoredKey(keyLocation.copy(credentialVersion = 2), passphrase = null)
            .passphrase!!.concatToString() shouldBe "session"
    }

    @Test
    fun `the stored key fails like resolve when nothing usable is stored`() = runTest {
        val cipher = FakeCipher()
        val store = create(cipher = cipher)

        shouldThrow<SftpCredentialUnavailableException> { store.resolveStoredKey(askingLocation, null) }

        store.store(locationId, 1, key(), remember = true, rememberPassphrase = false)
        cipher.keyPresent = false
        shouldThrow<SftpCredentialUnavailableException> { store.resolveStoredKey(askingLocation, null) }
    }

    @Test
    fun `a password location has no stored key`() = runTest {
        val store = create()
        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = true)

        shouldThrow<IllegalArgumentException> { store.resolveStoredKey(location, null) }
    }

    @Test
    fun `availability follows the held passphrase while the rows stay untouched`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)
        store.store(locationId, 1, key(), remember = true, rememberPassphrase = false)
        dao.keepRows = true
        val seen = mutableListOf<SftpCredentialStore.Availability>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.availability(askingLocation).collect { seen.add(it) }
        }
        val rows = dao.rows.value

        store.holdPassphrase(locationId, 1, "typed".toCharArray())
        store.remove(locationId)

        dao.rows.value shouldBeSameInstanceAs rows
        seen shouldBe listOf(
            SftpCredentialStore.Availability.MISSING,
            SftpCredentialStore.Availability.AVAILABLE,
            SftpCredentialStore.Availability.MISSING,
        )
        job.cancel()
    }

    @Test
    fun `a restarted app has the key but not its passphrase`() = runTest {
        val dao = FakeDao()
        val cipher = FakeCipher()
        val before = create(cipher = cipher, dao = dao)
        before.store(locationId, 1, key("secret"), remember = true, rememberPassphrase = false)

        val restarted = create(cipher = cipher, dao = dao)

        restarted.availability(askingLocation).first() shouldBe SftpCredentialStore.Availability.MISSING
        shouldThrow<SftpKeyPassphraseRequiredException> { restarted.resolve(askingLocation) }
        restarted.resolveStoredKey(askingLocation, null).let {
            it.keyBytes.contentEquals(keyBytes) shouldBe true
            it.passphrase shouldBe null
        }
    }

    @Test
    fun `a missing Keystore key outranks a missing passphrase`() = runTest {
        val cipher = FakeCipher()
        val store = create(cipher = cipher)
        store.store(locationId, 1, key(), remember = true, rememberPassphrase = false)

        cipher.keyPresent = false

        store.availability(askingLocation).first() shouldBe SftpCredentialStore.Availability.KEY_UNAVAILABLE
        shouldThrow<SftpCredentialUnavailableException> { store.resolve(askingLocation) }
    }

    @Test
    fun `storing the generation again drops its held passphrase`() = runTest {
        val store = create()
        store.store(locationId, 1, key("secret"), remember = true, rememberPassphrase = false)

        store.store(locationId, 1, key(), remember = true, rememberPassphrase = false)

        shouldThrow<SftpKeyPassphraseRequiredException> { store.resolve(askingLocation) }
    }

    @Test
    fun `every way of dropping a generation drops its held passphrase`() = runTest {
        val clears: List<suspend SftpCredentialStore.() -> Unit> = listOf(
            { remove(locationId) },
            { dropOtherGenerations(locationId, keepVersion = 2) },
            { reconcile(emptyList()) },
            { reconcile(listOf(askingLocation.copy(credentialVersion = 2))) },
        )
        clears.forEach { clear ->
            val dao = FakeDao()
            val store = create(dao = dao)
            store.store(locationId, 1, key("secret"), remember = true, rememberPassphrase = false)
            dao.keepRows = true

            store.clear()

            shouldThrow<SftpKeyPassphraseRequiredException> { store.resolve(askingLocation) }
            store.availability(askingLocation).first() shouldBe SftpCredentialStore.Availability.MISSING
        }
    }

    @Test
    fun `retiring other generations keeps the current one's passphrase`() = runTest {
        val store = create()
        store.store(locationId, 1, key("old"), remember = true, rememberPassphrase = false)
        store.store(locationId, 2, key("new"), remember = true, rememberPassphrase = false)

        store.dropOtherGenerations(locationId, keepVersion = 2)
        store.reconcile(listOf(askingLocation.copy(credentialVersion = 2)))

        store.resolvedPassphrase(askingLocation.copy(credentialVersion = 2)) shouldBe "new"
    }

    @Test
    fun `not remembering the passphrase changes nothing for a password or a session-only key`() = runTest {
        val dao = FakeDao()
        val store = create(dao = dao)

        store.store(locationId, 1, password("hunter2"), remember = true, rememberPassphrase = false)
        store.resolvedPassword(location) shouldBe "hunter2"

        store.store(locationId, 2, key("secret"), remember = false, rememberPassphrase = false)
        dao.rows.value.map { it.credentialVersion } shouldBe listOf(1)
        store.resolvedPassphrase(askingLocation.copy(credentialVersion = 2, rememberCredential = false)) shouldBe
            "secret"
    }

    // endregion
}
