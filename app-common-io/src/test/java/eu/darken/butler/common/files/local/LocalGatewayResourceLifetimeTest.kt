package eu.darken.butler.common.files.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.adb.AdbManager
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.actions.DeleteAction
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
import eu.darken.butler.common.sharedresource.KeepAlive
import eu.darken.butler.common.sharedresource.Resource
import eu.darken.butler.common.storage.StorageManager2
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import okio.FileHandle
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import java.io.IOException
import kotlin.time.Instant

/**
 * Tests for resource lifetimes and escalation error priority in LocalGateway.
 *
 * Locks down the semantics of the mode-dispatch refactoring:
 * - IPC service resources for walk()/file() are released on completion, cancellation,
 *   and mid-acquisition failure (leak fix).
 * - Explicit walk(ROOT/ADB) does not pre-check root/ADB availability (historic behavior).
 * - AUTO escalation never converts an original error into NO_MECHANISM when no
 *   escalation mechanism is available.
 * - walk(AUTO) collection-time errors do not trigger escalation (cold flow semantics).
 * - Routed AUTO operations close their mode sessions even when collection fails.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class LocalGatewayResourceLifetimeTest : BaseTest() {

    private lateinit var mockFileSystemOps: LocalFileSystemOps
    private lateinit var mockRootManager: RootManager
    private lateinit var mockAdbManager: AdbManager
    private lateinit var mockAccessChecker: LocalPathAccessChecker
    private lateinit var mockIsolatedServiceClient: IsolatedServiceClient
    private lateinit var mockStorageManager: StorageManager2
    private lateinit var mockRoutingPolicy: LocalPathRoutingPolicy
    private lateinit var mockModeSessionFactory: ModeSessionFactory
    private lateinit var mockRootServiceClient: RootServiceClient
    private lateinit var mockFileOpsClient: FileOpsClient
    private lateinit var dispatcherProvider: TestDispatcherProvider
    private lateinit var testScope: TestScope
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
        mockFileOpsClient = mockk()
        dispatcherProvider = TestDispatcherProvider()
        testScope = TestScope()

        every { mockRootManager.useRoot } returns flowOf(false)
        every { mockAdbManager.useAdb } returns flowOf(false)
        every { mockRootManager.serviceClient } returns mockRootServiceClient
        every { mockAdbManager.serviceClient } returns mockk(relaxed = true)
        every { mockAccessChecker.shouldTryNormalAccess(any(), any()) } returns true

        gateway = LocalGateway(
            appScope = testScope,
            dispatcherProvider = dispatcherProvider,
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

    private fun mockRootResource(): Resource<RootServiceClient.Connection> {
        val connection = mockk<RootServiceClient.Connection> {
            every { clientModules } returns listOf(mockFileOpsClient)
        }
        return mockk<Resource<RootServiceClient.Connection>> {
            every { item } returns connection
            every { close() } just Runs
        }
    }

    private fun lookup(path: LocalPath, type: FileType = FileType.FILE): LocalPathLookup = LocalPathLookup(
        lookedUp = path,
        fileType = type,
        size = 0L,
        modifiedAt = Instant.fromEpochMilliseconds(0),
    )

    // ========================================================================
    // walk() — IPC resource lifetime
    // ========================================================================

    // walkViaIpc acquires the held resource first, runModuleAction's session resource second

    @Test
    fun `walk ROOT direct closes held resource only after flow completes`() = runTest2 {
        val path = LocalPath.build("/data/dir")
        val heldResource = mockRootResource()
        val sessionResource = mockRootResource()
        coEvery { mockRootServiceClient.get() } returnsMany listOf(heldResource, sessionResource)
        coEvery { mockFileOpsClient.walk(path, any(), any()) } returns flowOf(lookup(path.child("file")))

        // Note: useRoot is false — explicit walk(ROOT) intentionally skips the availability pre-check
        val walkFlow = gateway.walk(
            path = path,
            lookupOptions = LookupOptions.BASE,
            walkOptions = APathGateway.WalkOptions(),
            mode = LocalGateway.Mode.ROOT,
        )

        // Held resource must survive flow construction
        verify(exactly = 0) { heldResource.close() }

        walkFlow.toList().size shouldBe 1

        verify(exactly = 1) { heldResource.close() }
    }

    @Test
    fun `walk ROOT direct closes held resource when flow is cancelled`() = runTest2 {
        val path = LocalPath.build("/data/dir")
        val heldResource = mockRootResource()
        val sessionResource = mockRootResource()
        coEvery { mockRootServiceClient.get() } returnsMany listOf(heldResource, sessionResource)
        coEvery { mockFileOpsClient.walk(path, any(), any()) } returns flow {
            while (true) emit(lookup(path.child("file")))
        }

        gateway.walk(
            path = path,
            lookupOptions = LookupOptions.BASE,
            walkOptions = APathGateway.WalkOptions(),
            mode = LocalGateway.Mode.ROOT,
        ).first()

        verify(exactly = 1) { heldResource.close() }
    }

    @Test
    fun `walk ROOT direct closes held resource if IPC walk setup fails`() = runTest2 {
        val path = LocalPath.build("/data/dir")
        val heldResource = mockRootResource()
        val sessionResource = mockRootResource()
        coEvery { mockRootServiceClient.get() } returnsMany listOf(heldResource, sessionResource)
        coEvery { mockFileOpsClient.walk(path, any(), any()) } throws IOException("boom")

        shouldThrow<IOException> {
            gateway.walk(
                path = path,
                lookupOptions = LookupOptions.BASE,
                walkOptions = APathGateway.WalkOptions(),
                mode = LocalGateway.Mode.ROOT,
            )
        }

        verify(exactly = 1) { heldResource.close() }
    }

    @Test
    fun `walk AUTO collection-time IOException does not escalate`() = runTest2 {
        val path = LocalPath.build("/sdcard/dir")
        every { mockRootManager.useRoot } returns flowOf(true)
        coEvery { mockFileSystemOps.lookup(path, any()) } throws IOException("boom")

        val walkFlow = gateway.walk(
            path = path,
            lookupOptions = LookupOptions.BASE,
            walkOptions = APathGateway.WalkOptions(),
            mode = LocalGateway.Mode.AUTO,
        )

        shouldThrow<IOException> { walkFlow.collect() }

        // Escalation only applies at flow construction time, never during collection
        coVerify(exactly = 0) { mockRootServiceClient.get() }
    }

    // ========================================================================
    // file() — IPC resource lifetime
    // ========================================================================

    // fileViaIpc get() order: keepResourcesAlive (1st), runModuleAction session (2nd), held resource (3rd)

    @Test
    fun `file ROOT closes held resource only when FileHandle is closed`() = runTest2 {
        val path = LocalPath.build("/data/file")
        every { mockRootManager.useRoot } returns flowOf(true)
        val keepAliveResource = mockRootResource()
        val sessionResource = mockRootResource()
        val heldResource = mockRootResource()
        coEvery { mockRootServiceClient.get() } returnsMany listOf(keepAliveResource, sessionResource, heldResource)
        coEvery { mockFileOpsClient.file(path, true) } returns mockk<FileHandle>(relaxed = true)

        val handle = gateway.file(path, readWrite = true, mode = LocalGateway.Mode.ROOT)

        // Held resource must survive until the caller closes the handle
        verify(exactly = 0) { heldResource.close() }

        handle.close()

        verify(exactly = 1) { heldResource.close() }
    }

    @Test
    fun `file ROOT closes held resource if file open fails after acquisition`() = runTest2 {
        val path = LocalPath.build("/data/file")
        every { mockRootManager.useRoot } returns flowOf(true)
        val keepAliveResource = mockRootResource()
        val sessionResource = mockRootResource()
        val heldResource = mockRootResource()
        coEvery { mockRootServiceClient.get() } returnsMany listOf(keepAliveResource, sessionResource, heldResource)
        coEvery { mockFileOpsClient.file(path, true) } throws IOException("boom")

        shouldThrow<IOException> {
            gateway.file(path, readWrite = true, mode = LocalGateway.Mode.ROOT)
        }

        verify(exactly = 1) { heldResource.close() }
    }

    // ========================================================================
    // create() — escalation error priority
    // ========================================================================

    @Test
    fun `create AUTO surfaces original error when escalation is unavailable`() = runTest2 {
        val target = LocalPath.build("/sdcard/newfile")
        // Target doesn't exist yet
        coEvery { mockFileSystemOps.lookup(target, any()) } returns lookup(target, FileType.UNKNOWN)
        coEvery { mockFileSystemOps.createFile(target) } throws SecurityException("Permission denied")

        val thrown = shouldThrow<PathPermissionDeniedException> {
            gateway.create(
                target = target,
                type = CreateAction.CreateType.FILE,
                options = CreateAction.Options(),
                mode = LocalGateway.Mode.AUTO,
            ).collect()
        }

        // The original permission error must surface, never NO_MECHANISM
        thrown.reason shouldBe PathPermissionDeniedException.Reason.ACCESS_DENIED
        (thrown.cause is SecurityException) shouldBe true
    }

    // ========================================================================
    // delete() AUTO — routed mode session lifetime
    // ========================================================================

    @Test
    fun `delete AUTO closes mode sessions when collection fails`() = runTest2 {
        val target = LocalPath.build("/sdcard/doomed")
        val lease = RecordingKeepAlive()
        val sessionOps = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true)
        coEvery { sessionOps.lookup(any(), any()) } throws IOException("boom")
        coEvery { mockRoutingPolicy.classify(any(), any(), any()) } returns RouteDecision.Allowed(AccessMode.DIRECT)
        coEvery { mockModeSessionFactory.open(AccessMode.DIRECT) } returns ModeSession(
            mode = AccessMode.DIRECT,
            ops = sessionOps,
            batch = null,
            lease = lease,
        )

        shouldThrow<Exception> {
            gateway.delete(
                targets = setOf(target),
                options = DeleteAction.Options(),
                mode = LocalGateway.Mode.AUTO,
            ).collect()
        }

        lease.isClosed shouldBe true
    }

    @Test
    fun `delete AUTO closes mode sessions after successful completion`() = runTest2 {
        val target = LocalPath.build("/sdcard/doomed")
        val lease = RecordingKeepAlive()
        val sessionOps = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true)
        coEvery { sessionOps.lookup(any(), any()) } answers { lookup(firstArg()) }
        coEvery { mockRoutingPolicy.classify(any(), any(), any()) } returns RouteDecision.Allowed(AccessMode.DIRECT)
        coEvery { mockModeSessionFactory.open(AccessMode.DIRECT) } returns ModeSession(
            mode = AccessMode.DIRECT,
            ops = sessionOps,
            batch = null,
            lease = lease,
        )

        gateway.delete(
            targets = setOf(target),
            options = DeleteAction.Options(),
            mode = LocalGateway.Mode.AUTO,
        ).collect()

        lease.isClosed shouldBe true
    }

    private class RecordingKeepAlive : KeepAlive {
        override val resourceId: String = "test"
        override var isClosed: Boolean = false
            private set

        override fun close() {
            isClosed = true
        }
    }
}
