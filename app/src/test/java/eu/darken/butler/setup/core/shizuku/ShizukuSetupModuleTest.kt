package eu.darken.butler.setup.core.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.shizuku.AdbAvailability
import eu.darken.butler.common.adb.shizuku.AdbBackend
import eu.darken.butler.common.adb.shizuku.AdbPermissionState
import eu.darken.butler.common.adb.shizuku.AdbServer
import eu.darken.butler.common.adb.shizuku.ShizukuManager
import eu.darken.butler.common.adb.shizuku.ShizukuServiceState
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.getLabel2
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.setup.core.SetupModule
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.flow.TestCollector
import testhelpers.flow.awaitSharingStopped
import testhelpers.flow.test
import java.util.concurrent.atomic.AtomicInteger

class ShizukuSetupModuleTest : BaseTest() {

    private val context: Context = mockk(relaxed = true)
    private val packageManager: PackageManager = mockk(relaxed = true)
    private val adbSettings: AdbSettings = mockk()
    private val shizukuManager: ShizukuManager = mockk()
    private val rootManager: RootManager = mockk()

    private lateinit var useShizukuFlow: MutableStateFlow<Boolean?>
    private lateinit var promptPendingFlow: MutableStateFlow<Boolean>
    private lateinit var serverFlow: MutableStateFlow<AdbServer?>
    private lateinit var scope: CoroutineScope
    private var probeCount = 0

    private val server: AdbServer = mockk<AdbServer>().also {
        every { it.backend } returns AdbBackend.SHIZUKU
    }

    private fun <T> MutableStateFlow<T>.asDataStoreValue(): DataStoreValue<T> {
        val backing = this
        val mock = mockk<DataStoreValue<T>>()
        every { mock.flow } returns backing
        coEvery { mock.update(any()) } coAnswers {
            val update = firstArg<(T) -> T?>()
            val old = backing.value
            @Suppress("UNCHECKED_CAST")
            val new = update(old) as T
            backing.value = new
            DataStoreValue.Updated(old, new)
        }
        return mock
    }

    @BeforeEach
    fun setup() {
        probeCount = 0
        useShizukuFlow = MutableStateFlow(true)
        promptPendingFlow = MutableStateFlow(false)
        serverFlow = MutableStateFlow(null)
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        every { context.packageManager } returns packageManager
        val useShizukuValue = useShizukuFlow.asDataStoreValue()
        val promptPendingValue = promptPendingFlow.asDataStoreValue()
        every { adbSettings.useShizuku } returns useShizukuValue
        every { adbSettings.permissionPromptPending } returns promptPendingValue

        every { shizukuManager.shizukuBinder } returns serverFlow
        every { shizukuManager.permissionState } returns flowOf(AdbPermissionState.Granted)
        every { shizukuManager.useShizuku } returns flowOf(false)
        coEvery { shizukuManager.availability() } returns AdbAvailability.Connected(AdbBackend.SHIZUKU, SHIZUKU.name)
        coEvery { shizukuManager.getManagerIds(any()) } returns emptyList()
        coEvery { shizukuManager.isGranted() } returns true
        coEvery { shizukuManager.requestPermission() } returns AdbPermissionState.Granted
        coEvery { shizukuManager.getServiceState() } coAnswers { probeCount++; ShizukuServiceState.Available }

        every { rootManager.useRoot } returns flowOf(false)
    }

    @AfterEach
    fun teardown() {
        scope.cancel()
    }

    private fun module() = ShizukuSetupModule(
        context,
        scope,
        TestDispatcherProvider(),
        adbSettings,
        shizukuManager,
        rootManager,
    )

    private fun ShizukuSetupModule.firstResult(): ShizukuSetupModule.Result {
        val collector = state.test(tag = "result", scope = scope)
        val result = collector.await { _, emission -> emission is ShizukuSetupModule.Result }
        runBlocking { collector.cancelAndJoin() }
        return result.shouldBeInstanceOf<ShizukuSetupModule.Result>()
    }

    private fun TestCollector<SetupModule.State>.awaitResult(
        condition: (ShizukuSetupModule.Result) -> Boolean,
    ): ShizukuSetupModule.Result = await { _, emission ->
        emission is ShizukuSetupModule.Result && condition(emission)
    } as ShizukuSetupModule.Result

