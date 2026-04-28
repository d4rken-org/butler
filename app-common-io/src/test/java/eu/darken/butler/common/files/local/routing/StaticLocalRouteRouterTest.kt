package eu.darken.butler.common.files.local.routing

import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.root.RootUnavailableException
import eu.darken.butler.common.sharedresource.KeepAlive
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import testhelpers.BaseTest
import java.io.ByteArrayInputStream
import kotlin.time.Instant

class StaticLocalRouteRouterTest : BaseTest() {

    private val caps = CapabilitySnapshot(hasRoot = true, hasAdb = false)

    @Test
    fun `router caches same path independently per intent`() = runTest {
        val path = p("/same/path")
        val directOps = mockOps()
        val rootOps = mockOps()
        val factory = mockk<ModeSessionFactory> {
            coEvery { open(AccessMode.DIRECT) } returns ModeSession(AccessMode.DIRECT, directOps, null, null)
            coEvery { open(AccessMode.ROOT) } returns ModeSession(AccessMode.ROOT, rootOps, fakeBatch(), null)
        }
        val policy = mockk<LocalPathRoutingPolicy> {
            every { classify(path, AccessIntent.Read, caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            every { classify(path, AccessIntent.Write, caps) } returns RouteDecision.Allowed(AccessMode.ROOT)
        }
        val registry = ModeSessionRegistry(factory)
        val router = StaticLocalRouteRouter(policy, caps, registry)

        try {
            router.routeFor(path, AccessIntent.Read).mode shouldBe AccessMode.DIRECT
            router.routeFor(path, AccessIntent.Write).mode shouldBe AccessMode.ROOT
            router.routeFor(path, AccessIntent.Read).ops shouldBe directOps

            coVerify(exactly = 1) { factory.open(AccessMode.DIRECT) }
            coVerify(exactly = 1) { factory.open(AccessMode.ROOT) }
        } finally {
            registry.close()
        }
    }

    @Test
    fun `route maps backend loss to route unavailable`() = runTest {
        val path = p("/sdcard/Android/data/other")
        val factory = mockk<ModeSessionFactory> {
            coEvery { open(AccessMode.ROOT) } throws RootUnavailableException()
        }
        val policy = mockk<LocalPathRoutingPolicy> {
            every { classify(path, AccessIntent.Read, caps) } returns RouteDecision.Allowed(AccessMode.ROOT)
        }
        val registry = ModeSessionRegistry(factory)
        val router = StaticLocalRouteRouter(policy, caps, registry)

        try {
            val error = shouldThrow<RouteUnavailableException> {
                router.routeFor(path, AccessIntent.Read)
            }

            error.path shouldBe path
            error.intent shouldBe AccessIntent.Read
            (error.cause is RootUnavailableException) shouldBe true
        } finally {
            registry.close()
        }
    }

    @Test
    fun `lookupFiles returns unknown lookups for denied proactive children`() = runTest {
        val parent = p("/sdcard")
        val child = p("/sdcard/Android/data")
        val directOps = mockOps()
        coEvery { directOps.lookupFiles(parent, any<LookupOptions>()) } returns emptyList()

        val factory = mockk<ModeSessionFactory> {
            coEvery { open(AccessMode.DIRECT) } returns ModeSession(AccessMode.DIRECT, directOps, null, null)
        }
        val policy = mockk<LocalPathRoutingPolicy> {
            every { classify(parent, AccessIntent.Read, caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            every { classify(child, AccessIntent.Read, caps) } returns RouteDecision.Denied
            every { proactiveChildren(parent) } returns setOf(child)
        }
        val registry = ModeSessionRegistry(factory)
        val router = StaticLocalRouteRouter(policy, caps, registry)
        val ops = RoutedLocalFileSystemOps(router, AccessIntent.Read)

        try {
            val lookups = ops.lookupFiles(parent, AccessIntent.Read, LookupOptions.BASE)

            lookups.map { it.lookedUp } shouldBe listOf(child)
            lookups.single().fileType shouldBe FileType.UNKNOWN
        } finally {
            registry.close()
        }
    }

    @Test
    fun `retained stream keeps session lease alive until stream close`() {
        val lease = RecordingKeepAlive()
        val session = ModeSession(AccessMode.ROOT, mockOps(), fakeBatch(), lease)
        val stream = session.retainLeaseFor(ByteArrayInputStream(byteArrayOf(1)))

        session.close()
        lease.isClosed shouldBe false

        stream.close()
        lease.isClosed shouldBe true
    }

    private fun p(path: String): LocalPath = LocalPath.build(path)

    private fun lookup(path: LocalPath, type: FileType = FileType.UNKNOWN): LocalPathLookup = LocalPathLookup(
        lookedUp = path,
        fileType = type,
        size = 0L,
        modifiedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun mockOps(): FileSystemOps<LocalPath, LocalPathLookup> {
        val ops = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true)
        coEvery { ops.lookup(any(), any<LookupOptions>()) } answers { lookup(firstArg()) }
        return ops
    }

    private fun fakeBatch(): ClientBatchOps = object : ClientBatchOps {
        override suspend fun copySubtreeExact(
            sourceRoot: LocalPath,
            destinationRoot: LocalPath,
            options: CopyAction.Options,
            onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        ): Flow<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> = emptyFlow()

        override suspend fun moveSubtreeExact(
            sourceRoot: LocalPath,
            destinationRoot: LocalPath,
            options: MoveAction.Options,
            onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        ): Flow<MoveAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> = emptyFlow()

        override suspend fun deleteSubtree(
            root: LocalPath,
            options: DeleteAction.Options<LocalPath>,
        ): Flow<DeleteAction.State<LocalPath, LocalPathLookup>> = emptyFlow()
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
