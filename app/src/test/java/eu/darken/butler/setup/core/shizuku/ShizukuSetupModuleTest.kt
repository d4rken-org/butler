package eu.darken.butler.setup.core.shizuku

import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.shizuku.ShizukuManager
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.root.RootManager
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.flow.test

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
        coEvery { shizukuManager.isOurServiceAvailable() } coAnswers { probeCount++; true }

        every { rootManager.useRoot } returns flowOf(false)
    }

    @AfterEach
    fun teardown() {
        scope.cancel()
    }

    private fun module() = ShizukuSetupModule(scope, adbSettings, shizukuManager, rootManager)

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
        runBlocking { delay(50) } // let the share fully stop (clears the replay buffer)

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
        runBlocking { delay(50) }

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
        runBlocking { delay(50) }

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
}
