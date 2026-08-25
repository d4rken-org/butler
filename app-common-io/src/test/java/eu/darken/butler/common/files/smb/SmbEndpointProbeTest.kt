package eu.darken.butler.common.files.smb

import eu.darken.butler.common.files.smb.location.SmbLocation
import eu.darken.butler.common.network.NetworkStateProvider
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class SmbEndpointProbeTest : BaseTest() {

    private val locationId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val otherLocationId = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private val ipv6 = InetAddress.getByAddress(
        "nas.local",
        byteArrayOf(0x20, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
    )
    private val ipv4 = InetAddress.getByAddress("nas.local", byteArrayOf(192.toByte(), 168.toByte(), 1, 50))

    private fun location(id: Uuid = locationId, host: String = "nas.local") = SmbLocation(
        id = id,
        label = null,
        host = host,
        share = "media",
        authType = SmbLocation.AuthType.GUEST,
        rememberCredential = false,
        credentialVersion = 1,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private class FakeResolver(private val result: () -> List<InetAddress>) : SmbEndpointProbe.Resolver {
        override fun resolve(host: String): List<InetAddress> = result()
    }

    private class FakeConnector(
        private val behavior: suspend (InetAddress) -> Unit = {},
    ) : SmbEndpointProbe.Connector {
        val attempts = mutableListOf<InetAddress>()
        override suspend fun connect(address: InetAddress, port: Int, timeout: Duration) {
            attempts.add(address)
            behavior(address)
        }
    }

    private class FakeClock(var current: Instant = Instant.fromEpochMilliseconds(0)) : SmbEndpointProbe.Clock {
        override fun now(): Instant = current
    }

    private val appScopes = mutableListOf<CoroutineScope>()

    @AfterEach
    fun cancelAppScopes() {
        appScopes.forEach { it.cancel() }
        appScopes.clear()
    }

    /**
     * `backgroundScope` cannot stand in for the app scope here: `advanceUntilIdle` stops as soon as
     * only background work is left, so a probe launched there would never run. This scope shares the
     * test's scheduler but counts as foreground work.
     */
    private fun TestScope.appScope() = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        .also { appScopes.add(it) }

    private fun TestScope.createProbe(
        resolver: SmbEndpointProbe.Resolver,
        connector: SmbEndpointProbe.Connector,
        clock: SmbEndpointProbe.Clock = FakeClock(),
    ) = SmbEndpointProbe(
        appScope = appScope(),
        dispatcherProvider = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        resolver = resolver,
        connector = connector,
        clock = clock,
        networkStateProvider = mockk<NetworkStateProvider>().apply {
            every { networkState } returns emptyFlow()
        },
    )

    @Test
    fun `the reported address is the one that answered`() = runTest {
        val connector = FakeConnector { address -> if (address == ipv6) throw IOException("no route") }
        val probe = createProbe(FakeResolver { listOf(ipv6, ipv4) }, connector)

        probe.probe(listOf(location()))
        advanceUntilIdle()

        connector.attempts shouldBe listOf(ipv6, ipv4)
        probe.states.value[locationId] shouldBe SmbEndpointState(
            address = ipv4.hostAddress,
            reachability = SmbEndpointState.Reachability.REACHABLE,
        )
    }

    @Test
    fun `a server that answers nowhere keeps its first address`() = runTest {
        val connector = FakeConnector { throw IOException("refused") }
        val probe = createProbe(FakeResolver { listOf(ipv6, ipv4) }, connector)

        probe.probe(listOf(location()))
        advanceUntilIdle()

        probe.states.value[locationId] shouldBe SmbEndpointState(
            address = ipv6.hostAddress,
            reachability = SmbEndpointState.Reachability.UNREACHABLE,
        )
    }

    @Test
    fun `a host that does not resolve has no address to show`() = runTest {
        val probe = createProbe(FakeResolver { throw IOException("unknown host") }, FakeConnector())

        probe.probe(listOf(location()))
        advanceUntilIdle()

        probe.states.value[locationId] shouldBe SmbEndpointState(
            address = null,
            reachability = SmbEndpointState.Reachability.UNREACHABLE,
        )
    }

    @Test
    fun `a probe that hangs is given up on`() = runTest {
        val connector = FakeConnector { delay(Duration.INFINITE) }
        val probe = createProbe(FakeResolver { listOf(ipv4) }, connector)

        probe.probe(listOf(location()))
        runCurrent()
        probe.states.value[locationId]?.reachability shouldBe SmbEndpointState.Reachability.CHECKING

        advanceTimeBy(30.seconds)
        probe.states.value[locationId]?.reachability shouldBe SmbEndpointState.Reachability.UNREACHABLE
    }

    @Test
    fun `a connector that blows up does not escape into the list`() = runTest {
        val connector = FakeConnector { throw IllegalStateException("boom") }
        val probe = createProbe(FakeResolver { listOf(ipv4) }, connector)

        probe.probe(listOf(location()))
        advanceUntilIdle()

        probe.states.value[locationId]?.reachability shouldBe SmbEndpointState.Reachability.UNREACHABLE
    }

    @Test
    fun `two locations on the same server share one probe`() = runTest {
        val connector = FakeConnector()
        val probe = createProbe(FakeResolver { listOf(ipv4) }, connector)

        probe.probe(listOf(location(), location(id = otherLocationId)))
        advanceUntilIdle()

        connector.attempts shouldBe listOf(ipv4)
        probe.states.value[locationId]?.reachability shouldBe SmbEndpointState.Reachability.REACHABLE
        probe.states.value[otherLocationId]?.reachability shouldBe SmbEndpointState.Reachability.REACHABLE
    }

    @Test
    fun `a fresh result is reused, an expired one is probed again`() = runTest {
        val connector = FakeConnector()
        val clock = FakeClock()
        val probe = createProbe(FakeResolver { listOf(ipv4) }, connector, clock)

        probe.probe(listOf(location()))
        advanceUntilIdle()
        connector.attempts.size shouldBe 1

        clock.current = Instant.fromEpochMilliseconds(30_000)
        probe.probe(listOf(location()))
        advanceUntilIdle()
        connector.attempts.size shouldBe 1

        clock.current = Instant.fromEpochMilliseconds(90_000)
        probe.probe(listOf(location()))
        advanceUntilIdle()
        connector.attempts.size shouldBe 2
    }

    @Test
    fun `an explicit refresh ignores the cache`() = runTest {
        val connector = FakeConnector()
        val probe = createProbe(FakeResolver { listOf(ipv4) }, connector)

        probe.probe(listOf(location()))
        advanceUntilIdle()
        connector.attempts.size shouldBe 1

        probe.probe(listOf(location()), force = true)
        advanceUntilIdle()
        connector.attempts.size shouldBe 2
    }

    /** Leaving the Network view stops the observation, it must not kill an uncancellable lookup. */
    @Test
    fun `a probe outlives the caller that asked for it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val connector = FakeConnector { gate.await() }
        val probe = createProbe(FakeResolver { listOf(ipv4) }, connector)

        val caller = launch { probe.probe(listOf(location())) }
        runCurrent()
        caller.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        probe.states.value[locationId]?.reachability shouldBe SmbEndpointState.Reachability.REACHABLE
    }

    @Test
    fun `the real connector reaches a listening port`() = runTest {
        val server = ServerSocket(0)
        try {
            SmbEndpointProbeModule.connector().connect(InetAddress.getLoopbackAddress(), server.localPort, 5.seconds)
        } finally {
            server.close()
        }
    }

    @Test
    fun `the real connector reports a closed port`() = runTest {
        val server = ServerSocket(0)
        val port = server.localPort
        server.close()

        shouldThrow<IOException> {
            SmbEndpointProbeModule.connector().connect(InetAddress.getLoopbackAddress(), port, 5.seconds)
        }
    }
}
