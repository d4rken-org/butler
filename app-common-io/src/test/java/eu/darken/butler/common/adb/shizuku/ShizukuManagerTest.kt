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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
    private lateinit var scope: CoroutineScope

    private var binderSubscriptions = 0

    @BeforeEach
    fun setup() {
        binderSubscriptions = 0
        useShizukuFlow = MutableStateFlow(true)
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        every { settings.useShizuku } returns useShizukuValue
        every { useShizukuValue.flow } returns useShizukuFlow

        every { shizukuWrapper.permissionGrantEvents } returns emptyFlow()

        // Track whether the underlying Shizuku binder flow is ever collected.
        every { shizukuWrapper.baseServiceBinder } returns flow {
            binderSubscriptions++
            emit(mockk<ShizukuBaseServiceBinder>())
        }
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

    private fun setShizukuPackage(pkg: String?) {
        coEvery { shizukuWrapper.getManagerPackage() } returns pkg
    }

    @Test fun `binder is not probed when Shizuku is not installed`() {
        setShizukuPackage(null)
        val mgr = manager()

        val collector = mgr.shizukuBinder.test(tag = "binder", scope = scope)
        collector.await { values, _ -> values.isNotEmpty() }

        collector.latestValues.last() shouldBe null
        binderSubscriptions shouldBe 0

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `binder is probed when Shizuku is installed`() {
        setShizukuPackage(ShizukuManager.PKG_ID.name)
        val mgr = manager()

        val collector = mgr.shizukuBinder.test(tag = "binder", scope = scope)
        collector.await { values, _ -> values.any { it != null } }

        binderSubscriptions shouldBe 1

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `binder stays closed when user opted out even if installed`() {
        setShizukuPackage(ShizukuManager.PKG_ID.name)
        useShizukuFlow.value = false
        val mgr = manager()

        val collector = mgr.shizukuBinder.test(tag = "binder", scope = scope)
        collector.await { values, _ -> values.isNotEmpty() }

        collector.latestValues.last() shouldBe null
        binderSubscriptions shouldBe 0

        runBlocking { collector.cancelAndJoin() }
    }

    @Test fun `isInstalled is not cached and re-evaluates each call`() {
        val mgr = manager()

        setShizukuPackage(null)
        runBlocking { mgr.isInstalled() } shouldBe false

        // Shizuku gets installed afterwards: the next call must reflect it (no stale cache).
        setShizukuPackage(ShizukuManager.PKG_ID.name)
        runBlocking { mgr.isInstalled() } shouldBe true
    }

    @Test fun `getManagerId resolves the detected package`() {
        val mgr = manager()

        setShizukuPackage(null)
        runBlocking { mgr.getManagerId() } shouldBe null

        setShizukuPackage(ShizukuManager.PKG_ID.name)
        runBlocking { mgr.getManagerId() } shouldBe ShizukuManager.PKG_ID
    }

    @Test fun `getManagerId resolves a fork under a different package name`() {
        val forkPkg = "com.example.shizuku.fork"
        setShizukuPackage(forkPkg)
        val mgr = manager()

        runBlocking { mgr.getManagerId() } shouldBe forkPkg.toPkgId()
    }

    @Test fun `isOurServiceAvailable does not launch the service client without permission`() {
        // Enabled-but-absent Shizuku (or permission not yet granted): the availability probe must
        // fail fast without acquiring the service client, which would attempt a host launch and
        // enter SharedResource.
        val mgr = manager()

        // Shizuku absent: no binder, permission state unknowable.
        coEvery { shizukuWrapper.isGranted() } returns null
        runBlocking { mgr.isOurServiceAvailable() } shouldBe false

        // Shizuku present but permission denied.
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

        // "Cannot know" - overwhelmingly just Shizuku not started yet. Telling this user their setup
        // failed would be wrong, so it must not be reported as a denial or as terminal.
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
        setShizukuPackage(null)
        runBlocking { mgr.managerIds() } shouldBe setOf(ShizukuManager.PKG_ID)

        // Fork installed under a different package: both the reference and the fork are included.
        val forkPkg = "com.example.shizuku.fork"
        setShizukuPackage(forkPkg)
        runBlocking { mgr.managerIds() } shouldBe setOf(ShizukuManager.PKG_ID, forkPkg.toPkgId())
    }
}
