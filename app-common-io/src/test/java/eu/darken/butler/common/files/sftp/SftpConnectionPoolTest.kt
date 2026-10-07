package eu.darken.butler.common.files.sftp

import eu.darken.butler.common.files.SftpPath
import eu.darken.butler.common.files.sftp.credentials.SftpCredential
import eu.darken.butler.common.files.sftp.credentials.SftpCredentialStore
import eu.darken.butler.common.files.sftp.location.SftpLocation
import eu.darken.butler.common.files.smb.FakeUpgradeRepo
import eu.darken.ssh.HostKeyPolicy
import eu.darken.ssh.SftpEndpoint
import eu.darken.ssh.SftpSession
import eu.darken.ssh.SshCredentials
import eu.darken.ssh.SshException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
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
import eu.darken.ssh.SftpPath as ServerPath

class SftpConnectionPoolTest : BaseTest() {

    private val locationA = testSftpLocation(Uuid.parse("11111111-1111-1111-1111-111111111111"))
    private val locationB = testSftpLocation(Uuid.parse("22222222-2222-2222-2222-222222222222"))

    private val home = ServerPath(listOf("home", "darken"))

    /** Records every connect; [gates] hold the connect with the same index until completed. */
    private inner class FakeClientFactory : SftpClientFactory {
        val clients = mutableListOf<FakeClient>()
        val sessions = mutableListOf<SftpSession>()
        val sessionConnected = mutableListOf<Boolean>()
        val policies = mutableListOf<HostKeyPolicy>()
        val usernames = mutableListOf<String>()
        val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var connectFailure: Throwable? = null
        private var connects = 0

        override fun create(): SftpClient = FakeClient().also { clients.add(it) }

        inner class FakeClient : SftpClient {
            var closed = false

            override suspend fun connect(
                endpoint: SftpEndpoint,
                credentials: SshCredentials,
                hostKeyPolicy: HostKeyPolicy,
            ): SftpSession {
                check(!closed) { "Connector is closed" }
                val attempt = connects++
                policies.add(hostKeyPolicy)
                usernames.add(credentials.username)
                gates[attempt]?.await()
                connectFailure?.let { throw it }
                val index = sessions.size
                val session = mockk<SftpSession>(relaxed = true) {
                    every { connected } answers { sessionConnected[index] }
                    coEvery { canonicalize(any()) } returns home
                }
                sessions.add(session)
                sessionConnected.add(true)
                return session
            }

            override fun close() {
                closed = true
            }
        }
    }

    private fun credentialStore(evictions: MutableSharedFlow<Uuid> = MutableSharedFlow()) =
        mockk<SftpCredentialStore>(relaxed = true) {
            every { this@mockk.evictions } returns evictions
            coEvery { resolve(any()) } answers {
                SftpCredential.Password(firstArg<SftpLocation>().username, "hunter2".toCharArray())
            }
        }

    private fun pool(
        factory: FakeClientFactory,
        locations: FakeSftpLocationManager = FakeSftpLocationManager(listOf(locationA, locationB)),
        credentialStore: SftpCredentialStore = credentialStore(),
    ) = SftpConnectionPool(
        appScope = TestScope(),
        locationManager = locations,
        credentialStore = credentialStore,
        clientFactory = factory,
        upgradeRepo = FakeUpgradeRepo(),
    )

    @Test
    fun `a session is reused across operations`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        pool.use(SftpPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(SftpPath.root(locationA.id), retryOnTransportLoss = false) { }

        factory.sessions.size shouldBe 1
        factory.clients.size shouldBe 1
    }

    @Test
    fun `two locations on the same endpoint get their own session from one client`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        pool.use(SftpPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(SftpPath.root(locationB.id), retryOnTransportLoss = false) { }

        factory.sessions.size shouldBe 2
        factory.clients.size shouldBe 1
    }

    @Test
    fun `sessions are pinned to the location's host key`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        pool.use(SftpPath.root(locationA.id), retryOnTransportLoss = false) { }

