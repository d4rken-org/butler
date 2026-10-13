package eu.darken.butler.common.files.webdav.location

import eu.darken.butler.common.files.credentials.CredentialCipher
import eu.darken.butler.common.files.webdav.credentials.WebDavCredential
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialCipher
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialStore
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialUnavailableException
import eu.darken.butler.common.files.webdav.credentials.db.WebDavCredentialEntity
import eu.darken.butler.common.files.webdav.credentials.db.WebDavCredentialsDao
import eu.darken.butler.common.files.webdav.location.WebDavLocationManager.RetrustResult
import eu.darken.butler.common.files.webdav.location.db.WebDavLocationEntity
import eu.darken.butler.common.files.webdav.location.db.WebDavLocationsDao
import eu.darken.butler.common.files.webdav.testCertificate
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

class WebDavLocationManagerTest : BaseTest() {

    private class FakeLocationsDao : WebDavLocationsDao {
        val rows = MutableStateFlow<List<WebDavLocationEntity>>(emptyList())

        /** Number of writes to accept before failing, negative means no limit. */
        var failAfterWrites: Int = -1

        override fun getAll(): Flow<List<WebDavLocationEntity>> = rows
        override suspend fun get(locationId: Uuid) = rows.value.firstOrNull { it.locationId == locationId }

        override suspend fun upsert(entity: WebDavLocationEntity) {
            checkWriteAllowed()
            rows.value = rows.value.filterNot { it.locationId == entity.locationId } + entity
        }

        override suspend fun delete(locationId: Uuid) {
            checkWriteAllowed()
            rows.value = rows.value.filterNot { it.locationId == locationId }
        }

        override suspend fun setLastSeen(locationId: Uuid, at: Instant) {
            checkWriteAllowed()
            rows.value = rows.value.map { if (it.locationId == locationId) it.copy(lastSeenAt = at) else it }
        }