    private fun eventually(condition: () -> Boolean) = runBlocking {
        withTimeout(10_000) { while (!condition()) delay(5) }
    }

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

    @Test fun `basicService follows the server connection`() {
        val mod = module()

        val collector = mod.state.test(tag = "basic", scope = scope)
        collector.awaitResult { !it.basicService }

        serverFlow.value = server
        collector.awaitResult { it.basicService }

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `nothing installed maps to not installed without an open target`() {
        coEvery { shizukuManager.availability() } returns AdbAvailability.NotInstalled

        val result = module().firstResult()

        result.isInstalled shouldBe false
        result.isAvailable shouldBe false
        result.backend shouldBe null
        result.pkg shouldBe null
    }

    @Test fun `an unreadable availability without a connection counts as not installed`() {
        coEvery { shizukuManager.availability() } returns null

        module().firstResult().isInstalled shouldBe false
    }

    @Test fun `an unreadable availability with a live connection is not reported as not installed`() {
        coEvery { shizukuManager.availability() } returns null
        serverFlow.value = server

        val collector = module().state.test(tag = "live", scope = scope)
        val result = collector.awaitResult { it.basicService }

        result.isInstalled shouldBe true
        result.backend shouldBe AdbBackend.SHIZUKU
        result.pkg shouldBe null

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `an installed manager that is not connected is the open target`() {
        coEvery { shizukuManager.availability() } returns
            AdbAvailability.InstalledNotConnected(AdbBackend.PORTER, PORTER.name)

        val result = module().firstResult()

        result.isInstalled shouldBe true
        result.isUnrecognized shouldBe false
        result.isCompatible shouldBe true
        result.backend shouldBe AdbBackend.PORTER
        result.pkg shouldBe PORTER
    }

    @Test fun `an unrecognized permission owner is treated as the manager`() {
        coEvery { shizukuManager.availability() } returns
            AdbAvailability.InstalledUnrecognized(AdbBackend.SHIZUKU, FORK.name)

        val result = module().firstResult()

        result.isInstalled shouldBe true
        result.isUnrecognized shouldBe true
        result.pkg shouldBe FORK
    }

    @Test fun `a server too old for Butler maps to a manager update`() {
        coEvery { shizukuManager.availability() } returns AdbAvailability.Incompatible(
            backend = AdbBackend.PORTER,
            packageName = PORTER.name,
            serverTooOld = true,
            clientTooOld = false,
        )

        val result = module().firstResult()

        result.isInstalled shouldBe true
        result.isCompatible shouldBe false
        result.serverTooOld shouldBe true
        result.clientTooOld shouldBe false
        result.pkg shouldBe PORTER
    }

    @Test fun `a client too old for the server maps to a Butler update`() {
        coEvery { shizukuManager.availability() } returns AdbAvailability.Incompatible(
            backend = AdbBackend.PORTER,
            packageName = PORTER.name,
            serverTooOld = false,
            clientTooOld = true,
        )

        val result = module().firstResult()

        result.isCompatible shouldBe false
        result.serverTooOld shouldBe false
        result.clientTooOld shouldBe true
    }

    @Test fun `a connected server whose manager is gone has no open target`() {
        coEvery { shizukuManager.availability() } returns AdbAvailability.Connected(AdbBackend.PORTER, null)
        serverFlow.value = server

        val collector = module().state.test(tag = "orphan", scope = scope)
        val result = collector.awaitResult { it.basicService }

        result.isInstalled shouldBe true
        result.backend shouldBe AdbBackend.PORTER
        result.pkg shouldBe null
        result.managerLabel shouldBe null

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `a denial from the server reaches the result`() {
        every { shizukuManager.permissionState } returns
            flowOf(AdbPermissionState.Denied(permanentlyDenied = true))
        coEvery { shizukuManager.getServiceState() } returns ShizukuServiceState.PermissionDenied

        val collector = module().state.test(tag = "denied", scope = scope)
        val result = collector.awaitResult { it.permissionState is AdbPermissionState.Denied }

        result.isPermissionDenied shouldBe true
        result.ourService shouldBe false

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `a disabled switch does not probe our service`() {
        useShizukuFlow.value = null

        val result = module().firstResult()

        result.useShizuku shouldBe null
        result.serviceState shouldBe ShizukuServiceState.NotChecked
        result.isComplete shouldBe false
        probeCount shouldBe 0
    }

    @Test fun `the open target's label names the manager`() {
        mockkStatic("eu.darken.butler.common.pkgs.PackageManagerExtensionsKt")
        try {
            every { packageManager.getLabel2(SHIZUKU) } returns "Shizuku"

            module().firstResult().managerLabel shouldBe "Shizuku"
        } finally {
            unmockkStatic("eu.darken.butler.common.pkgs.PackageManagerExtensionsKt")
        }
    }

    /**
     * The label lookup runs outside the availability probe's handler, so an exception there would kill
     * the sharing coroutine and strand every later subscriber on the state it died in.
     */
    @Test fun `a failing label lookup does not kill the state flow`() {
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws RuntimeException("binder died")

        val mod = module()
        val collector = mod.state.test(tag = "label", scope = scope)
        val result = collector.awaitResult { true }

        result.isInstalled shouldBe true
        result.managerLabel shouldBe null

        // Still live: a dead sharing coroutine would never serve another value.
        val before = collector.latestValues.count { it is ShizukuSetupModule.Result }
        runBlocking { mod.refresh() }
        collector.await { values, _ -> values.count { it is ShizukuSetupModule.Result } > before }

        runBlocking { collector.cancelAndJoin() }
    }

    /**
     * The permission owner can be a manager with no launcher activity (Shizuku+'s Compat Hub owns the
     * stock permission), so the open target has to be a sibling that can actually be started.
     */
    @Test fun `a launchable manager of the same family is preferred as the open target`() {
        coEvery { shizukuManager.availability() } returns AdbAvailability.Connected(AdbBackend.SHIZUKU, COMPAT_HUB.name)
        coEvery { shizukuManager.getManagerIds(AdbBackend.SHIZUKU) } returns listOf(COMPAT_HUB, SHIZUKU_PLUS)
        every { packageManager.getLaunchIntentForPackage(COMPAT_HUB.name) } returns null
        every { packageManager.getLaunchIntentForPackage(SHIZUKU_PLUS.name) } returns mockk<Intent>()

        module().firstResult().pkg shouldBe SHIZUKU_PLUS
    }

    @Test fun `without any launchable manager the permission owner stays the open target`() {
        coEvery { shizukuManager.availability() } returns AdbAvailability.Connected(AdbBackend.SHIZUKU, COMPAT_HUB.name)
        coEvery { shizukuManager.getManagerIds(AdbBackend.SHIZUKU) } returns listOf(COMPAT_HUB, SHIZUKU_PLUS)
        every { packageManager.getLaunchIntentForPackage(any()) } returns null

        // Opening it then falls back to the app info page.
        module().firstResult().pkg shouldBe COMPAT_HUB
    }

    @Test fun `the open target stays within the selected backend`() {
        coEvery { shizukuManager.availability() } returns AdbAvailability.Connected(AdbBackend.SHIZUKU, COMPAT_HUB.name)
        coEvery { shizukuManager.getManagerIds(AdbBackend.SHIZUKU) } returns listOf(COMPAT_HUB)
        coEvery { shizukuManager.getManagerIds(AdbBackend.PORTER) } returns listOf(PORTER)
        every { packageManager.getLaunchIntentForPackage(COMPAT_HUB.name) } returns null

        module().firstResult().pkg shouldBe COMPAT_HUB
    }

    @Test fun `a denied prompt on enable keeps the switch on`() {
        useShizukuFlow.value = null
        serverFlow.value = server
        coEvery { shizukuManager.isGranted() } returns false
        coEvery { shizukuManager.requestPermission() } returns AdbPermissionState.Denied(permanentlyDenied = false)

        runBlocking { module().toggleUseShizuku(true) }

        coVerify(exactly = 1) { shizukuManager.requestPermission() }
        useShizukuFlow.value shouldBe true
        promptPendingFlow.value shouldBe false
    }

    @Test fun `a permanent denial on enable keeps the switch on`() {
        useShizukuFlow.value = null
        serverFlow.value = server
        coEvery { shizukuManager.isGranted() } returns false
        coEvery { shizukuManager.requestPermission() } returns AdbPermissionState.Denied(permanentlyDenied = true)

        runBlocking { module().toggleUseShizuku(true) }

        useShizukuFlow.value shouldBe true
        promptPendingFlow.value shouldBe false
    }

    @Test fun `switching off clears a pending prompt`() {
        promptPendingFlow.value = true

        runBlocking { module().toggleUseShizuku(null) }

        useShizukuFlow.value shouldBe null
        promptPendingFlow.value shouldBe false
    }

    @Test fun `enabling without a server prompts once when one connects`() {
        useShizukuFlow.value = null
        coEvery { shizukuManager.isGranted() } returns null
        coEvery { shizukuManager.requestPermission() } returns AdbPermissionState.Denied(permanentlyDenied = false)
        val mod = module()
        val collector = mod.state.test(tag = "pending", scope = scope)

        // Waits for the connection in the background, like the view model's call would.
        scope.launch { mod.toggleUseShizuku(true) }
        eventually { useShizukuFlow.value == true }

        promptPendingFlow.value shouldBe true
        coVerify(exactly = 0) { shizukuManager.requestPermission() }

        coEvery { shizukuManager.isGranted() } returns false
        serverFlow.value = server
        eventually { !promptPendingFlow.value }

        // Neither a re-subscription nor another connection asks again.
        runBlocking { mod.refresh() }
        collector.awaitResult { it.basicService }
        val other = mockk<AdbServer>().also { every { it.backend } returns AdbBackend.SHIZUKU }
        serverFlow.value = other
        collector.awaitResult { it.basicService }

        coVerify(exactly = 1) { shizukuManager.requestPermission() }
        useShizukuFlow.value shouldBe true

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `a connection without a pending prompt does not prompt`() {
        coEvery { shizukuManager.isGranted() } returns false
        val collector = module().state.test(tag = "no-flag", scope = scope)
        collector.awaitResult { !it.basicService }

        serverFlow.value = server
        collector.awaitResult { it.basicService }

        coVerify(exactly = 0) { shizukuManager.requestPermission() }

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `a replacement server during a pending prompt is prompted once the first request ends`() {
        promptPendingFlow.value = true
        coEvery { shizukuManager.isGranted() } returns false
        val firstAnswer = CompletableDeferred<AdbPermissionState>()
        val requests = AtomicInteger(0)
        coEvery { shizukuManager.requestPermission() } coAnswers {
            if (requests.incrementAndGet() == 1) firstAnswer.await() else AdbPermissionState.Granted
        }
        val collector = module().state.test(tag = "replacement", scope = scope)
        collector.awaitResult { !it.basicService }

        // Connection 1 shows up: the pending prompt starts and waits for the user's answer.
        serverFlow.value = server
        eventually { requests.get() == 1 }

        // Server restart before the user answered: connection 2 replaces connection 1.
        val before = probeCount
        val replacement = mockk<AdbServer>().also { every { it.backend } returns AdbBackend.SHIZUKU }
        serverFlow.value = replacement
        eventually { probeCount > before }
        runBlocking { delay(200) } // let the trigger's launch for connection 2 run

        // Connection 1's request ends without an answer because its connection went away.
        firstAnswer.complete(AdbPermissionState.Unknown)
        runBlocking { withTimeoutOrNull(3_000) { while (requests.get() < 2) delay(5) } }

        withClue("requestPermission() calls after connection 1 ended with Unknown while connection 2 is live") {
            requests.get() shouldBe 2
        }
        eventually { !promptPendingFlow.value }

        runBlocking { collector.cancelAndJoin() }
    }

    companion object {
        private val PORTER = "eu.darken.porter".toPkgId()
        private val SHIZUKU = "moe.shizuku.privileged.api".toPkgId()
        private val COMPAT_HUB: Pkg.Id = SHIZUKU
        private val SHIZUKU_PLUS = "af.shizuku.plus".toPkgId()
        private val FORK = "com.example.shizuku.fork".toPkgId()
    }
}