        factory.policies.single() shouldBe HostKeyPolicy.Pinned(locationA.hostKey.toHostKey())
    }

    @Test
    fun `a changed host key names the location as it was pinned`() = runTest {
        val factory = FakeClientFactory().apply {
            connectFailure = SshException(SshException.Kind.HOST_KEY_MISMATCH, presentedHostKey = testHostKey(2).toHostKey())
        }
        val location = locationA.copy(host = "fe80::1", port = 2222, trustRevision = 4)
        val pool = pool(factory, FakeSftpLocationManager(listOf(location)))

        val error = shouldThrow<SftpHostKeyChangedException> {
            pool.use(SftpPath.root(location.id), retryOnTransportLoss = false) { }
        }

        error.locationId shouldBe location.id
        error.host shouldBe "fe80::1"
        error.port shouldBe 2222
        error.trustRevision shouldBe 4
        error.storedKey shouldBe location.hostKey
        error.presentedKey shouldBe testHostKey(2)
    }

    @Test
    fun `the root is resolved once per session`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        pool.use(SftpPath.root(locationA.id), retryOnTransportLoss = false) { it.root shouldBe home }
        val lease = pool.acquire(locationA.id)
        lease.serverPath(SftpPath(locationA.id, listOf("a", "b"))) shouldBe home.child("a").child("b")
        lease.close()

        coVerify(exactly = 1) { factory.sessions[0].canonicalize(".") }
    }

    @Test
    fun `a base path the server cannot resolve fails the acquisition and closes the session`() = runTest {
        val locations = FakeSftpLocationManager(listOf(locationA.copy(basePath = "gone")))
        val session = mockk<SftpSession>(relaxed = true)
        coEvery { session.canonicalize("gone") } throws SshException(SshException.Kind.MISSING)
        val factory = SftpClientFactory {
            object : SftpClient {
                override suspend fun connect(
                    endpoint: SftpEndpoint,
                    credentials: SshCredentials,
                    hostKeyPolicy: HostKeyPolicy,
                ): SftpSession = session

                override fun close() = Unit
            }
        }
        val pool = SftpConnectionPool(TestScope(), locations, credentialStore(), factory, FakeUpgradeRepo())

        val error = shouldThrow<Exception> { pool.acquire(locationA.id) }

        SftpStatusMapper.isMissing(error) shouldBe true
        verify { session.disconnect() }
    }

    @Test
    fun `evicting one location leaves the other connected`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.use(SftpPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(SftpPath.root(locationB.id), retryOnTransportLoss = false) { }

        pool.evict(locationA.id)

        verify { factory.sessions[0].disconnect() }
        verify(exactly = 0) { factory.sessions[1].disconnect() }
    }

    @Test
    fun `an idempotent operation retries exactly once after a transport loss`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        var attempts = 0

        pool.use(SftpPath.root(locationA.id), retryOnTransportLoss = true) {
            attempts++
            if (attempts == 1) throw SshException(SshException.Kind.TRANSPORT)
        }

        attempts shouldBe 2
        factory.sessions.size shouldBe 2
    }

    @Test
    fun `a mutation is never replayed`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        var attempts = 0

        shouldThrow<SshException> {
            pool.use(SftpPath.root(locationA.id), retryOnTransportLoss = false) {
                attempts++
                throw SshException(SshException.Kind.TRANSPORT)
            }
        }

        attempts shouldBe 1
    }

    @Test
    fun `a stale generation closes only after its last lease is returned`() = runTest {
        val factory = FakeClientFactory()
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
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.use(SftpPath.root(locationA.id), retryOnTransportLoss = false) { }
        val leased = pool.acquire(locationB.id)

        pool.trimIdle(FAR_FUTURE)

        verify { factory.sessions[0].disconnect() }
        verify(exactly = 0) { factory.sessions[1].disconnect() }
        leased.close()
    }

    @Test
    fun `the idle sweep never closes a session that is being handed out`() = runBlocking {
        repeat(RACE_ROUNDS) {
            val factory = FakeClientFactory()
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
    fun `a session that was closed under us is never handed out`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        val first = pool.acquire(locationA.id)
        first.close()

        factory.sessionConnected[0] = false

        val second = pool.acquire(locationA.id)

        second.session shouldNotBe first.session
        factory.sessions.size shouldBe 2
        second.close()
    }

    @Test
    fun `closing the pool closes every session and the client`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.use(SftpPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(SftpPath.root(locationB.id), retryOnTransportLoss = false) { }

        pool.close()

        verify { factory.sessions[0].disconnect() }
        verify { factory.sessions[1].disconnect() }
        factory.clients.single().closed shouldBe true
    }

    @Test
    fun `a closed pool keeps its client until the last lease is returned`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        val lease = pool.acquire(locationA.id)

        pool.close()
        factory.clients.single().closed shouldBe false

        lease.close()
        verify { factory.sessions[0].disconnect() }
        factory.clients.single().closed shouldBe true
    }

    @Test
    fun `the pool reconnects with a new client after it was closed`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.acquire(locationA.id).close()

        pool.close()
        pool.use(SftpPath.root(locationA.id), retryOnTransportLoss = false) { }

        factory.clients.size shouldBe 2
        factory.sessions.size shouldBe 2
    }

    @Test
    fun `an edited endpoint gets a new session while the old one drains`() = runTest {
        val factory = FakeClientFactory()
        val locations = FakeSftpLocationManager(listOf(locationA))
        val pool = pool(factory, locations)
        val inFlight = pool.acquire(locationA.id)

        locations.put(locationA.copy(host = "other.local"))
        val afterEdit = pool.acquire(locationA.id)

        afterEdit.location.host shouldBe "other.local"
        factory.sessions.size shouldBe 2
        verify(exactly = 0) { factory.sessions[0].disconnect() }
        inFlight.close()
        verify { factory.sessions[0].disconnect() }

        afterEdit.close()
    }

    @Test
    fun `a new credential version retires the session`() = runTest {
        val factory = FakeClientFactory()
        val locations = FakeSftpLocationManager(listOf(locationA))
        val pool = pool(factory, locations)
        val inFlight = pool.acquire(locationA.id)

        locations.put(locationA.copy(credentialVersion = 2, username = "other"))
        val afterEdit = pool.acquire(locationA.id)

        afterEdit.location.credentialVersion shouldBe 2
        factory.usernames shouldBe listOf("darken", "other")
        verify(exactly = 0) { factory.sessions[0].disconnect() }
        inFlight.close()
        verify { factory.sessions[0].disconnect() }
        afterEdit.close()
    }

    @Test
    fun `a new trust revision retires the session and pins the new key`() = runTest {
        val factory = FakeClientFactory()
        val locations = FakeSftpLocationManager(listOf(locationA))
        val pool = pool(factory, locations)
        val inFlight = pool.acquire(locationA.id)

        val retrusted = locationA.copy(hostKey = testHostKey(2), trustRevision = 2)
        locations.put(retrusted)
        val afterRetrust = pool.acquire(locationA.id)

        afterRetrust.location.trustRevision shouldBe 2
        factory.policies shouldBe listOf(
            HostKeyPolicy.Pinned(testHostKey(1).toHostKey()),
            HostKeyPolicy.Pinned(testHostKey(2).toHostKey()),
        )
        // The operation already running on the old session is allowed to finish
        verify(exactly = 0) { factory.sessions[0].disconnect() }
        inFlight.close()
        verify { factory.sessions[0].disconnect() }

        // Later acquisitions share the new generation
        afterRetrust.close()
        pool.acquire(locationA.id).session shouldBe factory.sessions[1]
    }

    /**
     * The connect started before the location was re-trusted and finishes after: its session belongs
     * to a trust decision that no longer stands and must not be published, nor handed out.
     */
    @Test
    fun `a session started under an obsolete generation is never published`() = runTest {
        val factory = FakeClientFactory()
        val locations = FakeSftpLocationManager(listOf(locationA))
        val pool = pool(factory, locations)
        factory.gates[0] = CompletableDeferred()

        val acquisition = async { pool.acquire(locationA.id) }
        runCurrent()

        locations.put(locationA.copy(hostKey = testHostKey(2), trustRevision = 2))
        factory.gates[0]!!.complete(Unit)
        runCurrent()

        val lease = acquisition.await()
        lease.location.trustRevision shouldBe 2
        lease.session shouldBe factory.sessions[1]
        verify { factory.sessions[0].disconnect() }

        lease.close()
        pool.acquire(locationA.id).session shouldBe factory.sessions[1]
        factory.sessions.size shouldBe 2
    }

    @Test
    fun `a session connected across an eviction is never published`() = runTest {
        val factory = FakeClientFactory()
        val evictions = MutableSharedFlow<Uuid>()
        val pool = pool(factory, credentialStore = credentialStore(evictions))
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
        val factory = FakeClientFactory()
        val evictions = MutableSharedFlow<Uuid>()
        val scope = TestScope(testScheduler)
        val pool = SftpConnectionPool(
            appScope = scope,
            locationManager = FakeSftpLocationManager(listOf(locationA)),
            credentialStore = credentialStore(evictions),
            clientFactory = factory,
            upgradeRepo = FakeUpgradeRepo(),
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