        override suspend fun retrust(
            locationId: Uuid,
            url: String,
            expectedTrustRevision: Int,
            certificateDer: ByteArray,
            certificateFingerprint: String,
            updatedAt: Instant,
        ): Int {
            checkWriteAllowed()
            var changed = 0
            rows.value = rows.value.map {
                val matches = it.locationId == locationId && it.url == url &&
                    it.trustRevision == expectedTrustRevision
                if (matches) {
                    changed++
                    it.copy(
                        certificateDer = certificateDer,
                        certificateFingerprint = certificateFingerprint,
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

    private class FakeCredentialsDao : WebDavCredentialsDao {
        val rows = MutableStateFlow<List<WebDavCredentialEntity>>(emptyList())

        /** Runs inside a credential write, i.e. in the middle of a create() or update(). */
        var onUpsert: (suspend () -> Unit)? = null

        /** Runs before the credential rows are read, i.e. in the middle of a reconcile(). */
        var onGetAllOnce: (suspend () -> Unit)? = null

        override fun getAll(): Flow<List<WebDavCredentialEntity>> = rows
        override suspend fun getAllOnce(): List<WebDavCredentialEntity> {
            onGetAllOnce?.invoke()
            return rows.value
        }
        override suspend fun get(locationId: Uuid, credentialVersion: Int) = rows.value.firstOrNull {
            it.locationId == locationId && it.credentialVersion == credentialVersion
        }

        override suspend fun upsert(entity: WebDavCredentialEntity) {
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
    private object PlainCipher : WebDavCredentialCipher {
        override fun encrypt(locationId: Uuid, payloadVersion: Int, plaintext: ByteArray) =
            CredentialCipher.Envelope(CredentialCipher.ENVELOPE_VERSION, "fake", ByteArray(0), plaintext.copyOf())

        override fun decrypt(locationId: Uuid, payloadVersion: Int, envelope: CredentialCipher.Envelope) =
            envelope.ciphertext.copyOf()

        override fun isKeyAvailable(keyAlias: String) = true
    }

    private val locationsDao = FakeLocationsDao()
    private val credentialsDao = FakeCredentialsDao()
    private val credentialStore = WebDavCredentialStore(credentialsDao, PlainCipher)

    private val certA = testCertificate(1)
    private val certB = testCertificate(2)

    private val url = "https://cloud.example.com/remote.php/dav/files/alice/"

    private fun manager() = WebDavLocationManagerImpl(
        appScope = TestScope(),
        dao = locationsDao,
        credentialStore = credentialStore,
    )

    private suspend fun WebDavLocationManager.createSample(
        password: String = "hunter2",
        remember: Boolean = true,
        pin: TrustedCertificate? = certA,
    ) = create(
        label = "Cloud",
        url = url,
        authType = WebDavLocation.AuthType.BASIC,
        username = "alice",
        rememberCredential = remember,
        password = password.toCharArray(),
        pinnedCertificate = pin,
    )

    private suspend fun WebDavLocationManager.createAnonymous(url: String = "https://nas.local/dav/") = create(
        label = null,
        url = url,
        authType = WebDavLocation.AuthType.ANONYMOUS,
        username = null,
        rememberCredential = false,
        password = null,
        pinnedCertificate = null,
    )

    /** Everything but the arguments given stays as stored in [location]; the pin too, unless passed. */
    private suspend fun WebDavLocationManager.edit(
        location: WebDavLocation,
        label: String? = location.label,
        url: String = location.url,
        authType: WebDavLocation.AuthType = location.authType,
        username: String? = location.username,
        rememberCredential: Boolean = location.rememberCredential,
        password: String? = null,
        pinnedCertificate: TrustedCertificate? = location.pinnedCertificate,
    ) = update(
        id = location.id,
        label = label,
        url = url,
        authType = authType,
        username = username,
        rememberCredential = rememberCredential,
        password = password?.toCharArray(),
        pinnedCertificate = pinnedCertificate,
    )

    private suspend fun resolvedPassword(location: WebDavLocation): String {
        val credential = credentialStore.resolve(location)
        credential.shouldBeInstanceOf<WebDavCredential.Password>()
        return String(credential.password)
    }

    @Test
    fun `creating stores the location, its pin and its credential`() = runTest {
        val location = manager().createSample()

        location.credentialVersion shouldBe 1
        location.trustRevision shouldBe 1
        val row = locationsDao.rows.value.single()
        row.locationId shouldBe location.id
        row.url shouldBe url
        row.certificateFingerprint shouldBe certA.fingerprint
        row.certificateDer.contentEquals(certA.der) shouldBe true
        credentialsDao.rows.value.single().locationId shouldBe location.id
        credentialsDao.rows.value.single().credentialVersion shouldBe 1
        manager().get(location.id) shouldBe location
        resolvedPassword(location) shouldBe "hunter2"
    }

    @Test
    fun `a location without a pin stores none`() = runTest {
        val location = manager().createSample(pin = null)

        locationsDao.rows.value.single().certificateDer shouldBe null
        locationsDao.rows.value.single().certificateFingerprint shouldBe null
        manager().get(location.id)!!.pinnedCertificate shouldBe null
    }

    @Test
    fun `the URL is stored normalized`() = runTest {
        val location = manager().create(
            label = null,
            url = " https://Cloud.Example.com:443/remote.php/dav ",
            authType = WebDavLocation.AuthType.BASIC,
            username = "alice",
            rememberCredential = true,
            password = "hunter2".toCharArray(),
            pinnedCertificate = null,
        )

        location.url shouldBe "https://cloud.example.com/remote.php/dav/"
        manager().get(location.id)!!.url shouldBe "https://cloud.example.com/remote.php/dav/"
    }

    @Test
    fun `an anonymous location stores no credential and remembers nothing`() = runTest {
        val location = manager().create(
            label = null,
            url = "http://nas.local/dav/",
            authType = WebDavLocation.AuthType.ANONYMOUS,
            username = null,
            rememberCredential = true,
            password = null,
            pinnedCertificate = null,
        )

        location.rememberCredential shouldBe false
        locationsDao.rows.value.single().authType shouldBe "ANONYMOUS"
        credentialsDao.rows.value shouldBe emptyList()
        shouldThrow<IllegalArgumentException> { credentialStore.resolve(location) }
    }

    @Test
    fun `a secret that does not fit the sign-in method is rejected before anything is written`() = runTest {
        val manager = manager()

        shouldThrow<IllegalArgumentException> {
            manager.create(
                label = null, url = url, authType = WebDavLocation.AuthType.ANONYMOUS, username = null,
                rememberCredential = false, password = "x".toCharArray(), pinnedCertificate = null,
            )
        }
        shouldThrow<IllegalArgumentException> {
            manager.create(
                label = null, url = url, authType = WebDavLocation.AuthType.BASIC, username = "alice",
                rememberCredential = true, password = null, pinnedCertificate = null,
            )
        }
        shouldThrow<IllegalArgumentException> {
            manager.create(
                label = null, url = url, authType = WebDavLocation.AuthType.BASIC, username = null,
                rememberCredential = true, password = "x".toCharArray(), pinnedCertificate = null,
            )
        }

        locationsDao.rows.value shouldBe emptyList()
        credentialsDao.rows.value shouldBe emptyList()
    }

    @Test
    fun `a plain HTTP location with a pin is rejected before anything is written`() = runTest {
        shouldThrow<IllegalArgumentException> {
            manager().create(
                label = null, url = "http://nas.local/dav/", authType = WebDavLocation.AuthType.BASIC,
                username = "alice", rememberCredential = true, password = "x".toCharArray(),
                pinnedCertificate = certA,
            )
        }

        locationsDao.rows.value shouldBe emptyList()
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
        val manager = WebDavLocationManagerImpl(
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
        var labelOnly: Deferred<WebDavLocation>? = null
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

        val updated = manager.edit(location, label = "Renamed")

        updated.label shouldBe "Renamed"
        updated.credentialVersion shouldBe 1
        updated.trustRevision shouldBe 1
        updated.pinnedCertificate shouldBe certA
        updated.createdAt shouldBe location.createdAt
        resolvedPassword(updated) shouldBe "hunter2"
    }

    @Test
    fun `changing the username without a password is rejected`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        shouldThrow<IllegalArgumentException> { manager.edit(location, username = "bob") }
    }

    @Test
    fun `changing the remember setting without a password is rejected`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        shouldThrow<IllegalArgumentException> { manager.edit(location, rememberCredential = false) }
    }

    @Test
    fun `a new username with its password bumps the generation`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        val updated = manager.edit(location, username = "bob", password = "bobpass")

        updated.credentialVersion shouldBe 2
        credentialStore.resolve(updated).username shouldBe "bob"
    }

    @Test
    fun `switching to anonymous access needs no password and retires the stored one`() = runTest {
        val manager = manager()
        val location = manager.createSample(remember = false)
        manager.edit(location, password = "remembered", rememberCredential = true)

        val updated = manager.edit(
            manager.get(location.id)!!,
            authType = WebDavLocation.AuthType.ANONYMOUS,
            username = null,
            rememberCredential = true,
        )

        updated.authType shouldBe WebDavLocation.AuthType.ANONYMOUS
        updated.username shouldBe null
        updated.rememberCredential shouldBe false
        updated.credentialVersion shouldBe 3
        credentialsDao.rows.value shouldBe emptyList()
        shouldThrow<WebDavCredentialUnavailableException> {
            credentialStore.resolve(updated.copy(authType = WebDavLocation.AuthType.BASIC, username = "alice"))
        }
    }

    @Test
    fun `an anonymous edit refuses a password`() = runTest {
        val manager = manager()
        val location = manager.createAnonymous()

        shouldThrow<IllegalArgumentException> { manager.edit(location, password = "x") }
    }

    @Test
    fun `switching from anonymous to Basic needs the password`() = runTest {
        val manager = manager()
        val location = manager.createAnonymous()

        shouldThrow<IllegalArgumentException> {
            manager.edit(location, authType = WebDavLocation.AuthType.BASIC, username = "alice")
        }

        val updated = manager.edit(
            location,
            authType = WebDavLocation.AuthType.BASIC,
            username = "alice",
            password = "pw",
        )
        updated.credentialVersion shouldBe 2
        resolvedPassword(updated) shouldBe "pw"
    }

    @Test
    fun `a label edit of an anonymous location keeps its generation`() = runTest {
        val manager = manager()
        val location = manager.createAnonymous()

        manager.edit(location, label = "Renamed").credentialVersion shouldBe 1
    }

    @Test
    fun `another path on the same origin keeps the trust revision`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        val updated = manager.edit(location, url = "https://cloud.example.com/remote.php/dav/files/bob/")

        updated.url shouldBe "https://cloud.example.com/remote.php/dav/files/bob/"
        updated.trustRevision shouldBe 1
    }

    @Test
    fun `a changed pin bumps the trust revision`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        manager.edit(location, pinnedCertificate = certA).trustRevision shouldBe 1
        manager.edit(location, pinnedCertificate = certB).trustRevision shouldBe 2
        manager.edit(location, pinnedCertificate = null).trustRevision shouldBe 3
        manager.get(location.id)!!.pinnedCertificate shouldBe null
    }

    @Test
    fun `another origin bumps the trust revision even with the same pin`() = runTest {
        val manager = manager()
        val location = manager.createSample(pin = null)

        val otherHost = manager.edit(location, url = "https://other.example.com/dav/")
        otherHost.trustRevision shouldBe 2

        val otherPort = manager.edit(otherHost, url = "https://other.example.com:8443/dav/")
        otherPort.trustRevision shouldBe 3

        val otherScheme = manager.edit(otherPort, url = "http://other.example.com:8443/dav/")
        otherScheme.trustRevision shouldBe 4
        otherScheme.isSecure shouldBe false
    }

    @Test
    fun `moving to plain HTTP with a pin is rejected before anything is written`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        val rowBefore = locationsDao.rows.value.single()

        shouldThrow<IllegalArgumentException> { manager.edit(location, url = "http://cloud.example.com/dav/") }

        locationsDao.rows.value.single() shouldBe rowBefore
    }

    @Test
    fun `an edit replaces a retrust that landed while it was saving with its own tested pin`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        credentialsDao.onUpsert = {
            credentialsDao.onUpsert = null
            manager.retrust(location.id, url, location.trustRevision, certB)
        }

        val updated = manager.edit(location, password = "newpass", pinnedCertificate = certA)

        updated.pinnedCertificate shouldBe certA
        // Revision 2 was the retrust, the edit's pin differs from it
        updated.trustRevision shouldBe 3
    }

