package eu.darken.butler.setup.core.root

import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.RootSettings
import eu.darken.butler.common.root.service.RootServiceClient
import eu.darken.butler.common.root.service.RootServiceConnection
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import eu.darken.butler.common.ipc.IpcContract
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.flow.awaitSharingStopped
import testhelpers.flow.test

class RootSetupModuleTest : BaseTest() {

    private val rootSettings: RootSettings = mockk()
    private val rootManager: RootManager = mockk()

    private val useRootValue: DataStoreValue<Boolean?> = mockk()
    private lateinit var useRootFlow: MutableStateFlow<Boolean?>
    private val connection: RootServiceClient.Connection = mockk()
    private val ipc: RootServiceConnection = mockk()
    private lateinit var scope: CoroutineScope
    private var probeCount = 0

    private val hostIdentity = IpcContract.HostIdentity(
        versionCode = 12345,
        versionName = "1.2.3",
        lastUpdateTime = 1755800000000,
        packageCodePath = "/data/app/~~aB1/eu.darken.butler-Xy2/base.apk",
    )

    @BeforeEach
    fun setup() {
        probeCount = 0
        useRootFlow = MutableStateFlow(true)
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        every { rootSettings.useRoot } returns useRootValue
        every { useRootValue.flow } returns useRootFlow

        coEvery { rootManager.isInstalled() } returns true
        every { rootManager.binder } returns flowOf(connection)
        every { connection.ipc } returns ipc
        every { connection.hostIdentity } returns hostIdentity
        every { ipc.checkBase() } answers { probeCount++; "${hostIdentity.encode()}\nok" }
    }

    @AfterEach
    fun teardown() {
        scope.cancel()
    }

    private fun module() = RootSetupModule(scope, rootSettings, rootManager)

