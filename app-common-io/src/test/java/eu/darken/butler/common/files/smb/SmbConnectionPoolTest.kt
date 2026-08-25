package eu.darken.butler.common.files.smb

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.protocol.transport.TransportException
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.extensions.Segments
import eu.darken.butler.common.files.smb.credentials.SmbCredential
import eu.darken.butler.common.files.smb.credentials.SmbCredentialStore
import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.files.smb.location.SmbLocationManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
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
            share: String,
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
            share: String,
            basePath: Segments,
            domain: String?,
            username: String?,
            authType: SmbLocation.AuthType,
            rememberCredential: Boolean,
            password: CharArray?,
        ): SmbLocation = throw UnsupportedOperationException()

        override suspend fun delete(id: Uuid) = throw UnsupportedOperationException()
    }

    /** Hands out a fresh mock stack per connect so sessions can be told apart. */
    private class FakeClientFactory : SmbClientFactory {
        val shares = mutableListOf<DiskShare>()
        val clients = mutableListOf<SMBClient>()

        override fun create(config: com.hierynomus.smbj.SmbConfig): SMBClient {
            val share = mockk<DiskShare>(relaxed = true)
            val session = mockk<Session>(relaxed = true) {
                every { connectShare(any()) } returns share
            }
            val connection = mockk<Connection>(relaxed = true) {
                every { authenticate(any()) } returns session
                every { isConnected } returns true
            }
            val client = mockk<SMBClient>(relaxed = true) {
                every { connect(any<String>(), any<Int>()) } returns connection
            }
            shares.add(share)
            clients.add(client)
            return client
        }
    }

    private fun pool(
        factory: FakeClientFactory,
        locations: List<SmbLocation> = listOf(locationA, locationB),
        evictions: SharedFlow<Uuid> = MutableSharedFlow(),
    ): SmbConnectionPool = pool(factory, FakeLocationManager(locations.associateBy { it.id }), evictions)

    private fun pool(
        factory: FakeClientFactory,
        locationManager: FakeLocationManager,
        evictions: SharedFlow<Uuid> = MutableSharedFlow(),
    ): SmbConnectionPool {
        val credentialStore = mockk<SmbCredentialStore>(relaxed = true) {
            every { this@mockk.evictions } returns evictions
        }
        return SmbConnectionPool(
            appScope = TestScope(),
            locationManager = locationManager,
            credentialStore = credentialStore,
            clientFactory = factory,
            dialectProbe = SmbDialectProbe(factory),
        )
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

        verify { factory.shares[0].close() }
        verify(exactly = 0) { factory.shares[1].close() }
    }

    @Test
    fun `an idempotent operation retries exactly once after a transport loss`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        var attempts = 0

        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = true) {
            attempts++
            if (attempts == 1) throw TransportException("connection died")
        }

        attempts shouldBe 2
        factory.clients.size shouldBe 2
    }

    @Test
    fun `a mutation is never replayed`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        var attempts = 0

        shouldThrow<TransportException> {
            pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) {
                attempts++
                throw TransportException("connection died")
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
        verify { factory.shares[0].close() }
    }

    @Test
    fun `a stale generation closes only after its last lease is returned`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        val lease = pool.acquire(locationA.id)

        pool.evict(locationA.id)
        verify(exactly = 0) { factory.shares[0].close() }

        lease.close()
        verify { factory.shares[0].close() }
    }

    @Test
    fun `closing a lease twice releases it once`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        val first = pool.acquire(locationA.id)
        val second = pool.acquire(locationA.id)

        first.close()
        first.close()
        pool.evict(locationA.id)
        verify(exactly = 0) { factory.shares[0].close() }

        second.close()
        verify { factory.shares[0].close() }
    }

    @Test
    fun `an idle generation is closed, a leased one is not`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { }
        val leased = pool.acquire(locationB.id)

        pool.trimIdle(FAR_FUTURE)

        verify { factory.shares[0].close() }
        verify(exactly = 0) { factory.shares[1].close() }
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

            verify(exactly = 0) { lease.share.close() }
            lease.close()
        }
    }

    @Test
    fun `closing the pool closes every session`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { }
        pool.use(SmbPath.root(locationB.id), retryOnTransportLoss = false) { }

        pool.close()

        verify { factory.shares[0].close() }
        verify { factory.shares[1].close() }
    }

    @Test
    fun `the pool reconnects after it was closed`() = runTest {
        val factory = FakeClientFactory()
        val pool = pool(factory)
        pool.acquire(locationA.id).close()

        pool.close()
        pool.use(SmbPath.root(locationA.id), retryOnTransportLoss = false) { }

        factory.clients.size shouldBe 2
    }

    @Test
    fun `an edited endpoint gets a new session while the old one drains`() = runTest {
        val factory = FakeClientFactory()
        val locations = FakeLocationManager(mapOf(locationA.id to locationA))
        val pool = pool(factory, locations)
        val inFlight = pool.acquire(locationA.id)

        locations.locationsById = mapOf(locationA.id to locationA.copy(host = "other.local"))
        val afterEdit = pool.acquire(locationA.id)

        afterEdit.location.host shouldBe "other.local"
        factory.clients.size shouldBe 2
        // The old session is only closed once the operation still using it is done
        verify(exactly = 0) { factory.shares[0].close() }
        inFlight.close()
        verify { factory.shares[0].close() }

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
            dialectProbe = SmbDialectProbe(factory),
        )

        val beforeEdit = async { pool.acquire(passwordLocation.id) }
        runCurrent()

        locations.locationsById = mapOf(passwordLocation.id to passwordLocation.copy(host = "other.local"))
        val afterEdit = async { pool.acquire(passwordLocation.id) }
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

        val next = pool.acquire(passwordLocation.id)
        next.location.host shouldBe "other.local"
        next.close()
    }

    companion object {
        private const val FAR_FUTURE = Long.MAX_VALUE / 2
        private const val RACE_ROUNDS = 100
        private const val BARRIER_TIMEOUT_SECONDS = 10L
    }
}
