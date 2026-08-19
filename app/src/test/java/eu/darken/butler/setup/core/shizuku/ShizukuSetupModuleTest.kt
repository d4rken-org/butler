package eu.darken.butler.setup.core.shizuku

import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.shizuku.ShizukuBaseServiceBinder
import eu.darken.butler.common.adb.shizuku.ShizukuManager
import eu.darken.butler.common.adb.shizuku.ShizukuServiceState
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.root.RootManager
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.flow.awaitSharingStopped
import testhelpers.flow.test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuSetupModuleTest : BaseTest() {

    private val adbSettings: AdbSettings = mockk()
    private val shizukuManager: ShizukuManager = mockk()
    private val rootManager: RootManager = mockk()

    private val useShizukuValue: DataStoreValue<Boolean?> = mockk()
    private lateinit var useShizukuFlow: MutableStateFlow<Boolean?>
    private lateinit var scope: CoroutineScope
    private var probeCount = 0

    @BeforeEach
    fun setup() {
        probeCount = 0
        useShizukuFlow = MutableStateFlow(true)
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        every { adbSettings.useShizuku } returns useShizukuValue
        every { useShizukuValue.flow } returns useShizukuFlow

        every { shizukuManager.shizukuPkgId } returns "moe.shizuku.privileged.api".toPkgId()
        every { shizukuManager.shizukuBinder } returns flowOf(null)
        every { shizukuManager.permissionGrantEvents } returns emptyFlow()
        coEvery { shizukuManager.getManagerId() } returns "moe.shizuku.privileged.api".toPkgId()
        coEvery { shizukuManager.isCompatible() } returns true
        coEvery { shizukuManager.isGranted() } returns true
        coEvery { shizukuManager.getServiceState() } coAnswers { probeCount++; ShizukuServiceState.Available }

        every { rootManager.useRoot } returns flowOf(false)
    }

    @AfterEach
    fun teardown() {
        scope.cancel()
    }

    private fun module(
        dispatchers: DispatcherProvider = TestDispatcherProvider(),
    ) = ShizukuSetupModule(scope, dispatchers, adbSettings, shizukuManager, rootManager)

    @Test fun `first subscription emits Loading then Result`() {
        val mod = module()

        val collector = mod.state.test(tag = "first", scope = scope)
        collector.await { values, _ -> values.any { it is ShizukuSetupModule.Result } }

        collector.latestValues.first().shouldBeInstanceOf<ShizukuSetupModule.Loading>()
        val result = collector.latestValues.last().shouldBeInstanceOf<ShizukuSetupModule.Result>()
        result.ourService shouldBe true

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `re-subscription emits cached Result instead of Loading`() {
        val mod = module()

        val first = mod.state.test(tag = "first", scope = scope)
        first.await { values, _ -> values.any { it is ShizukuSetupModule.Result } }
        runBlocking { first.cancelAndJoin() }
        mod.state.awaitSharingStopped() // the share must fully stop (clears the replay buffer)

        // Returning to the dashboard: the cached Result must come first so the setup card doesn't
        // flicker to Loading while the probe re-runs.
        val second = mod.state.test(tag = "second", scope = scope)
        second.await { values, _ -> values.isNotEmpty() }

        second.latestValues.first().shouldBeInstanceOf<ShizukuSetupModule.Result>()

        runBlocking { second.cancelAndJoin() }
    }

    @Test fun `re-subscription still re-runs the probe in the background`() {
        val mod = module()

        val first = mod.state.test(tag = "first", scope = scope)
        first.await { values, _ -> values.any { it is ShizukuSetupModule.Result } }
        runBlocking { first.cancelAndJoin() }
        mod.state.awaitSharingStopped()

        val before = probeCount
        val second = mod.state.test(tag = "second", scope = scope)
        second.await { _, _ -> probeCount > before } // doesn't trust the cache blindly

        probeCount shouldBeGreaterThan before

        runBlocking { second.cancelAndJoin() }
    }

    @Test fun `setting change while unsubscribed does not replay stale cache`() {
        val mod = module()

        val first = mod.state.test(tag = "first", scope = scope)
        first.await { values, _ -> values.any { it is ShizukuSetupModule.Result } }
        runBlocking { first.cancelAndJoin() }
        mod.state.awaitSharingStopped()

        // User turns Shizuku off while nothing observes the module.
        useShizukuFlow.value = false

        val second = mod.state.test(tag = "second", scope = scope)
        second.await { values, _ -> values.isNotEmpty() }

        // Cached Result was for useShizuku=true and must not be replayed for the new setting.
        second.latestValues.first().shouldBeInstanceOf<ShizukuSetupModule.Loading>()

        runBlocking { second.cancelAndJoin() }
    }

    @Test fun `refresh triggers a fresh probe`() {
        val mod = module()

        val collector = mod.state.test(tag = "refresh", scope = scope)
        collector.await { values, _ -> values.any { it is ShizukuSetupModule.Result } }
        val before = probeCount

        runBlocking { mod.refresh() }
        collector.await { _, _ -> probeCount > before }

        probeCount shouldBeGreaterThan before

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `a wedged pingBinder does not stall the state flow`() {
        // pingBinder() is a synchronous PING_TRANSACTION; a Shizuku server that is alive but not
        // servicing requests never answers it. Unbounded, that stalls the state combine and the setup
        // card is stuck on whatever it last showed. Real dispatcher: the wedge blocks a thread,
        // Unconfined would block ours.
        val pingEntered = CompletableDeferred<Unit>()
        val wedge = CountDownLatch(1)
        val binder = mockk<ShizukuBaseServiceBinder>()
        every { binder.pingBinder() } answers {
            pingEntered.complete(Unit)
            wedge.await(30, TimeUnit.SECONDS)
            true
        }
        every { shizukuManager.shizukuBinder } returns flowOf(binder)

        val mod = module(TestDispatcherProvider(Dispatchers.IO)).apply { pingTimeoutMs = 250L }

        try {
            val collector = mod.state.test(tag = "wedge", scope = scope)

            // The binder flow starts with onStart { emit(null) }, which yields a Result before any
            // ping happens. Waiting for "a Result" would therefore pass even while wedged - the state
            // that matters is the one produced for the non-null binder, so wait for the wedge to be
            // real first and then require a FURTHER emission.
            runBlocking { pingEntered.await() }
            val before = collector.latestValues.count { it is ShizukuSetupModule.Result }

            val result = collector.await(timeout = 10_000) { values, _ ->
                values.count { it is ShizukuSetupModule.Result } > before
            }

            result.shouldBeInstanceOf<ShizukuSetupModule.Result>().basicService shouldBe false

            runBlocking { collector.cancelAndJoin() }
        } finally {
            wedge.countDown()
        }
    }
}