    @Test
    fun `retrust pins a certificate where nothing was pinned`() = runTest {
        val manager = manager()
        val location = manager.createSample(pin = null)

        val result = manager.retrust(location.id, url, location.trustRevision, certB)

        result.shouldBeInstanceOf<RetrustResult.Retrusted>()
        result.location.pinnedCertificate shouldBe certB
        result.location.trustRevision shouldBe 2
        result.location.credentialVersion shouldBe 1
        manager.get(location.id)!!.pinnedCertificate shouldBe certB
    }

    @Test
    fun `retrust replaces the pin of the URL it was confirmed for`() = runTest {
        val manager = manager()
        val location = manager.createSample()

        val result = manager.retrust(location.id, url, location.trustRevision, certB)

        result.shouldBeInstanceOf<RetrustResult.Retrusted>()
        result.location.pinnedCertificate shouldBe certB
        result.location.trustRevision shouldBe 2
    }

    @Test
    fun `a stale retrust cannot pin a certificate onto an edited URL`() = runTest {
        val manager = manager()
        val location = manager.createSample(pin = null)
        // The confirmation dialog was opened for the old URL, then the location was edited
        val edited = manager.edit(location, url = "https://cloud.example.com/remote.php/dav/files/bob/")

        val result = manager.retrust(location.id, url, location.trustRevision, certB)

        result shouldBe RetrustResult.EndpointChanged
        manager.get(location.id) shouldBe edited
    }

