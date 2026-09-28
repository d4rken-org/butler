package eu.darken.butler.common.adb.shizuku

import eu.darken.butler.common.adb.AdbConnectTimeoutException
import eu.darken.butler.common.adb.AdbSettings
import eu.darken.butler.common.adb.AdbUnavailableException
import eu.darken.butler.common.adb.service.AdbServiceClient
import eu.darken.butler.common.datastore.DataStoreValue
import eu.darken.butler.common.pkgs.toPkgId
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.flow.test

class ShizukuManagerTest : BaseTest() {

    private val settings: AdbSettings = mockk()
    private val shizukuWrapper: ShizukuWrapper = mockk()
    private val serviceClient: AdbServiceClient = mockk(relaxed = true)

    private val useShizukuValue: DataStoreValue<Boolean?> = mockk()
    private lateinit var useShizukuFlow: MutableStateFlow<Boolean?>
    private lateinit var connectionFlow: MutableStateFlow<AdbServer?>
    private lateinit var permissionFlow: MutableStateFlow<AdbPermissionState>
    private lateinit var scope: CoroutineScope

    private var connectionSubscriptions = 0

    @BeforeEach
    fun setup() {
        connectionSubscriptions = 0
        useShizukuFlow = MutableStateFlow(true)
        connectionFlow = MutableStateFlow(null)
        permissionFlow = MutableStateFlow(AdbPermissionState.Unknown)
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        every { settings.useShizuku } returns useShizukuValue
        every { useShizukuValue.flow } returns useShizukuFlow

        every { shizukuWrapper.permissionState } returns permissionFlow
        // Tracks whether the SDK connection flow is ever collected.
        every { shizukuWrapper.connection } returns connectionFlow.onStart { connectionSubscriptions++ }
    }

    @AfterEach
    fun teardown() {
        scope.cancel()
    }

    private fun manager() = ShizukuManager(
        appScope = scope,
        dispatcherProvider = TestDispatcherProvider(),
        settings = settings,
        shizukuWrapper = shizukuWrapper,
        serviceClient = serviceClient,
    )

    private fun setAvailability(availability: AdbAvailability?) {
        coEvery { shizukuWrapper.availability() } returns availability
    }

    private fun setManagerPackages(vararg pkgs: String) {
        coEvery { shizukuWrapper.getManagerPackages() } returns pkgs.toList()
    }

