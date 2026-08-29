package eu.darken.butler.common.files.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.files.local.routing.LocalPathRoutingPolicy
import eu.darken.butler.common.files.local.routing.ModeSessionFactory
import eu.darken.butler.common.files.local.service.IsolatedServiceClient
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.service.RootServiceClient
import eu.darken.butler.common.sharedresource.Resource
import eu.darken.butler.common.storage.StorageManager2
import eu.darken.butler.common.storage.StorageVolumeX
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.io.File
import java.io.IOException

/**
 * Mode selection for the strict existence probe. It cannot ride on the shared mode dispatch:
 * that escalates on a thrown IOException, while "could not tell" is a return value here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class LocalGatewayExistsStrictTest : BaseTest() {

    private lateinit var mockFileSystemOps: LocalFileSystemOps
    private lateinit var mockRootManager: RootManager
    private lateinit var mockAdbManager: AdbManager
    private lateinit var mockAccessChecker: LocalPathAccessChecker
    private lateinit var mockIsolatedServiceClient: IsolatedServiceClient
    private lateinit var mockStorageManager: StorageManager2
    private lateinit var mockRoutingPolicy: LocalPathRoutingPolicy
    private lateinit var mockModeSessionFactory: ModeSessionFactory
    private lateinit var mockRootServiceClient: RootServiceClient
    private lateinit var mockRootFileOpsClient: FileOpsClient
    private lateinit var mockIsolatedFileOpsClient: FileOpsClient
    private lateinit var gateway: LocalGateway

    @Before
    fun setup() {
        mockFileSystemOps = mockk()
        mockRootManager = mockk()
        mockAdbManager = mockk()
        mockAccessChecker = mockk(relaxed = true)
        mockIsolatedServiceClient = mockk(relaxed = true)
        mockStorageManager = mockk(relaxed = true)
        mockRoutingPolicy = mockk(relaxed = true)
        mockModeSessionFactory = mockk(relaxed = true)
        mockRootServiceClient = mockk(relaxed = true)
        mockRootFileOpsClient = mockk()
        mockIsolatedFileOpsClient = mockk()

        every { mockRootManager.useRoot } returns flowOf(false)
        every { mockAdbManager.useAdb } returns flowOf(false)
        every { mockRootManager.serviceClient } returns mockRootServiceClient
        every { mockAdbManager.serviceClient } returns mockk(relaxed = true)
        every { mockAccessChecker.shouldTryNormalAccess(any(), any()) } returns true
        every { mockStorageManager.storageVolumes } returns emptyList()

        coEvery { mockRootServiceClient.get() } answers { rootResource() }
        coEvery { mockIsolatedServiceClient.get() } answers { isolatedResource() }

        gateway = LocalGateway(
            appScope = TestScope(),
            dispatcherProvider = TestDispatcherProvider(),
            fileSystemOps = mockFileSystemOps,
            rootManager = mockRootManager,
            adbManager = mockAdbManager,
            accessChecker = mockAccessChecker,
            isolatedServiceClient = mockIsolatedServiceClient,
            storageManager = mockStorageManager,
            routingPolicy = mockRoutingPolicy,
            modeSessionFactory = mockModeSessionFactory,
        )
    }

    private fun rootResource(): Resource<RootServiceClient.Connection> {
        val connection = mockk<RootServiceClient.Connection> {
            every { clientModules } returns listOf(mockRootFileOpsClient)
        }
        return mockk<Resource<RootServiceClient.Connection>> {
            every { item } returns connection
            every { close() } just Runs
        }
    }

    private fun isolatedResource(): Resource<IsolatedServiceClient.Connection> {
        val connection = mockk<IsolatedServiceClient.Connection> {
            every { clientModules } returns listOf(mockIsolatedFileOpsClient)
        }
        return mockk<Resource<IsolatedServiceClient.Connection>> {
            every { item } returns connection
            every { close() } just Runs
        }
    }

    /** Makes [REMOVABLE_PATH] sit on a removable volume. */
    private fun onRemovableStorage() {
        val volume = mockk<StorageVolumeX> {
            every { directory } returns File("/storage/1234-ABCD")
            every { isRemovable } returns true
        }
        every { mockStorageManager.storageVolumes } returns listOf(volume)
    }

    @Test
    fun `AUTO takes a definitive direct answer`() = runTest2 {
        val path = LocalPath.build("/sdcard/gone.txt")
        coEvery { mockFileSystemOps.existsStrict(path) } returns Existence.ABSENT

        gateway.existsStrict(path) shouldBe Existence.ABSENT

        coVerify(exactly = 1) { mockFileSystemOps.existsStrict(path) }
        coVerify(exactly = 0) { mockRootFileOpsClient.existsStrict(any()) }
    }

    @Test
    fun `AUTO escalates when the direct probe cannot tell`() = runTest2 {
        val path = LocalPath.build("/data/data/com.example")
        coEvery { mockFileSystemOps.existsStrict(path) } returns Existence.UNKNOWN
        every { mockRootManager.useRoot } returns flowOf(true)
        coEvery { mockRootFileOpsClient.existsStrict(path) } returns Existence.PRESENT

        gateway.existsStrict(path) shouldBe Existence.PRESENT

        coVerify(exactly = 1) { mockFileSystemOps.existsStrict(path) }
        coVerify(exactly = 1) { mockRootFileOpsClient.existsStrict(path) }
    }

    @Test
    fun `AUTO escalates when the direct probe throws`() = runTest2 {
        val path = LocalPath.build("/data/data/com.example")
        coEvery { mockFileSystemOps.existsStrict(path) } throws IOException("Permission denied")
        every { mockRootManager.useRoot } returns flowOf(true)
        coEvery { mockRootFileOpsClient.existsStrict(path) } returns Existence.ABSENT

        gateway.existsStrict(path) shouldBe Existence.ABSENT
    }

    @Test
    fun `AUTO skips the direct probe where normal access is pointless`() = runTest2 {
        val path = LocalPath.build("/data/data/com.example")
        every { mockAccessChecker.shouldTryNormalAccess(path, false) } returns false
        every { mockRootManager.useRoot } returns flowOf(true)
        coEvery { mockRootFileOpsClient.existsStrict(path) } returns Existence.ABSENT

        gateway.existsStrict(path) shouldBe Existence.ABSENT

        coVerify(exactly = 0) { mockFileSystemOps.existsStrict(any()) }
    }

    /** Nothing left to ask is not an absence, and not a permission error either. */
    @Test
    fun `AUTO without an escalation mechanism cannot tell`() = runTest2 {
        val path = LocalPath.build("/data/data/com.example")
        coEvery { mockFileSystemOps.existsStrict(path) } returns Existence.UNKNOWN

        gateway.existsStrict(path) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `AUTO cannot tell when the escalated service is unreachable`() = runTest2 {
        val path = LocalPath.build("/data/data/com.example")
        coEvery { mockFileSystemOps.existsStrict(path) } returns Existence.UNKNOWN
        every { mockRootManager.useRoot } returns flowOf(true)
        coEvery { mockRootServiceClient.get() } throws IOException("host died")

        gateway.existsStrict(path) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `an escalated answer that cannot tell stays unknown`() = runTest2 {
        val path = LocalPath.build("/data/data/com.example")
        coEvery { mockFileSystemOps.existsStrict(path) } returns Existence.UNKNOWN
        every { mockRootManager.useRoot } returns flowOf(true)
        coEvery { mockRootFileOpsClient.existsStrict(path) } returns Existence.UNKNOWN

        gateway.existsStrict(path) shouldBe Existence.UNKNOWN
    }

    @Test
    fun `an explicit mode that is unavailable cannot tell`() = runTest2 {
        val path = LocalPath.build("/data/data/com.example")

        gateway.existsStrict(path, LocalGateway.Mode.ROOT) shouldBe Existence.UNKNOWN

        coVerify(exactly = 0) { mockFileSystemOps.existsStrict(any()) }
    }

    @Test
    fun `an explicit mode answers from that mode alone`() = runTest2 {
        val path = LocalPath.build("/data/data/com.example")
        coEvery { mockFileSystemOps.existsStrict(path) } returns Existence.PRESENT

        gateway.existsStrict(path, LocalGateway.Mode.DIRECT) shouldBe Existence.PRESENT

        coVerify(exactly = 0) { mockRootFileOpsClient.existsStrict(any()) }
    }

    /** Crash isolation against a card being pulled mid-probe, as for every other AUTO operation. */
    @Test
    fun `removable storage is probed through the isolated service`() = runTest2 {
        val path = REMOVABLE_PATH
        onRemovableStorage()
        coEvery { mockIsolatedFileOpsClient.existsStrict(path) } returns Existence.ABSENT

        gateway.existsStrict(path) shouldBe Existence.ABSENT

        coVerify(exactly = 0) { mockFileSystemOps.existsStrict(any()) }
    }

    @Test
    fun `removable storage falls back to direct when the isolated service does not bind`() = runTest2 {
        val path = REMOVABLE_PATH
        onRemovableStorage()
        coEvery { mockIsolatedServiceClient.get() } throws
            IsolatedServiceClient.ServiceBindException("no service")
        coEvery { mockFileSystemOps.existsStrict(path) } returns Existence.PRESENT

        gateway.existsStrict(path) shouldBe Existence.PRESENT
    }

    @Test
    fun `removable storage cannot tell when the isolated probe fails otherwise`() = runTest2 {
        val path = REMOVABLE_PATH
        onRemovableStorage()
        coEvery { mockIsolatedFileOpsClient.existsStrict(path) } throws IOException("transport gone")

        gateway.existsStrict(path) shouldBe Existence.UNKNOWN
    }

    companion object {
        private val REMOVABLE_PATH = LocalPath.build("/storage/1234-ABCD/DCIM")
    }
}