    @Test fun `first subscription emits Loading then Result`() {
        val mod = module()

        val collector = mod.state.test(tag = "first", scope = scope)
        collector.await { values, _ -> values.any { it is RootSetupModule.Result } }

        collector.latestValues.first().shouldBeInstanceOf<RootSetupModule.Loading>()
        val result = collector.latestValues.last().shouldBeInstanceOf<RootSetupModule.Result>()
        result.ourService shouldBe true

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `re-subscription emits cached Result instead of Loading`() {
        val mod = module()

        val first = mod.state.test(tag = "first", scope = scope)
        first.await { values, _ -> values.any { it is RootSetupModule.Result } }
        runBlocking { first.cancelAndJoin() }
        mod.state.awaitSharingStopped()

        val second = mod.state.test(tag = "second", scope = scope)
        second.await { values, _ -> values.isNotEmpty() }

        second.latestValues.first().shouldBeInstanceOf<RootSetupModule.Result>()

        runBlocking { second.cancelAndJoin() }
    }

    @Test fun `setting change while unsubscribed does not replay stale cache`() {
        val mod = module()

        val first = mod.state.test(tag = "first", scope = scope)
        first.await { values, _ -> values.any { it is RootSetupModule.Result } }
        runBlocking { first.cancelAndJoin() }
        mod.state.awaitSharingStopped()

        // User turns root off while nothing observes the module.
        useRootFlow.value = false

        val second = mod.state.test(tag = "second", scope = scope)
        second.await { values, _ -> values.isNotEmpty() }

        second.latestValues.first().shouldBeInstanceOf<RootSetupModule.Loading>()

        runBlocking { second.cancelAndJoin() }
    }

    @Test fun `refresh triggers a fresh probe`() {
        val mod = module()

        val collector = mod.state.test(tag = "refresh", scope = scope)
        collector.await { values, _ -> values.any { it is RootSetupModule.Result } }
        val before = probeCount

        runBlocking { mod.refresh() }
        collector.await { _, _ -> probeCount > before }

        probeCount shouldBeGreaterThan before

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `a host without an identity is not reported as our service`() {
        // Pre-handshake hosts answer with a plain diagnostic string. Its transaction codes need not
        // match ours, so the setup card must not show as complete.
        every { ipc.checkBase() } answers { probeCount++; "Our pkg: eu.darken.butler" }
        val mod = module()

        val collector = mod.state.test(tag = "no-identity", scope = scope)
        collector.await { _, value ->
            value is RootSetupModule.Result && value.serviceState is RootServiceState.Failed
        }

        collector.latestValues.last().shouldBeInstanceOf<RootSetupModule.Result>().ourService shouldBe false
    }

    @Test fun `a host from another installation is not reported as our service`() {
        // Same app version, reinstalled: only lastUpdateTime gives the leftover host away.
        val leftover = hostIdentity.copy(lastUpdateTime = hostIdentity.lastUpdateTime - 5000)
        every { ipc.checkBase() } answers { probeCount++; "${leftover.encode()}\nok" }
        val mod = module()

        val collector = mod.state.test(tag = "stale-host", scope = scope)
        collector.await { _, value ->
            value is RootSetupModule.Result && value.serviceState is RootServiceState.Failed
        }

        collector.latestValues.last().shouldBeInstanceOf<RootSetupModule.Result>().ourService shouldBe false
    }

    @Test fun `an answering host counts even if we cannot name the root manager`() {
        // The installed-manager lookup only knows a handful of package ids; a device rooted with
        // anything else still has a working service, and the service is what root access needs.
        coEvery { rootManager.isInstalled() } returns false
        val mod = module()

        val collector = mod.state.test(tag = "unknown-manager", scope = scope)
        val state = collector.await { _, value -> value is RootSetupModule.Result && value.ourService }

        val result = state.shouldBeInstanceOf<RootSetupModule.Result>()
        result.serviceState shouldBe RootServiceState.Available
        result.isInstalled shouldBe false
        result.isComplete shouldBe true

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `an unknown root manager without a matching host stays incomplete`() {
        coEvery { rootManager.isInstalled() } returns false
        val leftover = hostIdentity.copy(lastUpdateTime = hostIdentity.lastUpdateTime - 5000)
        every { ipc.checkBase() } answers { probeCount++; "${leftover.encode()}\nok" }
        val mod = module()

        val collector = mod.state.test(tag = "unknown-manager-no-host", scope = scope)
        collector.await { _, value ->
            value is RootSetupModule.Result && value.serviceState is RootServiceState.Failed
        }

        val result = collector.latestValues.last().shouldBeInstanceOf<RootSetupModule.Result>()
        result.serviceState shouldBe RootServiceState.Failed
        result.ourService shouldBe false
        result.isComplete shouldBe false

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `a binder that never resolves times out instead of staying pending`() {
        // An su prompt the user never answers leaves the binder flow silent forever. Without a budget
        // the card sits on "Connecting..." for the rest of the session.
        every { rootManager.binder } returns MutableSharedFlow()

        val mod = module().apply { connectTimeoutMs = 250L }

        val collector = mod.state.test(tag = "timeout", scope = scope)
        val state = collector.await(timeout = 10_000) { _, value ->
            value is RootSetupModule.Result && value.serviceState is RootServiceState.TimedOut
        }

        val result = state.shouldBeInstanceOf<RootSetupModule.Result>()
        result.ourService shouldBe false
        result.isComplete shouldBe false

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `the connecting state precedes the connected one`() {
        // Obtaining the connection can cold-bind an su session. The card needs something to show for
        // that window that isn't "no root".
        val mod = module()

        val collector = mod.state.test(tag = "connecting", scope = scope)
        collector.await { _, value -> value is RootSetupModule.Result && value.ourService }

        val results = collector.latestValues.filterIsInstance<RootSetupModule.Result>()
        val connecting = results.indexOfFirst { it.serviceState is RootServiceState.Connecting }
        val available = results.indexOfFirst { it.serviceState is RootServiceState.Available }

        (connecting >= 0) shouldBe true
        (connecting < available) shouldBe true

        runBlocking { collector.cancelAndJoin() }
    }
}
