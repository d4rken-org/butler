package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.smb.credentials.SmbCredential
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.credentials.SmbCredentialUnavailableException
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import eu.darken.smb.SmbConnector
import eu.darken.smb.SmbCredentials
import eu.darken.smb.SmbEndpoint
import eu.darken.smb.SmbException
import eu.darken.smb.SmbServer
import eu.darken.smb.SmbShare
import eu.darken.smb.SmbShareInfo
import eu.darken.smb.SmbShareLister
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SmbConnectionPoolTest : BaseTest() {

    private val locationA = location(Uuid.parse("11111111-1111-1111-1111-111111111111"))
    private val locationB = location(Uuid.parse("22222222-2222-2222-2222-222222222222"))

    private fun location(id: Uuid, credentialVersion: Int = 1) = SmbLocation(
        id = id,
        label = null,
        // Deliberately the same endpoint for both locations
        host = "nas.local",
        share = "media",
        authType = SmbLocation.AuthType.GUEST,
        rememberCredential = false,
        credentialVersion = credentialVersion,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private class FakeLocationManager(var locationsById: Map<Uuid, SmbLocation>) : SmbLocationManager {
        override val locations: Flow<List<SmbLocation>> get() = flowOf(locationsById.values.toList())
        override suspend fun get(id: Uuid): SmbLocation? = locationsById[id]
        override suspend fun create(
            label: String?,
            host: String,
            port: Int,
            share: String?,
            basePath: Segments,
            domain: String?,
            username: String?,
            authType: SmbLocation.AuthType,
            rememberCredential: Boolean,
            password: CharArray?,
        ): SmbLocation = throw UnsupportedOperationException()

        override suspend fun update(
            id: Uuid,
            label: String?,
            host: String,
            port: Int,
            share: String?,
            basePath: Segments,
            domain: String?,
            username: String?,
            authType: SmbLocation.AuthType,
            rememberCredential: Boolean,
            password: CharArray?,
        ): SmbLocation = throw UnsupportedOperationException()

        override suspend fun delete(id: Uuid) = throw UnsupportedOperationException()
        override suspend fun recordSeen(id: Uuid, host: String, port: Int, at: Instant) =
            throw UnsupportedOperationException()
    }

    private val serverLocation = location(Uuid.parse("33333333-3333-3333-3333-333333333333")).copy(share = null)

    private fun server(vararg segments: String) = SmbPath(serverLocation.id, segments.toList())

    private class FakeClientFactory : SmbClientFactory {
        val shares = mutableListOf<SmbShare>()
        val clients = mutableListOf<SmbConnector>()
        val endpoints = mutableListOf<SmbEndpoint>()
        val shareConnected = mutableListOf<Boolean>()
        val closeHooks = mutableListOf<(() -> Unit)?>()

        /** Holds the next connect mid-negotiation, after its credential was resolved, until completed. */
        var nextConnectGate: CompletableDeferred<Unit>? = null

        var shareInfos: List<SmbShareInfo> = emptyList()
        var listError: Exception? = null
        val listings = mutableListOf<Pair<SmbServer, SmbCredentials>>()

        override fun create(): SmbConnector {
            val index = shares.size
            val share = mockk<SmbShare>(relaxed = true) {
                every { connected } answers { shareConnected[index] }
                every { disconnect() } answers { closeHooks[index]?.invoke() }
            }
            val gate = nextConnectGate.also { nextConnectGate = null }
            val client = SmbConnector { endpoint, _ ->
                endpoints.add(endpoint)
                gate?.await()
                share
            }
            shares.add(share)
            shareConnected.add(true)
            closeHooks.add(null)
            clients.add(client)
            return client
        }

        override fun createLister(): SmbShareLister = SmbShareLister { server, credentials ->
            listings.add(server to credentials)
            listError?.let { throw it }
            shareInfos
        }
    }

    private fun pool(
        factory: FakeClientFactory,
        locations: List<SmbLocation> = listOf(locationA, locationB, serverLocation),
        evictions: SharedFlow<Uuid> = MutableSharedFlow(),
    ): SmbConnectionPool = pool(factory, FakeLocationManager(locations.associateBy { it.id }), evictions)

    private fun pool(
        factory: FakeClientFactory,
        locationManager: FakeLocationManager,
        evictions: SharedFlow<Uuid> = MutableSharedFlow(),
        credentialStore: SmbCredentialStore = mockk<SmbCredentialStore>(relaxed = true) {
            every { this@mockk.evictions } returns evictions
        },
        pro: Boolean = true,
    ): SmbConnectionPool = SmbConnectionPool(
        appScope = TestScope(),
        locationManager = locationManager,
        credentialStore = credentialStore,
        clientFactory = factory,
        upgradeRepo = FakeUpgradeRepo(pro = pro),
    )

    private fun shareInfo(name: String, type: SmbShareInfo.Type = SmbShareInfo.Type.DISK, special: Boolean = false) =
        SmbShareInfo(name = name, type = type, special = special, comment = "")

    @Test
    fun `each share of a server entry gets its own session`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        pool.use(server("media", "a"), retryOnTransportLoss = false) { }
        pool.use(server("photos"), retryOnTransportLoss = false) { }
        pool.use(server("media"), retryOnTransportLoss = false) { }

        factory.clients.size shouldBe 2
        factory.endpoints.map { it.share } shouldBe listOf("media", "photos")
    }

    @Test
    fun `evicting a server entry drops the sessions of all its shares`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.use(server("media"), retryOnTransportLoss = false) { }
        pool.use(server("photos"), retryOnTransportLoss = false) { }
        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { }

        pool.evict(serverLocation.id)

        verify { factory.shares[0].disconnect() }
        verify { factory.shares[1].disconnect() }
        verify(exactly = 0) { factory.shares[2].disconnect() }
    }

    @Test
    fun `a lease on a server entry addresses paths below their share`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        val lease = pool.acquire(server("media", "2024", "a.mkv"))

        lease.relativePath(server("media", "2024", "a.mkv")).segments shouldBe listOf("2024", "a.mkv")
        lease.relativePath(server("media")).segments shouldBe emptyList()
        lease.close()
    }

    @Test
    fun `a share location keeps addressing paths below its base path`() = runTest {
        val factory = FakeClientFactory()
        val based = locationA.copy(basePath = listOf("movies"))
        val pool = pool(factory, listOf(based))

        val lease = pool.acquire(SmbPath(based.id, listOf("a.mkv")))

        lease.relativePath(SmbPath(based.id, listOf("a.mkv"))).segments shouldBe listOf("movies", "a.mkv")
        factory.endpoints.single().share shouldBe "media"
        lease.close()
    }

    @Test
    fun `a server entry's root has no share to connect to`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        shouldThrow<IllegalArgumentException> {
            pool.use(serverLocation.rootPath, retryOnTransportLoss = true) { }
        }
        shouldThrow<IllegalArgumentException> { pool.acquire(serverLocation.rootPath) }

        factory.clients.size shouldBe 0
    }

    @Test
    fun `listing returns only browsable disk shares, sorted by name`() = runTest {
        val factory = FakeClientFactory()
        factory.shareInfos = listOf(
            shareInfo("photos"),
            shareInfo("IPC$", SmbShareInfo.Type.IPC, special = true),
            shareInfo("Backup"),
            shareInfo("C$", special = true),
            shareInfo("hidden$"),
            shareInfo("printer", SmbShareInfo.Type.PRINTER),
            shareInfo("apps"),
        )
        val pool = pool(factory)

        pool.listShares(serverLocation.id).map { it.name } shouldBe listOf("apps", "Backup", "photos")

        factory.listings.single().first shouldBe SmbServer(serverLocation.host, serverLocation.port)
        factory.listings.single().second shouldBe SmbCredentials.Guest
        factory.clients.size shouldBe 0
    }

    @Test
    fun `listing asks the server every time`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        pool.listShares(serverLocation.id)
        pool.listShares(serverLocation.id)

        factory.listings.size shouldBe 2
    }

    @Test
    fun `a free user's listing never reaches the server`() = runTest {
        val factory = FakeClientFactory()
        val locations = FakeLocationManager(mapOf(serverLocation.id to serverLocation))
        val pool = pool(factory, locations, pro = false)

        shouldThrow<SmbProRequiredException> { pool.listShares(serverLocation.id) }

        factory.listings.size shouldBe 0
    }

    @Test
    fun `listing signs in with the stored credential and wipes it afterwards`() = runTest {
        val factory = FakeClientFactory()
        val passwordServer = serverLocation.copy(authType = SmbLocation.AuthType.PASSWORD, username = "darken")
        val resolved = mutableListOf<SmbCredential>()
        val credentialStore = mockk<SmbCredentialStore>(relaxed = true) {
            every { this@mockk.evictions } returns MutableSharedFlow()
            coEvery { resolve(any()) } coAnswers {
                SmbCredential("darken", "WORKGROUP", "hunter2".toCharArray()).also { resolved.add(it) }
            }
        }
        val pool = pool(
            factory,
            FakeLocationManager(mapOf(passwordServer.id to passwordServer)),
            credentialStore = credentialStore,
        )

        pool.listShares(passwordServer.id)
        factory.listError = SmbException(SmbException.Kind.AUTHENTICATION)
        shouldThrow<SmbAuthException> { pool.listShares(passwordServer.id) }

        val sent = factory.listings.map { it.second.shouldBeInstanceOf<SmbCredentials.Password>() }
        sent.map { it.username } shouldBe listOf("darken", "darken")
        sent.map { it.domain } shouldBe listOf("WORKGROUP", "WORKGROUP")
        resolved.size shouldBe 2
        resolved.forEach { credential -> credential.password.all { it == Char(0) } shouldBe true }
    }

    @Test
    fun `listing failures are mapped`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        factory.listError = SmbException(SmbException.Kind.ACCESS_DENIED)
        shouldThrow<SmbShareListingUnavailableException> { pool.listShares(serverLocation.id) }

        factory.listError = SmbException(SmbException.Kind.TRANSPORT)
        shouldThrow<SmbUnreachableException> { pool.listShares(serverLocation.id) }
    }

    @Test
    fun `a session is reused across operations`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { }

        factory.clients.size shouldBe 1
    }

    @Test
    fun `two locations on the same endpoint get their own session`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(SmbPath.root(locationB.id), retryOnTransportLoss = false) { }

        factory.clients.size shouldBe 2
    }

    @Test
    fun `evicting one location leaves the other connected`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(SmbPath.root(locationB.id), retryOnTransportLoss = false) { }

        pool.evict(locationA.id)

        verify { factory.shares[0].disconnect() }
        verify(exactly = 0) { factory.shares[1].disconnect() }
    }

    @Test
    fun `an idempotent operation retries exactly once after a transport loss`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        var attempts = 0

        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = true) {
            attempts++
            if (attempts == 1) throw SmbException(SmbException.Kind.TRANSPORT)
        }

        attempts shouldBe 2
        factory.clients.size shouldBe 2
    }

    @Test
    fun `a mutation is never replayed`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        var attempts = 0

        shouldThrow<SmbException> {
            pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) {
                attempts++
                throw SmbException(SmbException.Kind.TRANSPORT)
            }
        }

        attempts shouldBe 1
    }

    @Test
    fun `a failing operation still releases its lease`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)

        shouldThrow<IllegalStateException> {
            pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { error("boom") }
        }

        // A leaked lease would keep the generation alive past the eviction
        pool.evict(locationA.id)
        verify { factory.shares[0].disconnect() }
    }

    @Test
    fun `a stale generation closes only after its last lease is returned`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        val lease = pool.acquire(locationA.rootPath)

        pool.evict(locationA.id)
        verify(exactly = 0) { factory.shares[0].disconnect() }

        lease.close()
        verify { factory.shares[0].disconnect() }
    }

    @Test
    fun `closing a lease twice releases it once`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        val first = pool.acquire(locationA.rootPath)
        val second = pool.acquire(locationA.rootPath)

        first.close()
        first.close()
        pool.evict(locationA.id)
        verify(exactly = 0) { factory.shares[0].disconnect() }

        second.close()
        verify { factory.shares[0].disconnect() }
    }

    @Test
    fun `an idle generation is closed, a leased one is not`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { }
        val leased = pool.acquire(locationB.rootPath)

        pool.trimIdle(FAR_FUTURE)

        verify { factory.shares[0].disconnect() }
        verify(exactly = 0) { factory.shares[1].disconnect() }
        leased.close()
    }

    /**
     * The idle sweep and a caller asking for the same location run into each other. Whichever wins,
     * the caller must never be handed the share the sweep just closed: either the sweep gets there
     * first and the caller connects a fresh session, or the caller leases first and the sweep skips
     * a generation that is no longer idle.
     */
    @Test
    fun `the idle sweep never closes a session that is being handed out`() = runBlocking {
        repeat(RACE_ROUNDS) {
            val factory = FakeClientFactory()
            val pool = pool(factory)
            // Cached, unleased and past the idle timeout: exactly what the sweep collects
            pool.acquire(locationA.rootPath).close()

            val start = CyclicBarrier(2)
            val sweep = async(Dispatchers.IO) {
                start.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                pool.trimIdle(FAR_FUTURE)
            }
            val lease = async(Dispatchers.IO) {
                start.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                pool.acquire(locationA.rootPath)
            }.await()
            sweep.await()

            verify(exactly = 0) { lease.share.disconnect() }
            lease.close()
        }
    }

    @Test
    fun `a share that was closed under us is never handed out`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        val first = pool.acquire(locationA.rootPath)
        first.close()

        factory.shareConnected[0] = false

        val second = pool.acquire(locationA.rootPath)

        second.share shouldNotBe first.share
        second.share.connected shouldBe true
        factory.clients.size shouldBe 2
        second.close()
    }

    @Test
    fun `closing the pool closes every session`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(SmbPath.root(locationB.id), retryOnTransportLoss = false) { }

        pool.close()

        verify { factory.shares[0].disconnect() }
        verify { factory.shares[1].disconnect() }
    }

    @Test
    fun `the pool reconnects after it was closed`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.acquire(locationA.rootPath).close()

        pool.close()
        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { }

        factory.clients.size shouldBe 2
    }

    @Test
    fun `an edited endpoint gets a new session while the old one drains`() = runTest {
        val factory = FakeClientFactory()
        val locations = FakeLocationManager(mapOf(locationA.id to locationA))
        val pool = pool(factory, locations)
        val inFlight = pool.acquire(locationA.rootPath)

        locations.locationsById = mapOf(locationA.id to locationA.copy(host = "other.local"))
        val afterEdit = pool.acquire(locationA.rootPath)

        afterEdit.location.host shouldBe "other.local"
        factory.clients.size shouldBe 2
        // The old session is only closed once the operation still using it is done
        verify(exactly = 0) { factory.shares[0].disconnect() }
        inFlight.close()
        verify { factory.shares[0].disconnect() }

        afterEdit.close()
    }

    /**
     * The connect that started first finishes last: it must not hand its old-endpoint session to the
     * caller that asked after the edit.
     */
    @Test
    fun `a connect that finishes late never replaces the edited endpoint`() = runTest {
        val factory = FakeClientFactory()
        val passwordLocation = locationA.copy(
            authType = SmbLocation.AuthType.PASSWORD,
            username = "darken",
        )
        val locations = FakeLocationManager(mapOf(passwordLocation.id to passwordLocation))
        // One gate per connect, in call order, so both can be held mid-negotiation
        val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        var resolveCalls = 0
        val credentialStore = mockk<SmbCredentialStore>(relaxed = true) {
            every { this@mockk.evictions } returns MutableSharedFlow()
            coEvery { resolve(any()) } coAnswers {
                gates[resolveCalls++].await()
                SmbCredential("darken", null, "hunter2".toCharArray())
            }
        }
        val pool = SmbConnectionPool(
            appScope = TestScope(),
            locationManager = locations,
            credentialStore = credentialStore,
            clientFactory = factory,
            upgradeRepo = FakeUpgradeRepo(),
        )

        val beforeEdit = async { pool.acquire(passwordLocation.rootPath) }
        runCurrent()

        locations.locationsById = mapOf(passwordLocation.id to passwordLocation.copy(host = "other.local"))
        val afterEdit = async { pool.acquire(passwordLocation.rootPath) }
        runCurrent()

        // The old-endpoint connect publishes first, the post-edit one right after
        gates[0].complete(Unit)
        runCurrent()
        gates[1].complete(Unit)
        runCurrent()

        val oldLease = beforeEdit.await()
        val newLease = afterEdit.await()
        oldLease.location.host shouldBe "nas.local"
        newLease.location.host shouldBe "other.local"

        oldLease.close()
        newLease.close()

        val next = pool.acquire(passwordLocation.rootPath)
        next.location.host shouldBe "other.local"
        next.close()
    }

    /**
     * The credential was resolved, then forgotten and the location evicted while the session was still
     * negotiating. That session must not be published, it would keep serving without a credential.
     */
    @Test
    fun `a connect that finishes after its location was evicted is dropped`() = runTest {
        val factory = FakeClientFactory()
        val passwordLocation = locationA.copy(
            authType = SmbLocation.AuthType.PASSWORD,
            username = "darken",
        )
        val locations = FakeLocationManager(mapOf(passwordLocation.id to passwordLocation))
        var credentialAvailable = true
        val credentialStore = mockk<SmbCredentialStore>(relaxed = true) {
            every { this@mockk.evictions } returns MutableSharedFlow()
            coEvery { resolve(any()) } coAnswers {
                if (!credentialAvailable) throw SmbCredentialUnavailableException(passwordLocation.id, "Session ended")
                SmbCredential("darken", null, "hunter2".toCharArray())
            }
        }
        val pool = SmbConnectionPool(
            appScope = TestScope(),
            locationManager = locations,
            credentialStore = credentialStore,
            clientFactory = factory,
            upgradeRepo = FakeUpgradeRepo(),
        )

        val gate = CompletableDeferred<Unit>()
        factory.nextConnectGate = gate
        val pending = async { runCatching { pool.acquire(passwordLocation.rootPath) } }
        runCurrent()
        factory.clients.size shouldBe 1

        credentialAvailable = false
        pool.evict(passwordLocation.id)
        gate.complete(Unit)
        runCurrent()

        // The retry resolves the credential again and finds it gone
        pending.await().exceptionOrNull().shouldBeInstanceOf<SmbCredentialUnavailableException>()
        verify { factory.shares[0].disconnect() }
        factory.clients.size shouldBe 1

        credentialAvailable = true
        val next = pool.acquire(passwordLocation.rootPath)
        next.share shouldNotBe factory.shares[0]
        factory.clients.size shouldBe 2
        next.close()
    }

    /**
     * A server that stopped answering makes closing its session sit in the socket timeout. That
     * teardown must not run under the pool-wide lock, or every unrelated location stalls with it.
     */
    @Test
    fun `an edited endpoint drops its old session without stalling other locations`() = runBlocking {
        val factory = FakeClientFactory()
        val locations = FakeLocationManager(mapOf(locationA.id to locationA, locationB.id to locationB))
        val pool = pool(factory, locations)
        pool.acquire(locationA.rootPath).close()

        val teardownStarted = CountDownLatch(1)
        val teardownRelease = CountDownLatch(1)
        factory.closeHooks[0] = {
            teardownStarted.countDown()
            teardownRelease.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        locations.locationsById = mapOf(
            locationA.id to locationA.copy(host = "other.local"),
            locationB.id to locationB,
        )
        val displacing = async(Dispatchers.IO) { pool.acquire(locationA.rootPath) }
        teardownStarted.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true

        val unrelated = withTimeoutOrNull(PROGRESS_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { pool.acquire(locationB.rootPath) }
        }
        teardownRelease.countDown()

        unrelated shouldNotBe null
        unrelated?.close()
        displacing.await().close()
    }

    /** The same teardown one path further along: the dead session is only replaced when the fresh one is published. */
    @Test
    fun `replacing a dead session does not stall other locations`() = runBlocking {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.acquire(locationA.rootPath).close()

        val teardownStarted = CountDownLatch(1)
        val teardownRelease = CountDownLatch(1)
        factory.closeHooks[0] = {
            teardownStarted.countDown()
            teardownRelease.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        // The cached generation is skipped on lookup and only disposed of when connect() publishes
        factory.shareConnected[0] = false
        val reconnecting = async(Dispatchers.IO) { pool.acquire(locationA.rootPath) }
        teardownStarted.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true

        val unrelated = withTimeoutOrNull(PROGRESS_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { pool.acquire(locationB.rootPath) }
        }
        teardownRelease.countDown()

        unrelated shouldNotBe null
        unrelated?.close()
        reconnecting.await().close()
    }

    companion object {
        private const val FAR_FUTURE = Long.MAX_VALUE / 2
        private const val RACE_ROUNDS = 100
        private const val BARRIER_TIMEOUT_SECONDS = 10L
        private const val PROGRESS_TIMEOUT_MS = 5_000L
    }
}