    @Test
    fun `a retrust confirmed against an older pin cannot replace a newer retrust`() = runTest {
        val manager = manager()
        val location = manager.createSample(pin = null)
        val certC = testCertificate(3)
        manager.retrust(location.id, url, location.trustRevision, certC)
            .shouldBeInstanceOf<RetrustResult.Retrusted>()

        manager.retrust(location.id, url, location.trustRevision, certB) shouldBe RetrustResult.EndpointChanged

        manager.get(location.id)!!.pinnedCertificate shouldBe certC
    }

    @Test
    fun `retrusting a deleted location reports it as gone`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        manager.delete(location.id)

        manager.retrust(location.id, url, location.trustRevision, certB) shouldBe RetrustResult.NotFound
    }

    @Test
    fun `a plain HTTP URL cannot be retrusted`() = runTest {
        val manager = manager()
        val location = manager.createAnonymous(url = "http://nas.local/dav/")

        shouldThrow<IllegalArgumentException> {
            manager.retrust(location.id, location.url, location.trustRevision, certB)
        }
        manager.get(location.id) shouldBe location
    }

    @Test
    fun `a session-only credential is not persisted`() = runTest {
        val manager = manager()
        val location = manager.createSample(remember = false)

        credentialsDao.rows.value shouldBe emptyList()
        resolvedPassword(location) shouldBe "hunter2"
    }

