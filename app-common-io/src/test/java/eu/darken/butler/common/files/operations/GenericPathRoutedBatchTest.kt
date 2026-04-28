package eu.darken.butler.common.files.operations

import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.routing.AccessIntent
import eu.darken.butler.common.files.local.routing.AccessMode
import eu.darken.butler.common.files.local.routing.BatchEligibility
import eu.darken.butler.common.files.local.routing.CapabilitySnapshot
import eu.darken.butler.common.files.local.routing.ClientBatchOps
import eu.darken.butler.common.files.local.routing.ModeSession
import eu.darken.butler.common.files.local.routing.ModeSessionFactory
import eu.darken.butler.common.files.local.routing.ModeSessionRegistry
import eu.darken.butler.common.files.local.routing.OwnershipFixup
import eu.darken.butler.common.files.local.routing.RouteDecision
import eu.darken.butler.common.files.local.routing.RoutedLocalFileSystemOps
import eu.darken.butler.common.files.local.routing.StaticLocalRouteRouter
import eu.darken.butler.common.files.local.routing.LocalPathRoutingPolicy
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.root.RootUnavailableException
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

class GenericPathRoutedBatchTest : BaseTest() {

    @Test
    fun `copy batch ownership normalization failure is surfaced without retrying batch`() = runTest {
        val source = p("/src")
        val destination = p("/dst")
        val destinationRoot = p("/dst/src")
        val owner = Ownership(1023, 1023, "media_rw", "media_rw")
        val sourceLookup = lookup(source, FileType.DIRECTORY)
        val destinationLookup = lookup(destinationRoot, FileType.DIRECTORY)
        val batch = CountingBatchOps(sourceLookup, destinationLookup)

        val rootOps = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true) {
            coEvery { lookup(source, any<LookupOptions>()) } returns sourceLookup
            coEvery { lookup(destinationRoot, any<LookupOptions>()) } returns destinationLookup
            coEvery { listFiles(destinationRoot) } returns emptyList()
            coEvery { setOwnership(destinationRoot, owner) } returns false
        }
        val directOps = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true) {
            coEvery { lookup(destination, any<LookupOptions>()) } returns lookup(destination, FileType.DIRECTORY)
            coEvery { lookup(destinationRoot, any<LookupOptions>()) } returns LocalPathLookup.unknown(destinationRoot)
            coEvery { createDir(destination, createParents = true) } returns Unit
        }
        val caps = CapabilitySnapshot.fixed(hasRoot = true, hasAdb = false)
        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(source, AccessIntent.Read, caps) } returns RouteDecision.Allowed(AccessMode.ROOT)
            coEvery { classify(destination, AccessIntent.Write, caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            coEvery { classify(destinationRoot, AccessIntent.Write, caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            every { proactiveChildren(any()) } returns emptySet()
            coEvery { batchEligibility(any()) } returns BatchEligibility.Eligible(
                mode = AccessMode.ROOT,
                destinationModeOverride = AccessMode.ROOT,
                ownershipFixup = OwnershipFixup.InheritNearestExistingDestinationOwner(owner),
            )
        }
        val factory = mockk<ModeSessionFactory> {
            coEvery { open(AccessMode.ROOT) } returns ModeSession(AccessMode.ROOT, rootOps, batch, null)
            coEvery { open(AccessMode.DIRECT) } returns ModeSession(AccessMode.DIRECT, directOps, null, null)
        }
        val registry = ModeSessionRegistry(factory)
        val router = StaticLocalRouteRouter(policy, caps, registry)
        val sourceOps = RoutedLocalFileSystemOps(router, AccessIntent.Read)
        val destOps = RoutedLocalFileSystemOps(router, AccessIntent.Write)

        try {
            var issue: PathActionIssue? = null
            val result = setOf(source).copyGeneric(
                destination = destination,
                sourceOps = sourceOps,
                destOps = destOps,
                strategy = GenericCrossTypeCopyStrategy(),
                onIssue = {
                    issue = it
                    PathActionIssue.UnknownError.Resolution.Skip()
                }
            ).last() as CopyAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            batch.copyCalls shouldBe 1
            result.copied.size shouldBe 1
            issue.shouldBeInstanceOf<PathActionIssue.UnknownError>().canRetry shouldBe false
        } finally {
            registry.close()
        }
    }

    @Test
    fun `outer cancellation cancels nested batch without surfacing UnknownError`() = runTest {
        val source = p("/src")
        val destination = p("/dst")
        val destinationRoot = p("/dst/src")
        val sourceLookup = lookup(source, FileType.DIRECTORY)
        val hangingBatch = HangingBatchOps()

        val rootOps = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true) {
            coEvery { lookup(source, any<LookupOptions>()) } returns sourceLookup
        }
        val directOps = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true) {
            coEvery { lookup(destination, any<LookupOptions>()) } returns lookup(destination, FileType.DIRECTORY)
            coEvery { lookup(destinationRoot, any<LookupOptions>()) } returns LocalPathLookup.unknown(destinationRoot)
            coEvery { createDir(destination, createParents = true) } returns Unit
        }
        val caps = CapabilitySnapshot.fixed(hasRoot = true, hasAdb = false)
        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(source, AccessIntent.Read, caps) } returns RouteDecision.Allowed(AccessMode.ROOT)
            coEvery { classify(destination, AccessIntent.Write, caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            coEvery { classify(destinationRoot, AccessIntent.Write, caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            every { proactiveChildren(any()) } returns emptySet()
            coEvery { batchEligibility(any()) } returns BatchEligibility.Eligible(
                mode = AccessMode.ROOT,
                destinationModeOverride = null,
                ownershipFixup = OwnershipFixup.None,
            )
        }
        val factory = mockk<ModeSessionFactory> {
            coEvery { open(AccessMode.ROOT) } returns ModeSession(AccessMode.ROOT, rootOps, hangingBatch, null)
            coEvery { open(AccessMode.DIRECT) } returns ModeSession(AccessMode.DIRECT, directOps, null, null)
        }
        val registry = ModeSessionRegistry(factory)
        val router = StaticLocalRouteRouter(policy, caps, registry)
        val sourceOps = RoutedLocalFileSystemOps(router, AccessIntent.Read)
        val destOps = RoutedLocalFileSystemOps(router, AccessIntent.Write)

        try {
            var surfacedIssue: PathActionIssue? = null
            val job = launch {
                try {
                    setOf(source).copyGeneric(
                        destination = destination,
                        sourceOps = sourceOps,
                        destOps = destOps,
                        strategy = GenericCrossTypeCopyStrategy(),
                        onIssue = {
                            surfacedIssue = it
                            PathActionIssue.UnknownError.Resolution.Skip()
                        }
                    ).last()
                } catch (_: CancellationException) {
                    // expected — outer cancellation propagates as coroutine cancellation,
                    // not as a PathActionIssue
                }
            }

            runCurrent()
            job.cancelAndJoin()

            job.isCancelled shouldBe true
            surfacedIssue shouldBe null
            hangingBatch.copyCalls shouldBe 1
        } finally {
            // Registry must close cleanly even though sessions were leased by the batch
            registry.close()
        }
    }

    @Test
    fun `move cleanup failure is surfaced as issue before completion`() = runTest {
        val source = p("/src")
        val destination = p("/dst")
        val destinationRoot = p("/dst/src")
        val sourceDirLookup = lookup(source, FileType.DIRECTORY)
        val cleanupError = IOException("simulated cleanup failure")

        val ops = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true) {
            coEvery { lookup(source, any<LookupOptions>()) } returns sourceDirLookup
            coEvery { lookup(destination, any<LookupOptions>()) } returns lookup(destination, FileType.DIRECTORY)
            coEvery { lookup(destinationRoot, any<LookupOptions>()) } returns LocalPathLookup.unknown(destinationRoot)
            coEvery { listFiles(source) } returns emptyList()
            coEvery { listFiles(destinationRoot) } returns emptyList()
            coEvery { createDir(any<LocalPath>(), any<Boolean>()) } returns Unit
            coEvery { delete(source, any<Boolean>()) } throws cleanupError
        }
        val caps = CapabilitySnapshot.fixed(hasRoot = false, hasAdb = false)
        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(any(), any(), caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            every { proactiveChildren(any()) } returns emptySet()
            coEvery { batchEligibility(any()) } returns BatchEligibility.Ineligible("not exercising batch")
        }
        val factory = mockk<ModeSessionFactory> {
            coEvery { open(AccessMode.DIRECT) } returns ModeSession(AccessMode.DIRECT, ops, null, null)
        }
        val registry = ModeSessionRegistry(factory)
        val router = StaticLocalRouteRouter(policy, caps, registry)
        val sourceOps = RoutedLocalFileSystemOps(router, AccessIntent.Delete)
        val destOps = RoutedLocalFileSystemOps(router, AccessIntent.Write)

        try {
            var issue: PathActionIssue? = null
            val result = setOf(source).moveGeneric(
                destination = destination,
                sourceOps = sourceOps,
                destOps = destOps,
                strategy = GenericCrossTypeMoveStrategy(),
                options = TransferStrategy.Options(attemptAtomicMove = false),
                onIssue = {
                    issue = it
                    PathActionIssue.UnknownError.Resolution.Skip()
                }
            ).last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

            val unknown = issue.shouldBeInstanceOf<PathActionIssue.UnknownError>()
            unknown.canRetry shouldBe false
            unknown.canSkip shouldBe true
            unknown.exception shouldBe cleanupError
            result shouldNotBe null
        } finally {
            registry.close()
        }
    }

    @Test
    fun `batch retry refuses when destination became populated by partial first attempt`() = runTest {
        val source = p("/src")
        val destination = p("/dst")
        val destinationRoot = p("/dst/src")
        val sourceLookup = lookup(source, FileType.DIRECTORY)
        val failingBatch = FailingThenStubbedBatchOps()

        val rootOps = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true) {
            coEvery { lookup(source, any<LookupOptions>()) } returns sourceLookup
        }
        val directOps = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true) {
            coEvery { lookup(destination, any<LookupOptions>()) } returns lookup(destination, FileType.DIRECTORY)
            // First call: destination doesn't exist (batch is allowed to run).
            // Second call (after retry): destination is partially populated.
            coEvery { lookup(destinationRoot, any<LookupOptions>()) } returnsMany listOf(
                LocalPathLookup.unknown(destinationRoot),
                lookup(destinationRoot, FileType.DIRECTORY),
            )
            coEvery { createDir(destination, createParents = true) } returns Unit
        }
        val caps = CapabilitySnapshot.fixed(hasRoot = true, hasAdb = false)
        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(source, AccessIntent.Read, caps) } returns RouteDecision.Allowed(AccessMode.ROOT)
            coEvery { classify(destination, AccessIntent.Write, caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            coEvery { classify(destinationRoot, AccessIntent.Write, caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            every { proactiveChildren(any()) } returns emptySet()
            coEvery { batchEligibility(any()) } returns BatchEligibility.Eligible(
                mode = AccessMode.ROOT,
                destinationModeOverride = null,
                ownershipFixup = OwnershipFixup.None,
            )
        }
        val factory = mockk<ModeSessionFactory> {
            coEvery { open(AccessMode.ROOT) } returns ModeSession(AccessMode.ROOT, rootOps, failingBatch, null)
            coEvery { open(AccessMode.DIRECT) } returns ModeSession(AccessMode.DIRECT, directOps, null, null)
        }
        val registry = ModeSessionRegistry(factory)
        val router = StaticLocalRouteRouter(policy, caps, registry)
        val sourceOps = RoutedLocalFileSystemOps(router, AccessIntent.Read)
        val destOps = RoutedLocalFileSystemOps(router, AccessIntent.Write)

        try {
            val issues = mutableListOf<PathActionIssue>()
            setOf(source).copyGeneric(
                destination = destination,
                sourceOps = sourceOps,
                destOps = destOps,
                strategy = GenericCrossTypeCopyStrategy(),
                onIssue = { issue ->
                    issues.add(issue)
                    if (issues.size == 1) {
                        // First failure → ask for Retry
                        PathActionIssue.UnknownError.Resolution.Retry
                    } else {
                        // Retry hit destination-already-exists → Skip
                        PathActionIssue.UnknownError.Resolution.Skip()
                    }
                }
            ).last()

            // The batch must have run only once — retry refused before re-invoking the IPC.
            failingBatch.copyCalls shouldBe 1
            issues.size shouldBe 2
            // First issue: retryable (the failure path)
            (issues[0] as PathActionIssue.UnknownError).canRetry shouldBe true
            // Second issue: NOT retryable (destination already exists)
            val refusal = issues[1].shouldBeInstanceOf<PathActionIssue.UnknownError>()
            refusal.canRetry shouldBe false
            refusal.canSkip shouldBe true
        } finally {
            registry.close()
        }
    }

    @Test
    fun `route unavailable from getOrOpen is surfaced as InsufficientPermission`() = runTest {
        val parent = p("/src")
        val child = p("/src/restricted")
        val destination = p("/dst")
        val destinationRoot = p("/dst/src")
        val parentLookup = lookup(parent, FileType.DIRECTORY)

        val directOps = mockk<FileSystemOps<LocalPath, LocalPathLookup>>(relaxed = true) {
            coEvery { lookup(parent, any<LookupOptions>()) } returns parentLookup
            coEvery { lookup(destination, any<LookupOptions>()) } returns lookup(destination, FileType.DIRECTORY)
            coEvery { lookup(destinationRoot, any<LookupOptions>()) } returns LocalPathLookup.unknown(destinationRoot)
            coEvery { listFiles(parent) } returns listOf(child)
            coEvery { createDir(any<LocalPath>(), any<Boolean>()) } returns Unit
        }
        val caps = CapabilitySnapshot.fixed(hasRoot = true, hasAdb = false)
        val policy = mockk<LocalPathRoutingPolicy> {
            coEvery { classify(parent, AccessIntent.Read, caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            coEvery { classify(child, AccessIntent.Read, caps) } returns RouteDecision.Allowed(AccessMode.ROOT)
            coEvery { classify(destination, AccessIntent.Write, caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            coEvery { classify(destinationRoot, AccessIntent.Write, caps) } returns RouteDecision.Allowed(AccessMode.DIRECT)
            coEvery { classify(p("/dst/src/restricted"), AccessIntent.Write, caps) } returns
                RouteDecision.Allowed(AccessMode.DIRECT)
            every { proactiveChildren(any()) } returns emptySet()
            coEvery { batchEligibility(any()) } returns BatchEligibility.Ineligible("not exercising batch")
        }
        val factory = mockk<ModeSessionFactory> {
            coEvery { open(AccessMode.DIRECT) } returns ModeSession(AccessMode.DIRECT, directOps, null, null)
            // Root capability snapshot says hasRoot=true but the actual open fails — mirrors a su revocation.
            coEvery { open(AccessMode.ROOT) } throws RootUnavailableException()
        }
        val registry = ModeSessionRegistry(factory)
        val router = StaticLocalRouteRouter(policy, caps, registry)
        val sourceOps = RoutedLocalFileSystemOps(router, AccessIntent.Read)
        val destOps = RoutedLocalFileSystemOps(router, AccessIntent.Write)

        try {
            var issue: PathActionIssue? = null
            setOf(parent).copyGeneric(
                destination = destination,
                sourceOps = sourceOps,
                destOps = destOps,
                strategy = GenericCrossTypeCopyStrategy(),
                onIssue = {
                    issue = it
                    when (it) {
                        is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                        is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
                        else -> error("Unexpected issue type: $it")
                    }
                }
            ).last()

            val perm = issue.shouldBeInstanceOf<PathActionIssue.InsufficientPermission>()
            perm.canSkip shouldBe true
        } finally {
            registry.close()
        }
    }

    private fun p(path: String): LocalPath = LocalPath.build(path)

    private fun lookup(path: LocalPath, fileType: FileType): LocalPathLookup = LocalPathLookup(
        lookedUp = path,
        fileType = fileType,
        size = 0L,
        modifiedAt = Instant.fromEpochMilliseconds(0),
    )

    private class CountingBatchOps(
        private val sourceLookup: LocalPathLookup,
        private val destinationLookup: LocalPathLookup,
    ) : ClientBatchOps {
        var copyCalls = 0

        override suspend fun copySubtreeExact(
            sourceRoot: LocalPath,
            destinationRoot: LocalPath,
            options: CopyAction.Options,
            onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        ): Flow<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> {
            copyCalls++
            return flowOf(
                CopyAction.State.Completed(
                    copied = setOf(sourceLookup to destinationLookup),
                    skipped = emptySet(),
                    copiedBytes = 1L,
                )
            )
        }

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

    private class HangingBatchOps : ClientBatchOps {
        var copyCalls = 0

        override suspend fun copySubtreeExact(
            sourceRoot: LocalPath,
            destinationRoot: LocalPath,
            options: CopyAction.Options,
            onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        ): Flow<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> {
            copyCalls++
            return flow { awaitCancellation() }
        }

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

    private class FailingThenStubbedBatchOps : ClientBatchOps {
        var copyCalls = 0

        override suspend fun copySubtreeExact(
            sourceRoot: LocalPath,
            destinationRoot: LocalPath,
            options: CopyAction.Options,
            onIssue: (suspend (PathActionIssue) -> PathActionIssue.Resolution)?,
        ): Flow<CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>> {
            copyCalls++
            return flow { throw IOException("simulated batch failure") }
        }

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
}
