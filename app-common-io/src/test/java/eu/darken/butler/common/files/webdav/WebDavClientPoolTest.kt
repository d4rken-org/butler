package eu.darken.butler.common.files.webdav

import eu.darken.butler.common.files.WebDavPath
import eu.darken.butler.common.files.smb.FakeUpgradeRepo
import eu.darken.butler.common.files.webdav.credentials.WebDavCredential
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialStore
import eu.darken.butler.common.files.webdav.credentials.WebDavCredentialUnavailableException
import eu.darken.butler.common.files.webdav.location.TrustedCertificate
import eu.darken.butler.common.files.webdav.location.WebDavLocation
import eu.darken.webdav.CertificatePolicy
import eu.darken.webdav.ServerCertificate
import eu.darken.webdav.TestCertificates
import eu.darken.webdav.WebDavConnector
import eu.darken.webdav.WebDavCredentials
import eu.darken.webdav.WebDavEndpoint
import eu.darken.webdav.WebDavException
import eu.darken.webdav.WebDavSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

class WebDavClientPoolTest : BaseTest() {

    private val locationA = testWebDavLocation(Uuid.parse("11111111-1111-1111-1111-111111111111"))
    private val locationB = testWebDavLocation(
        Uuid.parse("22222222-2222-2222-2222-222222222222"),
        url = "http://nas.local:8080/dav/",
        authType = WebDavLocation.AuthType.ANONYMOUS,
    )

    private val certificate1 = ServerCertificate(TestCertificates.leaf("cloud.example.com").certificate.encoded)
    private val certificate2 = ServerCertificate(TestCertificates.leaf("cloud.example.com").certificate.encoded)

    private class Connect(
        val endpoint: WebDavEndpoint,
        val credentials: WebDavCredentials,
        val policy: CertificatePolicy,
    ) {
        /** Copied at connect time; the pool wipes the original afterwards. */
        val password: String? = (credentials as? WebDavCredentials.Basic)?.password?.concatToString()
        val passedPassword: CharArray? = (credentials as? WebDavCredentials.Basic)?.password
    }

    /** Records every connect; [gates] hold the connect with the same index until completed. */
    private class FakeConnectorFactory : WebDavConnectorFactory {
        val connects = mutableListOf<Connect>()
        val sessions = mutableListOf<WebDavSession>()
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var connectFailure: Throwable? = null
        var created = 0

        override fun create(): WebDavConnector {
            created++
            return WebDavConnector { endpoint, credentials, policy ->
                val attempt = connects.size
                connects.add(Connect(endpoint, credentials, policy))
                gates[attempt]?.await()
                connectFailure?.let { throw it }
                mockk<WebDavSession>(relaxed = true).also { sessions.add(it) }
            }
        }
    }

    private fun credentialStore(evictions: MutableSharedFlow<Uuid> = MutableSharedFlow()) =
        mockk<WebDavCredentialStore>(relaxed = true) {
            every { this@mockk.evictions } returns evictions
            coEvery { resolve(any()) } answers {
                WebDavCredential.Password(firstArg<WebDavLocation>().username!!, "hunter2".toCharArray())
            }
        }

    private fun pool(
        factory: FakeConnectorFactory,
        locations: FakeWebDavLocationManager = FakeWebDavLocationManager(listOf(locationA, locationB)),
        credentialStore: WebDavCredentialStore = credentialStore(),
        appScope: TestScope = TestScope(),
    ) = WebDavClientPool(
        appScope = appScope,
        locationManager = locations,
        credentialStore = credentialStore,
        connectorFactory = factory,
        upgradeRepo = FakeUpgradeRepo(),
    )