    @Test
    fun `a sighting is only recorded for the host and port it was seen at`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        val seenAt = Instant.fromEpochMilliseconds(5_000)

        manager.recordSeen(location.id, "cloud.example.com", 443, seenAt)
        manager.get(location.id)!!.lastSeenAt shouldBe seenAt

        manager.recordSeen(location.id, "other.example.com", 443, Instant.fromEpochMilliseconds(9_000))
        manager.recordSeen(location.id, "cloud.example.com", 80, Instant.fromEpochMilliseconds(9_000))
        manager.get(location.id)!!.lastSeenAt shouldBe seenAt
    }

    @Test
    fun `a sighting of a deleted location changes nothing`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        manager.delete(location.id)

        manager.recordSeen(location.id, "cloud.example.com", 443, Instant.fromEpochMilliseconds(5_000))

        locationsDao.rows.value shouldBe emptyList()
    }

    @Test
    fun `editing the server forgets when the location was last seen, another path does not`() = runTest {
        val manager = manager()
        val location = manager.createSample(pin = null)
        val seenAt = Instant.fromEpochMilliseconds(5_000)
        manager.recordSeen(location.id, "cloud.example.com", 443, seenAt)

        val otherPath = manager.edit(location, url = "https://cloud.example.com/dav/")
        otherPath.lastSeenAt shouldBe seenAt

        val otherHost = manager.edit(otherPath, url = "https://other.example.com/dav/")
        otherHost.lastSeenAt shouldBe null
        manager.get(location.id)!!.lastSeenAt shouldBe null
    }

    @Test
    fun `a sighting recorded while an edit is saving survives it`() = runTest {
        val manager = manager()
        val location = manager.createSample()
        val seenAt = Instant.fromEpochMilliseconds(5_000)
        credentialsDao.onUpsert = {
            credentialsDao.onUpsert = null
            manager.recordSeen(location.id, "cloud.example.com", 443, seenAt)
        }

        val updated = manager.edit(location, label = "Renamed", password = "newpass")

        updated.lastSeenAt shouldBe seenAt
        manager.get(location.id)!!.lastSeenAt shouldBe seenAt
    }
}
