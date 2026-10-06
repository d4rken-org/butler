package eu.darken.butler.setup.core.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.shizuku.AdbAvailability
import eu.darken.butler.common.adb.shizuku.AdbBackend
import eu.darken.butler.common.adb.shizuku.AdbConnectionState
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
    private lateinit var connectionStateFlow: MutableStateFlow<AdbConnectionState>
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
        connectionStateFlow = MutableStateFlow(AdbConnectionState.Disconnected)
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        every { context.packageManager } returns packageManager
        val useShizukuValue = useShizukuFlow.asDataStoreValue()
        every { adbSettings.useShizuku } returns useShizukuValue

        every { shizukuManager.connectionState } returns connectionStateFlow
        every { shizukuManager.shizukuBinder } returns connectionStateFlow.map {
            (it as? AdbConnectionState.Connected)?.server
        }
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
        connectionStateFlow.value = AdbConnectionState.Connected(server)
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
        connectionStateFlow.value = AdbConnectionState.Connected(server)
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
        connectionStateFlow.value = AdbConnectionState.Connected(server)
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

        connectionStateFlow.value = AdbConnectionState.Connected(server)
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
        connectionStateFlow.value = AdbConnectionState.Connected(server)

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
        connectionStateFlow.value = AdbConnectionState.Incompatible(AdbBackend.PORTER, true, false)
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
        connectionStateFlow.value = AdbConnectionState.Incompatible(AdbBackend.PORTER, false, true)
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
        every { server.backend } returns AdbBackend.PORTER
        coEvery { shizukuManager.availability() } returns AdbAvailability.Connected(AdbBackend.PORTER, null)
        connectionStateFlow.value = AdbConnectionState.Connected(server)

        val collector = module().state.test(tag = "orphan", scope = scope)
        val result = collector.awaitResult { it.basicService }

        result.isInstalled shouldBe true
        result.backend shouldBe AdbBackend.PORTER
        result.pkg shouldBe null
        result.managerLabel shouldBe null

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `a denial from the server reaches the result`() {
        connectionStateFlow.value = AdbConnectionState.Connected(server)
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

    @Test fun `an incompatible Shizuku server still opens its launchable sibling rather than Porter`() {
        connectionStateFlow.value = AdbConnectionState.Incompatible(AdbBackend.SHIZUKU, true, false)
        coEvery { shizukuManager.availability() } returns AdbAvailability.Incompatible(
            AdbBackend.SHIZUKU, COMPAT_HUB.name, serverTooOld = true, clientTooOld = false,
        )
        coEvery { shizukuManager.getManagerIds(AdbBackend.SHIZUKU) } returns listOf(COMPAT_HUB, SHIZUKU_PLUS)
        coEvery { shizukuManager.getManagerIds(AdbBackend.PORTER) } returns listOf(PORTER)
        every { packageManager.getLaunchIntentForPackage(COMPAT_HUB.name) } returns null
        every { packageManager.getLaunchIntentForPackage(SHIZUKU_PLUS.name) } returns mockk<Intent>()

        module().firstResult().apply {
            isCompatible shouldBe false
            serverTooOld shouldBe true
            backend shouldBe AdbBackend.SHIZUKU
            pkg shouldBe SHIZUKU_PLUS
        }
        coVerify(exactly = 0) { shizukuManager.getManagerIds(AdbBackend.PORTER) }
        coVerify(exactly = 0) { shizukuManager.getServiceState() }
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

    @Test fun `a connected ungranted manager is never asked without a user action`() {
        coEvery { shizukuManager.isGranted() } returns false
        val collector = module().state.test(tag = "no-auto-prompt", scope = scope)
        collector.awaitResult { !it.basicService }

        connectionStateFlow.value = AdbConnectionState.Connected(server)
        collector.awaitResult { it.basicService }
        runBlocking { delay(300) }

        coVerify(exactly = 0) { shizukuManager.requestPermission() }

        runBlocking { collector.cancelAndJoin() }
    }

    /** Switches on with a connected manager that has not granted Butler, answering its prompt with [answer]. */
    private fun switchOnAsking(answer: suspend () -> AdbPermissionState): AtomicInteger {
        useShizukuFlow.value = null
        connectionStateFlow.value = AdbConnectionState.Connected(server)
        coEvery { shizukuManager.isGranted() } returns false
        val requests = AtomicInteger(0)
        coEvery { shizukuManager.requestPermission() } coAnswers {
            requests.incrementAndGet()
            answer()
        }
        val mod = module().apply { permissionRequestTimeoutMs = 200 }

        runBlocking { mod.toggleUseShizuku(true) }

        return requests
    }

    @Test fun `a granted prompt on enable keeps the switch on`() {
        val requests = switchOnAsking { AdbPermissionState.Granted }

        requests.get() shouldBe 1
        useShizukuFlow.value shouldBe true
    }

    @Test fun `a denied prompt on enable keeps the switch on`() {
        val requests = switchOnAsking { AdbPermissionState.Denied(permanentlyDenied = false) }

        requests.get() shouldBe 1
        useShizukuFlow.value shouldBe true
    }

    @Test fun `an unanswered prompt on enable keeps the switch on`() {
        val requests = switchOnAsking { awaitCancellation() }

        requests.get() shouldBe 1
        useShizukuFlow.value shouldBe true
    }

    @Test fun `enabling without a reachable server does not ask`() {
        useShizukuFlow.value = null
        every { shizukuManager.useShizuku } returns flowOf(true)
        coEvery { shizukuManager.isGranted() } returns null

        runBlocking { module().toggleUseShizuku(true) }

        coVerify(exactly = 0) { shizukuManager.requestPermission() }
        useShizukuFlow.value shouldBe true
    }

    @Test fun `switching off does not ask`() {
        coEvery { shizukuManager.isGranted() } returns false

        runBlocking { module().toggleUseShizuku(null) }

        coVerify(exactly = 0) { shizukuManager.requestPermission() }
        useShizukuFlow.value shouldBe null
    }

    @Test fun `grant access asks and re-probes`() {
        connectionStateFlow.value = AdbConnectionState.Connected(server)
        coEvery { shizukuManager.isGranted() } returns false
        val mod = module()
        val collector = mod.state.test(tag = "grant", scope = scope)
        collector.awaitResult { it.basicService && it.permissionState == AdbPermissionState.Granted }
        val before = probeCount

        runBlocking { mod.grantAccess() }
        collector.await { _, _ -> probeCount > before }

        coVerify(exactly = 1) { shizukuManager.requestPermission() }
        probeCount shouldBeGreaterThan before

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `grant access during a switch-on prompt joins it`() {
        useShizukuFlow.value = null
        connectionStateFlow.value = AdbConnectionState.Connected(server)
        coEvery { shizukuManager.isGranted() } returns false
        val answer = CompletableDeferred<AdbPermissionState>()
        val requests = AtomicInteger(0)
        coEvery { shizukuManager.requestPermission() } coAnswers {
            requests.incrementAndGet()
            answer.await()
        }
        val mod = module()

        val toggle = scope.launch { mod.toggleUseShizuku(true) }
        eventually { requests.get() == 1 }
        val grant = scope.launch { mod.grantAccess() }

        answer.complete(AdbPermissionState.Granted)
        eventually { toggle.isCompleted && grant.isCompleted }

        requests.get() shouldBe 1
        useShizukuFlow.value shouldBe true
    }

    @Test fun `grant access after an unanswered prompt asks again`() {
        connectionStateFlow.value = AdbConnectionState.Connected(server)
        val requests = AtomicInteger(0)
        coEvery { shizukuManager.requestPermission() } coAnswers {
            if (requests.incrementAndGet() == 1) awaitCancellation() else AdbPermissionState.Granted
        }
        val mod = module().apply { permissionRequestTimeoutMs = 200 }

        runBlocking { mod.grantAccess() }
        requests.get() shouldBe 1

        runBlocking { mod.grantAccess() }
        requests.get() shouldBe 2
    }

    @Test fun `refusal arrival reason change departure and recovery update without refresh for every setting and backend`() = runTest {
        scope.cancel()
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val permission = MutableStateFlow<AdbPermissionState>(AdbPermissionState.Unknown)
        every { shizukuManager.permissionState } returns permission

        for (enabled in listOf(true, false, null)) {
            for ((backend, target) in listOf(AdbBackend.PORTER to PORTER, AdbBackend.SHIZUKU to SHIZUKU_PLUS)) {
                useShizukuFlow.value = enabled
                connectionStateFlow.value = AdbConnectionState.Disconnected
                permission.value = AdbPermissionState.Unknown
                every { server.backend } returns backend
                coEvery { shizukuManager.availability() } returns AdbAvailability.InstalledNotConnected(backend, target.name)
                val beforeProbes = probeCount
                val seen = mutableListOf<SetupModule.State>()
                val job = launch { module().state.toList(seen) }
                runCurrent()
                fun latest() = seen.last().shouldBeInstanceOf<ShizukuSetupModule.Result>()
                latest().isCompatible shouldBe true
                latest().basicService shouldBe false

                connectionStateFlow.value = AdbConnectionState.Incompatible(backend, true, false)
                runCurrent()
                latest().apply {
                    isInstalled shouldBe true
                    isCompatible shouldBe false
                    serverTooOld shouldBe true
                    clientTooOld shouldBe false
                    this.backend shouldBe backend
                    pkg shouldBe target
                    permissionState shouldBe AdbPermissionState.Unknown
                    basicService shouldBe false
                }

                connectionStateFlow.value = AdbConnectionState.Incompatible(backend, false, true)
                runCurrent()
                latest().apply {
                    isCompatible shouldBe false
                    serverTooOld shouldBe false
                    clientTooOld shouldBe true
                    this.backend shouldBe backend
                    pkg shouldBe target
                }
                probeCount shouldBe beforeProbes

                // Metadata can lag a departure; only the snapshot owns compatibility.
                coEvery { shizukuManager.availability() } returns AdbAvailability.Incompatible(backend, target.name, false, true)
                connectionStateFlow.value = AdbConnectionState.Disconnected
                runCurrent()
                latest().apply {
                    isInstalled shouldBe true
                    isCompatible shouldBe true
                    serverTooOld shouldBe false
                    clientTooOld shouldBe false
                    basicService shouldBe false
                }

                connectionStateFlow.value = AdbConnectionState.Connected(server)
                permission.value = AdbPermissionState.Granted
                runCurrent()
                latest().apply {
                    isCompatible shouldBe true
                    this.backend shouldBe backend
                    pkg shouldBe target
                    basicService shouldBe (enabled == true)
                    permissionState shouldBe if (enabled == true) AdbPermissionState.Granted else AdbPermissionState.Unknown
                    serviceState shouldBe if (enabled == true) ShizukuServiceState.Available else ShizukuServiceState.NotChecked
                }
                if (enabled != true) probeCount shouldBe beforeProbes
                coVerify(exactly = 0) { shizukuManager.requestPermission() }
                job.cancel()
                runCurrent()
            }
        }
    }

    @Test fun `incompatible with unreadable metadata still reports installation and the correct update side`() = runTest {
        scope.cancel()
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        coEvery { shizukuManager.availability() } returns null
        for (enabled in listOf(true, false, null)) {
            useShizukuFlow.value = enabled
            for (backend in AdbBackend.entries) {
                for (serverTooOld in listOf(true, false)) {
                    connectionStateFlow.value = AdbConnectionState.Incompatible(backend, serverTooOld, !serverTooOld)
                    val seen = mutableListOf<SetupModule.State>()
                    val job = launch { module().state.toList(seen) }
                    runCurrent()
                    seen.last().shouldBeInstanceOf<ShizukuSetupModule.Result>().apply {
                        isInstalled shouldBe true
                        isCompatible shouldBe false
                        this.backend shouldBe backend
                        this.serverTooOld shouldBe serverTooOld
                        clientTooOld shouldBe !serverTooOld
                        pkg shouldBe null
                        managerLabel shouldBe null
                        basicService shouldBe false
                        permissionState shouldBe AdbPermissionState.Unknown
                    }
                    job.cancel()
                    runCurrent()
                }
            }
        }
        coVerify(exactly = 0) { shizukuManager.getServiceState() }
        coVerify(exactly = 0) { shizukuManager.requestPermission() }
    }

    @Test fun `new state cancels an older suspended metadata probe in every setting`() = runTest {
        scope.cancel()
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        for (enabled in listOf(true, false, null)) {
            useShizukuFlow.value = enabled
            connectionStateFlow.value = AdbConnectionState.Disconnected
            var started = false
            var cancelled = false
            coEvery { shizukuManager.availability() } coAnswers {
                started = true
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            val seen = mutableListOf<SetupModule.State>()
            val job = launch { module().state.toList(seen) }
            runCurrent()
            started shouldBe true
            seen.filterIsInstance<ShizukuSetupModule.Result>() shouldBe emptyList()

            coEvery { shizukuManager.availability() } returns null
            connectionStateFlow.value = AdbConnectionState.Incompatible(AdbBackend.PORTER, true, false)
            runCurrent()
            cancelled shouldBe true
            val results = seen.filterIsInstance<ShizukuSetupModule.Result>()
            results.isNotEmpty() shouldBe true
            results.all { !it.isCompatible && it.isInstalled && it.serverTooOld } shouldBe true
            job.cancel()
            runCurrent()
        }
    }

    @Test fun `a refused server cancels an older suspended service probe`() = runTest {
        scope.cancel()
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        connectionStateFlow.value = AdbConnectionState.Connected(server)
        var started = false
        var cancelled = false
        coEvery { shizukuManager.getServiceState() } coAnswers {
            started = true
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val seen = mutableListOf<SetupModule.State>()
        val job = launch { module().state.toList(seen) }
        runCurrent()
        started shouldBe true
        connectionStateFlow.value = AdbConnectionState.Incompatible(AdbBackend.SHIZUKU, false, true)
        runCurrent()
        cancelled shouldBe true
        val results = seen.filterIsInstance<ShizukuSetupModule.Result>()
        results.isNotEmpty() shouldBe true
        results.all { !it.isCompatible && !it.ourService && it.clientTooOld } shouldBe true
        job.cancel()
    }

    @Test fun `cached incompatible resubscription never emits a synthetic compatible result`() = runTest {
        scope.cancel()
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        for (enabled in listOf(true, false, null)) {
            useShizukuFlow.value = enabled
            connectionStateFlow.value = AdbConnectionState.Incompatible(AdbBackend.PORTER, true, false)
            val mod = module()
            val first = mutableListOf<SetupModule.State>()
            val firstJob = launch { mod.state.toList(first) }
            runCurrent()
            first.last().shouldBeInstanceOf<ShizukuSetupModule.Result>().isCompatible shouldBe false
            firstJob.cancel()
            runCurrent() // Stop the share and clear its replay buffer.

            val second = mutableListOf<SetupModule.State>()
            val secondJob = launch { mod.state.toList(second) }
            runCurrent()
            second.first().shouldBeInstanceOf<ShizukuSetupModule.Result>().isCompatible shouldBe false
            second.size shouldBeGreaterThan 1 // Cached result followed by a fresh probe.
            second.filterIsInstance<ShizukuSetupModule.Result>().all { !it.isCompatible && it.serverTooOld } shouldBe true
            secondJob.cancel()
            runCurrent()
        }
    }

    companion object {
        private val PORTER = "eu.darken.porter".toPkgId()
        private val SHIZUKU = "moe.shizuku.privileged.api".toPkgId()
        private val COMPAT_HUB: Pkg.Id = SHIZUKU
        private val SHIZUKU_PLUS = "af.shizuku.plus".toPkgId()
        private val FORK = "com.example.shizuku.fork".toPkgId()
    }
}