    @Test fun `binder stays closed when user opted out`() {
        useShizukuFlow.value = false
        connectionFlow.value = mockk()
        val mgr = manager()

        val collector = mgr.shizukuBinder.test(tag = "binder", scope = scope)
        collector.await { values, _ -> values.isNotEmpty() }

        collector.latestValues.last() shouldBe null
        connectionSubscriptions shouldBe 0

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `binder follows the connection when the user opted in`() {
        val server = mockk<AdbServer>()
        connectionFlow.value = server
        val mgr = manager()

        val collector = mgr.shizukuBinder.test(tag = "binder", scope = scope)
        collector.await { values, _ -> values.any { it != null } }

        collector.latestValues.last() shouldBe server
        connectionSubscriptions shouldBe 1

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `binder re-opens when a manager appears after the setting was enabled`() {
        // Enabled with nothing running: a gate evaluated once per setting emission would stay shut here.
        val mgr = manager()

        val collector = mgr.shizukuBinder.test(tag = "binder", scope = scope)
        collector.await { values, _ -> values.isNotEmpty() }
        collector.latestValues.last() shouldBe null

        val server = mockk<AdbServer>()
        connectionFlow.value = server
        collector.await { values, _ -> values.lastOrNull() == server }

        connectionFlow.value = null
        collector.await { values, _ -> values.lastOrNull() == null }

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `binder opens when the setting is enabled later`() {
        useShizukuFlow.value = false
        val server = mockk<AdbServer>()
        connectionFlow.value = server
        val mgr = manager()

        val collector = mgr.shizukuBinder.test(tag = "binder", scope = scope)
        collector.await { values, _ -> values.isNotEmpty() }
        collector.latestValues.last() shouldBe null

        useShizukuFlow.value = true
        collector.await { values, _ -> values.lastOrNull() == server }

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `isInstalled is not cached and re-evaluates each call`() {
        val mgr = manager()

        setAvailability(AdbAvailability.NotInstalled)
        runBlocking { mgr.isInstalled() } shouldBe false

        // A manager gets installed afterwards: the next call must reflect it (no stale cache).
        setAvailability(AdbAvailability.InstalledNotConnected(AdbBackend.PORTER, "eu.darken.porter"))
        runBlocking { mgr.isInstalled() } shouldBe true
    }

    @Test fun `isInstalled counts a running server whose manager is gone`() {
        setAvailability(AdbAvailability.Connected(AdbBackend.SHIZUKU, packageName = null))

        runBlocking { manager().isInstalled() } shouldBe true
    }

    @Test fun `isInstalled is false when availability could not be read`() {
        setAvailability(null)

        runBlocking { manager().isInstalled() } shouldBe false
    }

    @Test fun `getManagerId resolves the package the SDK reports`() {
        val mgr = manager()

        setAvailability(AdbAvailability.NotInstalled)
        runBlocking { mgr.getManagerId() } shouldBe null

        setAvailability(AdbAvailability.InstalledNotConnected(AdbBackend.SHIZUKU, ShizukuManager.PKG_ID.name))
        runBlocking { mgr.getManagerId() } shouldBe ShizukuManager.PKG_ID

        setAvailability(AdbAvailability.Connected(AdbBackend.PORTER, "eu.darken.porter"))
        runBlocking { mgr.getManagerId() } shouldBe "eu.darken.porter".toPkgId()

        // Uninstalled while its server keeps running: no package to name.
        setAvailability(AdbAvailability.Connected(AdbBackend.PORTER, packageName = null))
        runBlocking { mgr.getManagerId() } shouldBe null

        setAvailability(null)
        runBlocking { mgr.getManagerId() } shouldBe null
    }

    @Test fun `getManagerId resolves a fork the SDK does not recognise`() {
        val forkPkg = "com.example.shizuku.fork"
        setAvailability(AdbAvailability.InstalledUnrecognized(AdbBackend.SHIZUKU, forkPkg))

        runBlocking { manager().getManagerId() } shouldBe forkPkg.toPkgId()
    }

    @Test fun `isCompatible is false only for an incompatible server`() {
        val mgr = manager()

        setAvailability(
            AdbAvailability.Incompatible(AdbBackend.SHIZUKU, "moe.shizuku.privileged.api", serverTooOld = true, clientTooOld = false),
        )
        runBlocking { mgr.isCompatible() } shouldBe false

        setAvailability(AdbAvailability.Connected(AdbBackend.SHIZUKU, "moe.shizuku.privileged.api"))
        runBlocking { mgr.isCompatible() } shouldBe true

        // An unreadable availability is no proof of an incompatible server.
        setAvailability(null)
        runBlocking { mgr.isCompatible() } shouldBe true
    }

    @Test fun `isShizukud is false for an incompatible server without probing the service`() {
        setAvailability(
            AdbAvailability.Incompatible(AdbBackend.PORTER, "eu.darken.porter", serverTooOld = false, clientTooOld = true),
        )
        coEvery { shizukuWrapper.isGranted() } returns true
        val mgr = manager()

        runBlocking { mgr.isShizukud() } shouldBe false
        mgr.lastShizukudResult shouldBe false
        coVerify(exactly = 0) { serviceClient.get() }
    }

    @Test fun `a permission state change invalidates the cached isShizukud answer`() {
        setAvailability(AdbAvailability.Connected(AdbBackend.PORTER, "eu.darken.porter"))
        coEvery { shizukuWrapper.isGranted() } returns true
        coEvery { serviceClient.get() } throws AdbUnavailableException("test")
        val mgr = manager()

        val collector = mgr.permissionState.test(tag = "permission", scope = scope)
        collector.await { values, _ -> values.isNotEmpty() }

        runBlocking { mgr.isShizukud() } shouldBe false
        runBlocking { mgr.isShizukud() } shouldBe false
        coVerify(exactly = 1) { serviceClient.get() } // second answer came from the cache

        permissionFlow.value = AdbPermissionState.Granted
        collector.await { values, _ -> values.lastOrNull() == AdbPermissionState.Granted }

        runBlocking { mgr.isShizukud() }
        coVerify(exactly = 2) { serviceClient.get() }

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `isOurServiceAvailable does not launch the service client without permission`() {
        // Enabled-but-absent manager (or permission not yet granted): the availability probe must
        // fail fast without acquiring the service client, which would attempt a host launch and
        // enter SharedResource.
        val mgr = manager()

        // No connection: permission state unknowable.
        coEvery { shizukuWrapper.isGranted() } returns null
        runBlocking { mgr.isOurServiceAvailable() } shouldBe false

        // Connected but permission denied.
        coEvery { shizukuWrapper.isGranted() } returns false
        runBlocking { mgr.isOurServiceAvailable() } shouldBe false

        coVerify(exactly = 0) { serviceClient.get() }
    }

    @Test fun `isOurServiceAvailable is false when the service client fails`() {
        coEvery { shizukuWrapper.isGranted() } returns true
        coEvery { serviceClient.get() } throws AdbUnavailableException("test")
        val mgr = manager()

        runBlocking { mgr.isOurServiceAvailable() } shouldBe false
    }

    @Test fun `getServiceState separates an unreadable grant from a denied one`() {
        val mgr = manager()

        // "Cannot know" - overwhelmingly just the server not started yet. Telling this user their
        // setup failed would be wrong, so it must not be reported as a denial or as terminal.
        coEvery { shizukuWrapper.isGranted() } returns null
        runBlocking { mgr.getServiceState() } shouldBe ShizukuServiceState.Unknown
        ShizukuServiceState.Unknown.isTerminalFailure shouldBe false

        coEvery { shizukuWrapper.isGranted() } returns false
        runBlocking { mgr.getServiceState() } shouldBe ShizukuServiceState.PermissionDenied

        coVerify(exactly = 0) { serviceClient.get() }
    }

    @Test fun `getServiceState reports a spent connect budget as TimedOut`() {
        coEvery { shizukuWrapper.isGranted() } returns true
        // How AdbServiceClient surfaces it: the launcher's timeout wrapped by its .catch.
        coEvery { serviceClient.get() } throws AdbUnavailableException(
            "Failed to establish connection",
            cause = AdbConnectTimeoutException("did not connect"),
        )
        val mgr = manager()

        val state = runBlocking { mgr.getServiceState() }
        state shouldBe ShizukuServiceState.TimedOut
        state.isTerminalFailure shouldBe true
    }

    @Test fun `getServiceState reports any other connection error as Failed`() {
        coEvery { shizukuWrapper.isGranted() } returns true
        coEvery { serviceClient.get() } throws AdbUnavailableException("handshake boom")
        val mgr = manager()

        val state = runBlocking { mgr.getServiceState() }
        state shouldBe ShizukuServiceState.Failed
        state.isTerminalFailure shouldBe true
    }

    @Test fun `managerIds always includes the reference package plus any detected fork`() {
        val mgr = manager()

        // Nothing installed: just the reference package.
        setManagerPackages()
        runBlocking { mgr.managerIds() } shouldBe setOf(ShizukuManager.PKG_ID)

        // Fork installed under a different package: both the reference and the fork are included.
        val forkPkg = "com.example.shizuku.fork"
        setManagerPackages(forkPkg)
        runBlocking { mgr.managerIds() } shouldBe setOf(ShizukuManager.PKG_ID, forkPkg.toPkgId())
    }

    @Test fun `managerIds includes every detected manager package of both families`() {
        setManagerPackages("eu.darken.porter", "moe.shizuku.privileged.api", "af.shizuku.plus.api")

        runBlocking { manager().managerIds() } shouldBe setOf(
            ShizukuManager.PKG_ID,
            "eu.darken.porter".toPkgId(),
            "af.shizuku.plus.api".toPkgId(),
        )
    }
}