    @Test
    fun `a session is reused across operations`() = runTest {
        val factory = FakeConnectorFactory()
        val pool = pool(factory)

        pool.use(WebDavPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(WebDavPath.root(locationA.id), retryOnTransportLoss = false) { }

        factory.sessions.size shouldBe 1
        factory.created shouldBe 1
    }

    @Test
    fun `two locations get their own session`() = runTest {
        val factory = FakeConnectorFactory()
        val pool = pool(factory)

        pool.use(WebDavPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(WebDavPath.root(locationB.id), retryOnTransportLoss = false) { }

        factory.sessions.size shouldBe 2
        factory.connects.map { it.endpoint.url } shouldBe listOf(locationA.url, locationB.url)
    }

    @Test
    fun `basic locations sign in with the stored password, which is wiped after connecting`() = runTest {
        val factory = FakeConnectorFactory()
        val pool = pool(factory)

        pool.use(WebDavPath.root(locationA.id), retryOnTransportLoss = false) { }

        val connect = factory.connects.single()
        val basic = connect.credentials.shouldBeInstanceOf<WebDavCredentials.Basic>()
        basic.username shouldBe "alice"
        connect.password shouldBe "hunter2"
        connect.passedPassword!!.all { it == Char(0) } shouldBe true
    }

    @Test
    fun `anonymous locations never ask for a credential`() = runTest {
        val factory = FakeConnectorFactory()
        val store = credentialStore()
        val pool = pool(factory, credentialStore = store)

        pool.use(WebDavPath.root(locationB.id), retryOnTransportLoss = false) { }

        factory.connects.single().credentials shouldBe WebDavCredentials.Anonymous
        io.mockk.coVerify(exactly = 0) { store.resolve(any()) }
    }

    @Test
    fun `an unreadable credential fails the acquisition as it is`() = runTest {
        val factory = FakeConnectorFactory()
        val store = credentialStore()
        coEvery { store.resolve(any()) } throws WebDavCredentialUnavailableException(locationA.id, "gone")
        val pool = pool(factory, credentialStore = store)

        shouldThrow<WebDavCredentialUnavailableException> { pool.acquire(locationA.id) }
        factory.connects.size shouldBe 0
    }

    @Test
    fun `without a pin only the system trust applies, with one the pin is accepted too`() = runTest {
        val pinned = locationA.copy(pinnedCertificate = TrustedCertificate.from(certificate1))
        val factory = FakeConnectorFactory()
        val pool = pool(factory, FakeWebDavLocationManager(listOf(pinned, locationB)))

        pool.use(WebDavPath.root(locationB.id), retryOnTransportLoss = false) { }
        pool.use(WebDavPath.root(pinned.id), retryOnTransportLoss = false) { }

        factory.connects.map { it.policy } shouldBe listOf(
            CertificatePolicy.System,
            CertificatePolicy.Pinned(certificate1),
        )
    }

    @Test
    fun `an unreadable pin leaves only the system trust`() = runTest {
        val corrupt = locationA.copy(pinnedCertificate = testCertificate(9))
        val factory = FakeConnectorFactory()
        val pool = pool(factory, FakeWebDavLocationManager(listOf(corrupt)))

        pool.use(WebDavPath.root(corrupt.id), retryOnTransportLoss = false) { }

        factory.connects.single().policy shouldBe CertificatePolicy.System
    }

    @Test
    fun `a changed certificate names the location as it was pinned`() = runTest {
        val pinned = locationA.copy(pinnedCertificate = TrustedCertificate.from(certificate1), trustRevision = 4)
        val factory = FakeConnectorFactory().apply {
            connectFailure = WebDavException(
                WebDavException.Kind.CERTIFICATE_MISMATCH,
                presentedCertificate = certificate2,
            )
        }
        val pool = pool(factory, FakeWebDavLocationManager(listOf(pinned)))

        val error = shouldThrow<WebDavCertificateException> {
            pool.use(WebDavPath.root(pinned.id), retryOnTransportLoss = false) { }
        }

        error.locationId shouldBe pinned.id
        error.url shouldBe pinned.url
        error.trustRevision shouldBe 4
        error.stored shouldBe WebDavCertificateInfo.from(certificate1)
        error.presented shouldBe WebDavCertificateInfo.from(certificate2)
    }

    @Test
    fun `an untrusted certificate of an unpinned location carries no stored certificate`() = runTest {
        val factory = FakeConnectorFactory().apply {
            connectFailure = WebDavException(
                WebDavException.Kind.CERTIFICATE_UNTRUSTED,
                presentedCertificate = certificate2,
            )
        }
        val pool = pool(factory)

        val error = shouldThrow<WebDavCertificateException> { pool.acquire(locationA.id) }

        error.stored shouldBe null
        error.presented shouldBe WebDavCertificateInfo.from(certificate2)
    }

    @Test
    fun `an unknown location is unreachable`() = runTest {
        val pool = pool(FakeConnectorFactory())

        shouldThrow<WebDavUnreachableException> { pool.acquire(Uuid.random()) }
    }

    @Test
    fun `evicting one location leaves the other connected`() = runTest {
        val factory = FakeConnectorFactory()
        val pool = pool(factory)
        pool.use(WebDavPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(WebDavPath.root(locationB.id), retryOnTransportLoss = false) { }

        pool.evict(locationA.id)

        verify { factory.sessions[0].disconnect() }
        verify(exactly = 0) { factory.sessions[1].disconnect() }
    }

    @Test
    fun `an idempotent operation retries exactly once after a transport loss`() = runTest {
        val factory = FakeConnectorFactory()
        val pool = pool(factory)
        var attempts = 0

        pool.use(WebDavPath.root(locationA.id), retryOnTransportLoss = true) {
            attempts++
            if (attempts == 1) throw WebDavException(WebDavException.Kind.TRANSPORT)
        }

        attempts shouldBe 2
        factory.sessions.size shouldBe 2
    }

    @Test
    fun `a mutation is never replayed`() = runTest {
        val factory = FakeConnectorFactory()
        val pool = pool(factory)
        var attempts = 0

        shouldThrow<WebDavException> {
            pool.use(WebDavPath.root(locationA.id), retryOnTransportLoss = false) {
                attempts++
                throw WebDavException(WebDavException.Kind.TRANSPORT)
            }
        }

        attempts shouldBe 1
    }

    @Test
    fun `a stale generation closes only after its last lease is returned`() = runTest {
        val factory = FakeConnectorFactory()
        val pool = pool(factory)
        val lease = pool.acquire(locationA.id)

        pool.evict(locationA.id)
        verify(exactly = 0) { factory.sessions[0].disconnect() }

        lease.close()
        lease.close()
        verify(exactly = 1) { factory.sessions[0].disconnect() }
    }

    @Test
    fun `an idle generation is closed, a leased one is not`() = runTest {
        val factory = FakeConnectorFactory()
        val pool = pool(factory)
        pool.use(WebDavPath.root(locationA.id), retryOnTransportLoss = false) { }
        val leased = pool.acquire(locationB.id)

        pool.trimIdle(FAR_FUTURE)

        verify { factory.sessions[0].disconnect() }
        verify(exactly = 0) { factory.sessions[1].disconnect() }
        leased.close()
    }

    @Test
    fun `the idle sweep never closes a session that is being handed out`() = runBlocking {
        repeat(RACE_ROUNDS) {
            val factory = FakeConnectorFactory()
            val pool = pool(factory)
            pool.acquire(locationA.id).close()

            val start = CyclicBarrier(2)
            val sweep = async(Dispatchers.IO) {
                start.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                pool.trimIdle(FAR_FUTURE)
            }
            val lease = async(Dispatchers.IO) {
                start.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                pool.acquire(locationA.id)
            }.await()
            sweep.await()

            verify(exactly = 0) { lease.session.disconnect() }
            lease.close()
        }
    }

    @Test
    fun `closing the pool closes every session, the next operation reconnects`() = runTest {
        val factory = FakeConnectorFactory()
        val pool = pool(factory)
        pool.use(WebDavPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(WebDavPath.root(locationB.id), retryOnTransportLoss = false) { }

        pool.close()

        verify { factory.sessions[0].disconnect() }
        verify { factory.sessions[1].disconnect() }

        pool.use(WebDavPath.root(locationA.id), retryOnTransportLoss = false) { }
        factory.sessions.size shouldBe 3
    }

    /** The URL is the collection the session serves; keeping the old one would list the old folder. */
    @Test
    fun `a URL-only edit gets a new session while the old one drains`() = runTest {
        val factory = FakeConnectorFactory()
        val locations = FakeWebDavLocationManager(listOf(locationA))
        val pool = pool(factory, locations)
        val inFlight = pool.acquire(locationA.id)

        val moved = locationA.copy(url = "https://cloud.example.com/remote.php/dav/files/alice/photos/")
        locations.put(moved)
        val afterEdit = pool.acquire(locationA.id)

        afterEdit.location.url shouldBe moved.url
        factory.connects.map { it.endpoint.url } shouldBe listOf(locationA.url, moved.url)
        verify(exactly = 0) { factory.sessions[0].disconnect() }
        inFlight.close()
        verify { factory.sessions[0].disconnect() }

        afterEdit.close()
    }

    @Test
    fun `a new username, auth type or credential version retires the session`() = runTest {
        val factory = FakeConnectorFactory()
        val locations = FakeWebDavLocationManager(listOf(locationA))
        val pool = pool(factory, locations)

        pool.acquire(locationA.id).close()
        locations.put(locationA.copy(credentialVersion = 2))
        pool.acquire(locationA.id).close()
        locations.put(locationA.copy(credentialVersion = 2, username = "bob"))
        pool.acquire(locationA.id).close()
        locations.put(
            locationA.copy(
                credentialVersion = 3,
                username = null,
                authType = WebDavLocation.AuthType.ANONYMOUS,
            )
        )
        pool.acquire(locationA.id).close()

        factory.sessions.size shouldBe 4
        factory.connects.map { (it.credentials as? WebDavCredentials.Basic)?.username } shouldBe
            listOf("alice", "alice", "bob", null)
        (0..2).forEach { verify { factory.sessions[it].disconnect() } }
    }

    @Test
    fun `a new trust revision retires the session and pins the new certificate`() = runTest {
        val factory = FakeConnectorFactory()
        val locations = FakeWebDavLocationManager(listOf(locationA))
        val pool = pool(factory, locations)
        val inFlight = pool.acquire(locationA.id)

        val retrusted = locationA.copy(pinnedCertificate = TrustedCertificate.from(certificate2), trustRevision = 2)
        locations.put(retrusted)
        val afterRetrust = pool.acquire(locationA.id)

        afterRetrust.location.trustRevision shouldBe 2
        factory.connects.map { it.policy } shouldBe listOf(
            CertificatePolicy.System,
            CertificatePolicy.Pinned(certificate2),
        )
        verify(exactly = 0) { factory.sessions[0].disconnect() }
        inFlight.close()
        verify { factory.sessions[0].disconnect() }

        afterRetrust.close()
        pool.acquire(locationA.id).session shouldBe factory.sessions[1]
    }

    /**
     * The connect started before the location's URL was edited and finishes after: its session serves
     * a collection the location no longer names and must not be published, nor handed out.
     */
    @Test
    fun `a session started before an edit is never published`() = runTest {
        val factory = FakeConnectorFactory()
        val locations = FakeWebDavLocationManager(listOf(locationA))
        val pool = pool(factory, locations)
        factory.gates[0] = CompletableDeferred()

        val acquisition = async { pool.acquire(locationA.id) }
        runCurrent()

        val moved = locationA.copy(url = "https://cloud.example.com/remote.php/dav/files/alice/other/")
        locations.put(moved)
        factory.gates[0]!!.complete(Unit)
        runCurrent()

        val lease = acquisition.await()
        lease.location.url shouldBe moved.url
        lease.session shouldBe factory.sessions[1]
        verify { factory.sessions[0].disconnect() }

        lease.close()
        pool.acquire(locationA.id).session shouldBe factory.sessions[1]
        factory.sessions.size shouldBe 2
    }

    @Test
    fun `a session connected across an eviction is never published`() = runTest {
        val factory = FakeConnectorFactory()
        val pool = pool(factory)
        factory.gates[0] = CompletableDeferred()

        val acquisition = async { pool.acquire(locationA.id) }
        runCurrent()

        pool.evict(locationA.id)
        factory.gates[0]!!.complete(Unit)
        runCurrent()

        acquisition.await().session shouldBe factory.sessions[1]
        verify { factory.sessions[0].disconnect() }
    }

    @Test
    fun `a credential change evicts the session`() = runTest {
        val factory = FakeConnectorFactory()
        val evictions = MutableSharedFlow<Uuid>()
        val scope = TestScope(testScheduler)
        val pool = pool(
            factory,
            FakeWebDavLocationManager(listOf(locationA)),
            credentialStore(evictions),
            scope,
        )
        pool.acquire(locationA.id).close()
        scope.runCurrent()

        evictions.emit(locationA.id)
        scope.runCurrent()

        verify { factory.sessions[0].disconnect() }
        scope.cancel()
    }

    companion object {
        private const val FAR_FUTURE = Long.MAX_VALUE / 2
        private const val RACE_ROUNDS = 100
        private const val BARRIER_TIMEOUT_SECONDS = 10L
    }
}
