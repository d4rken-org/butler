package eu.darken.butler.common.files.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.errors.PathPermissionDeniedException
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.files.local.routing.AccessMode
import eu.darken.butler.common.files.local.routing.LocalPathRoutingPolicy
import eu.darken.butler.common.files.local.routing.ModeSession
import eu.darken.butler.common.files.local.routing.ModeSessionFactory
import eu.darken.butler.common.files.local.routing.RouteDecision
import eu.darken.butler.common.files.local.service.IsolatedServiceClient
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.root.RootManager
import eu.darken.butler.common.root.service.RootServiceClient
import eu.darken.butler.common.sharedresource.Resource
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.storage.StorageManager2
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.io.IOException

/**
 * Tests for LocalGateway privilege escalation refactoring.
 *
 * These tests focus on verifying the mode selection logic:
 * - AUTO mode tries normal first (critical for file ownership)
 * - AUTO mode escalates to root/ADB on IOException
 * - Explicit modes (NORMAL, ROOT, ADB) work correctly
 *
 * Limitations:
 * - Cannot test actual root/ADB IPC without real device
 * - Cannot test real file ownership without filesystem
 * - Focus is on testing the logic, not the implementation
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class LocalGatewayTest : BaseTest() {

    private lateinit var mockFileSystemOps: LocalFileSystemOps
    private lateinit var mockStorageEnvironment: StorageEnvironment
    private lateinit var mockRootManager: RootManager
    private lateinit var mockAdbManager: AdbManager
    private lateinit var mockAccessibilityChecker: LocalPathAccessChecker
    private lateinit var mockIsolatedServiceClient: IsolatedServiceClient
    private lateinit var mockStorageManager: StorageManager2
    private lateinit var mockRoutingPolicy: LocalPathRoutingPolicy
    private lateinit var mockModeSessionFactory: ModeSessionFactory
    private lateinit var dispatcherProvider: TestDispatcherProvider
    private lateinit var testScope: TestScope
    private lateinit var gateway: LocalGateway

    @Before
    fun setup() {
        mockFileSystemOps = mockk()
        mockStorageEnvironment = mockk(relaxed = true)
        mockRootManager = mockk()
        mockAdbManager = mockk()
        mockAccessibilityChecker = mockk(relaxed = true)
        mockIsolatedServiceClient = mockk(relaxed = true)
        mockStorageManager = mockk(relaxed = true)
        mockRoutingPolicy = mockk(relaxed = true)
        mockModeSessionFactory = mockk(relaxed = true)
        dispatcherProvider = TestDispatcherProvider()
        testScope = TestScope()

        // Setup default behaviors: no root/ADB available
        every { mockRootManager.useRoot } returns flowOf(false)
        every { mockAdbManager.useAdb } returns flowOf(false)
        every { mockRootManager.serviceClient } returns mockk(relaxed = true)
        every { mockAdbManager.serviceClient } returns mockk(relaxed = true)
        // Default: paths should try normal access (checker returns true for "should try normal access")
        every { mockAccessibilityChecker.shouldTryNormalAccess(any(), any()) } returns true

        gateway = LocalGateway(
            appScope = testScope,
            dispatcherProvider = dispatcherProvider,
            fileSystemOps = mockFileSystemOps,
            rootManager = mockRootManager,
            adbManager = mockAdbManager,
            accessChecker = mockAccessibilityChecker,
            isolatedServiceClient = mockIsolatedServiceClient,
            storageManager = mockStorageManager,
            routingPolicy = mockRoutingPolicy,
            modeSessionFactory = mockModeSessionFactory,
        )
    }

    /** Keeps the routed copy/move path in-process, so the mocked ops answer for every path. */
    private fun routeEverythingDirect() {
        coEvery { mockRoutingPolicy.classify(any(), any(), any()) } returns RouteDecision.Allowed(AccessMode.DIRECT)
        every { mockRoutingPolicy.proactiveChildren(any()) } returns emptySet()
        coEvery { mockModeSessionFactory.open(AccessMode.DIRECT) } returns ModeSession(
            mode = AccessMode.DIRECT,
            ops = mockFileSystemOps,
            batch = null,
            lease = null,
        )
    }

    private fun directoryLookup(path: LocalPath) = LocalPathLookup(
        lookedUp = path,
        fileType = FileType.DIRECTORY,
        size = null,
        modifiedAt = null,
    )

    // ========================================================================
    // AUTO Mode - Normal First Tests
    // ========================================================================

    @Test
    fun `createFile AUTO mode tries normal first for user paths`() = runTest2 {
        val path = LocalPath.build("/sdcard/test.txt")

        // Mock normal mode success
        coEvery { mockFileSystemOps.createFile(path) } just Runs

        // Execute
        gateway.createFile(path, mode = LocalGateway.Mode.AUTO)

        // Verify normal was called
        coVerify(exactly = 1) { mockFileSystemOps.createFile(path) }
    }

    @Test
    fun `createDir AUTO mode tries normal first for user paths`() = runTest2 {
        val path = LocalPath.build("/sdcard/newdir")

        // Mock normal mode success
        coEvery { mockFileSystemOps.createDir(path) } just Runs

        // Execute
        gateway.createDir(path, mode = LocalGateway.Mode.AUTO)

        // Verify normal was called
        coVerify(exactly = 1) { mockFileSystemOps.createDir(path) }
    }

    @Test
    fun `lookup AUTO mode tries normal first for user paths`() = runTest2 {
        val path = LocalPath.build("/sdcard/test.txt")
        val mockLookup = mockk<LocalPathLookup>()

        // Mock normal mode success
        coEvery { mockFileSystemOps.lookup(path, any()) } returns mockLookup

        // Execute
        val result = gateway.lookup(path, LookupOptions.BASE, mode = LocalGateway.Mode.AUTO)

        // Verify
        result shouldBe mockLookup
        coVerify(exactly = 1) { mockFileSystemOps.lookup(path, any()) }
    }

    @Test
    fun `listFiles AUTO mode tries normal first for user paths`() = runTest2 {
        val path = LocalPath.build("/sdcard")
        val mockFiles = listOf(
            LocalPath.build("/sdcard/file1.txt"),
            LocalPath.build("/sdcard/file2.txt")
        )

        // Mock normal mode success
        coEvery { mockFileSystemOps.listFiles(path) } returns mockFiles

        // Execute
        val result = gateway.listFiles(path, mode = LocalGateway.Mode.AUTO)

        // Verify
        result shouldBe mockFiles
        coVerify(exactly = 1) { mockFileSystemOps.listFiles(path) }
    }

    @Test
    fun `du AUTO mode tries normal first for user paths`() = runTest2 {
        val path = LocalPath.build("/sdcard/dir")

        // Mock normal mode success
        coEvery { mockFileSystemOps.du(path) } returns 1024L

        // Execute
        val result = gateway.du(path, mode = LocalGateway.Mode.AUTO)

        // Verify
        result shouldBe 1024L
        coVerify(exactly = 1) { mockFileSystemOps.du(path) }
    }

    // TODO: Fix this test - currently fails with "No matching mode available"
    // @Test
    // fun `createSymlink AUTO mode tries normal first`() = runTest2 {
    //     val linkPath = LocalPath.build("/sdcard/symlink")
    //     val targetPath = LocalPath.build("/sdcard/target")
    //
    //     // Mock normal success
    //     coEvery { mockFileSystemOps.createSymlink(linkPath, targetPath) } returns true
    //
    //     // Execute
    //     val result = gateway.createSymlink(linkPath, targetPath, mode = LocalGateway.Mode.AUTO)
    //
    //     // Verify
    //     result shouldBe true
    //     coVerify(exactly = 1) { mockFileSystemOps.createSymlink(linkPath, targetPath) }
    // }

    // ========================================================================
    // Explicit Mode Tests
    // ========================================================================

    @Test
    fun `NORMAL mode uses only normal filesystem ops`() = runTest2 {
        val path = LocalPath.build("/sdcard/test.txt")

        coEvery { mockFileSystemOps.createFile(path) } just Runs

        gateway.createFile(path, mode = LocalGateway.Mode.DIRECT)

        coVerify(exactly = 1) { mockFileSystemOps.createFile(path) }
    }

    @Test
    fun `NORMAL mode propagates IOException when normal fails`() = runTest2 {
        val path = LocalPath.build("/system/test.txt")

        // Mock normal mode failure
        coEvery { mockFileSystemOps.createFile(path) } throws IOException("Permission denied")

        // Should throw a typed permission error wrapping the original IOException
        var exceptionThrown = false
        try {
            gateway.createFile(path, mode = LocalGateway.Mode.DIRECT)
        } catch (e: PathPermissionDeniedException) {
            exceptionThrown = true
            e.reason shouldBe PathPermissionDeniedException.Reason.ACCESS_DENIED
            (e.cause as? IOException)?.message shouldBe "Permission denied"
        }

        exceptionThrown shouldBe true
    }

    // ========================================================================
    // Function-Specific Tests
    // ========================================================================

    @Test
    fun `createSymlink no longer checks canWrite on non-existent path - bug fix verification`() = runTest2 {
        val linkPath = LocalPath.build("/sdcard/symlink")
        val targetPath = LocalPath.build("/sdcard/target")

        // Mock normal success
        coEvery { mockFileSystemOps.createSymlink(linkPath, targetPath) } returns true

        val result = gateway.createSymlink(linkPath, targetPath, mode = LocalGateway.Mode.DIRECT)

        result shouldBe true
        coVerify(exactly = 1) { mockFileSystemOps.createSymlink(linkPath, targetPath) }
        // Verify canWrite was NOT called (this was the bug - checking non-existent path)
        coVerify(exactly = 0) { mockFileSystemOps.canWrite(any()) }
    }

    @Test
    fun `openOutputStream with append false works with normal mode`() = runTest2 {
        val path = LocalPath.build("/sdcard/test.txt")
        val mockOutputStream = mockk<java.io.OutputStream>(relaxed = true)

        coEvery { mockFileSystemOps.openOutputStream(path, false) } returns mockOutputStream

        val result = gateway.openOutputStream(path, append = false, mode = LocalGateway.Mode.DIRECT)

        result shouldBe mockOutputStream
        coVerify(exactly = 1) { mockFileSystemOps.openOutputStream(path, false) }
    }

    // ========================================================================
    // Error Handling Tests
    // ========================================================================

    @Test
    fun `AUTO mode with no escalation options surfaces typed permission error`() = runTest2 {
        val path = LocalPath.build("/sdcard/test.txt")

        // Mock all methods unavailable/failing
        coEvery { mockFileSystemOps.createFile(path) } throws IOException("Permission denied")
        every { mockRootManager.useRoot } returns flowOf(false)
        every { mockAdbManager.useAdb } returns flowOf(false)

        // The original IOException is wrapped in PathPermissionDeniedException with the cause preserved
        var exceptionThrown = false
        try {
            gateway.createFile(path, mode = LocalGateway.Mode.AUTO)
        } catch (e: PathPermissionDeniedException) {
            exceptionThrown = true
            e.reason shouldBe PathPermissionDeniedException.Reason.ACCESS_DENIED
            (e.cause as? IOException)?.message shouldBe "Permission denied"
        }

        exceptionThrown shouldBe true
        // Verify normal was tried
        coVerify(exactly = 1) { mockFileSystemOps.createFile(path) }
    }

    @Test
    fun `AUTO mode surfaces a gone source as gone, not as a denial`() = runTest2 {
        val path = LocalPath.build("/sdcard/vanished.txt")

        coEvery { mockFileSystemOps.lookup(path, any()) } throws PathNotFoundException(path)

        shouldThrow<PathNotFoundException> {
            gateway.lookup(path, LookupOptions.BASE, mode = LocalGateway.Mode.AUTO)
        }
    }

    @Test
    fun `AUTO mode escalates a gone-from-direct path that root can read`() = runTest2 {
        val path = LocalPath.build("/proc/4242/status")

        every { mockAccessibilityChecker.shouldTryNormalAccess(path, any()) } returns true
        coEvery { mockFileSystemOps.lookup(path, any()) } throws PathNotFoundException(path)
        every { mockRootManager.useRoot } returns flowOf(true)

        val rootFileOpsClient = mockk<FileOpsClient>()
        coEvery { rootFileOpsClient.lookup(path, any()) } returns LocalPathLookup(
            lookedUp = path,
            fileType = FileType.FILE,
            size = null,
            modifiedAt = null,
        )
        val rootConnection = mockk<RootServiceClient.Connection> {
            every { clientModules } returns listOf(rootFileOpsClient)
        }
        val rootServiceClient = mockk<RootServiceClient>(relaxed = true)
        coEvery { rootServiceClient.get() } answers {
            mockk<Resource<RootServiceClient.Connection>> {
                every { item } returns rootConnection
                every { close() } just Runs
            }
        }
        every { mockRootManager.serviceClient } returns rootServiceClient

        val result = gateway.lookup(path, LookupOptions.BASE, mode = LocalGateway.Mode.AUTO)

        result.fileType shouldBe FileType.FILE
        coVerify(exactly = 1) { mockFileSystemOps.lookup(path, any()) }
        coVerify(exactly = 1) { rootFileOpsClient.lookup(path, any()) }
    }

    @Test
    fun `AUTO copy surfaces a gone source as gone, not as a denial`() = runTest2 {
        val source = LocalPath.build("/sdcard/vanished.txt")
        val destination = LocalPath.build("/sdcard/dest")
        routeEverythingDirect()

        coEvery { mockFileSystemOps.lookup(destination, any()) } returns directoryLookup(destination)
        coEvery { mockFileSystemOps.lookup(source, any()) } throws PathNotFoundException(source)

        shouldThrow<PathNotFoundException> {
            gateway.copy(setOf(source), destination, onIssue = null, options = CopyAction.Options()).last()
        }
    }

    @Test
    fun `AUTO move surfaces a gone source as gone, not as a denial`() = runTest2 {
        val source = LocalPath.build("/sdcard/vanished.txt")
        val destination = LocalPath.build("/sdcard/dest")
        routeEverythingDirect()

        coEvery { mockFileSystemOps.lookup(destination, any()) } returns directoryLookup(destination)
        coEvery { mockFileSystemOps.lookup(source, any()) } throws PathNotFoundException(source)

        shouldThrow<PathNotFoundException> {
            gateway.move(setOf(source), destination, onIssue = null, options = MoveAction.Options()).last()
        }
    }

    @Test
    fun `AUTO mode with restricted path and no escalation throws NO_MECHANISM`() = runTest2 {
        val path = LocalPath.build("/data/data/com.example/file.txt")

        every { mockAccessibilityChecker.shouldTryNormalAccess(path, forWriting = true) } returns false
        every { mockRootManager.useRoot } returns flowOf(false)
        every { mockAdbManager.useAdb } returns flowOf(false)

        var exceptionThrown = false
        try {
            gateway.createFile(path, mode = LocalGateway.Mode.AUTO)
        } catch (e: PathPermissionDeniedException) {
            exceptionThrown = true
            e.reason shouldBe PathPermissionDeniedException.Reason.NO_MECHANISM
            e.operation shouldBe "createFile"
        }

        exceptionThrown shouldBe true
        // Direct was NOT attempted
        coVerify(exactly = 0) { mockFileSystemOps.createFile(any()) }
    }

    @Test
    fun `AUTO mode wraps EROFS from root in READONLY_FILESYSTEM exception`() = runTest2 {
        val path = LocalPath.build("/system/test.txt")

        every { mockAccessibilityChecker.shouldTryNormalAccess(path, forWriting = true) } returns false
        every { mockRootManager.useRoot } returns flowOf(true)
        // The root client surface (via runModuleAction) eventually throws — simulate by having the
        // resource get fail with the EROFS chain that LocalFileSystemOps would produce.
        val rootClient = mockRootManager.serviceClient
        coEvery { rootClient.get() } throws java.io.IOException("Read-only file system")

        var exceptionThrown = false
        try {
            gateway.createFile(path, mode = LocalGateway.Mode.AUTO)
        } catch (e: PathPermissionDeniedException) {
            exceptionThrown = true
            e.reason shouldBe PathPermissionDeniedException.Reason.READONLY_FILESYSTEM
        }

        exceptionThrown shouldBe true
    }

    // ========================================================================
    // Optimization Tests (Behavior Verification)
    // ========================================================================

    @Test
    fun `AUTO mode with restricted path skips normal when root available - optimization`() = runTest2 {
        val path = LocalPath.build("/system/test.txt")

        // Enable root
        every { mockRootManager.useRoot } returns flowOf(true)
        // Mock checker to say system paths should not try normal access
        every { mockAccessibilityChecker.shouldTryNormalAccess(path, forWriting = true) } returns false

        // Note: We can't fully test the escalation without complex mocking,
        // but we can verify that the normal path is skipped by checking
        // that normal operations are NOT called

        try {
            gateway.createFile(path, mode = LocalGateway.Mode.AUTO)
        } catch (e: Exception) {
            // Expected to fail since we haven't mocked root operations
            // The key thing is to verify normal was NOT called
        }

        // Verify normal was SKIPPED (optimization for system paths)
        coVerify(exactly = 0) { mockFileSystemOps.createFile(any()) }
    }

    @Test
    fun `AUTO mode with restricted path tries normal when root NOT available - no optimization`() = runTest2 {
        val path = LocalPath.build("/system/test.txt")

        // No root available
        every { mockRootManager.useRoot } returns flowOf(false)
        every { mockAdbManager.useAdb } returns flowOf(false)

        // Mock normal to fail
        coEvery { mockFileSystemOps.createFile(path) } throws IOException("Permission denied")

        try {
            gateway.createFile(path, mode = LocalGateway.Mode.AUTO)
        } catch (e: IOException) {
            // Expected
        }

        // Verify normal WAS tried (no optimization since no root available)
        coVerify(exactly = 1) { mockFileSystemOps.createFile(path) }
    }
}
