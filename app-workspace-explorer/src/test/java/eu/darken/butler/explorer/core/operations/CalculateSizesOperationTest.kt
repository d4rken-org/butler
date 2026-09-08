package eu.darken.butler.explorer.core.operations

import android.content.Context
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.common.storage.ExternalStorageStatsProvider
import eu.darken.butler.common.user.UserHandle2
import eu.darken.butler.explorer.core.sizes.AndroidDataEstimate
import eu.darken.butler.explorer.core.sizes.DirectorySizeStore
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.beInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class CalculateSizesOperationTest : BaseTest() {

    private val root = LocalPath.build("/a")
    private val externalStorageStats = mockk<ExternalStorageStatsProvider> {
        coEvery { prepare(any()) } returns null
    }

    /** Only ever handed to a [eu.darken.butler.common.ca.CaString] that ignores it. */
    private val stringContext = mockk<Context>()
    private val gateway = mockk<APathGateway<LocalPath, LocalPathLookup>>()
    private val gatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { getGateway(any()) } returns gateway
        coEvery { listFiles(any()) } returns emptyList()
    }

    /** The walk runs on a different dispatcher than the collector, as it does in production. */
    private val realIoDispatchers = object : DispatcherProvider {
        override val IO: CoroutineDispatcher get() = Dispatchers.IO
    }

    private fun lookup(path: String, fileType: FileType, size: Long? = null) = LocalPathLookup(
        lookedUp = LocalPath.build(path),
        fileType = fileType,
        size = size,
        modifiedAt = null,
    )

    /** Each read outruns the tracker's report interval, so every walked entry reports progress. */
    private class SteppingClock(private val step: Duration = 300.milliseconds) : Clock {
        private var current = Instant.fromEpochMilliseconds(0)
        override fun now(): Instant {
            current += step
            return current
        }
    }

    private fun operation(store: DirectorySizeStore, dispatcherProvider: DispatcherProvider) =
        CalculateSizesOperation(
            workspaceId = Workspace.Id(),
            command = ExplorerCommand.CalculateSizes(root),
            store = store,
            gatewaySwitch = gatewaySwitch,
            dispatcherProvider = dispatcherProvider,
            clock = SteppingClock(),
            externalStorageStats = externalStorageStats,
        )

    private fun context() = Operation.Context(id = Operation.Id(), startedAt = Instant.DISTANT_PAST)

    private fun estimatedWalk(): ExternalStorageStatsProvider.Target {
        val target = ExternalStorageStatsProvider.Target(root, Uuid.NIL, UserHandle2())
        coEvery { externalStorageStats.prepare(root) } returns target
        @Suppress("UNCHECKED_CAST")
        val rootLookup = lookup(root.path, FileType.DIRECTORY).copy(allocatedSize = 100) as APathLookup<APath<*>>
        coEvery { gatewaySwitch.lookup(root, any()) } returns rootLookup
        coEvery { externalStorageStats.query(target) } returns ExternalStorageStatsProvider.Snapshot(5000, Instant.DISTANT_PAST)
        coEvery { gateway.walk(any(), any(), any()) } coAnswers {
            secondArg<eu.darken.butler.common.files.LookupOptions>().fetchAllocatedSize shouldBe true
            val options = thirdArg<APathGateway.WalkOptions<LocalPath, LocalPathLookup>>()
            flow {
                emit(lookup("/a/Android", FileType.DIRECTORY).copy(allocatedSize = 100))
                emit(lookup("/a/Android/data", FileType.DIRECTORY).copy(allocatedSize = 100))
                emit(lookup("/a/visible", FileType.FILE, 1000).copy(allocatedSize = 1000))
                options.onError!!.invoke(lookup("/a/Android/data", FileType.DIRECTORY), IOException("denied"))
                options.onError!!.invoke(lookup("/a/Android/obb", FileType.DIRECTORY), IOException("denied"))
            }
        }
        return target
    }

    @Test
    fun `an estimated scan publishes provenance without clearing its unreadable locations`() = runTest {
        val target = estimatedWalk()
        val store = DirectorySizeStore()
        val completed = operation(store, realIoDispatchers).perform(context()).last() as ExplorerOperation.State.Completed
        val scan = store.snapshot.value.scanFor(root).shouldNotBeNull()
        scan.estimate!!.target shouldBe target
        scan.sizes.getValue("/a/Android/data").bytes shouldBe 3700L
        scan.sizes.getValue("/a").hasUnestimatedContent shouldBe true
        (completed.report as CalculateSizesOperation.Report).errorCount shouldBe 2
        scan.problems.size shouldBe 2
    }

    @Test
    fun `statistics failure leaves the measured scan intact`() = runTest {
        val target = estimatedWalk()
        coEvery { externalStorageStats.query(target) } throws IOException("unavailable")
        val store = DirectorySizeStore()
        operation(store, realIoDispatchers).perform(context()).last()
        store.snapshot.value.scanFor(root).shouldNotBeNull().apply {
            estimate shouldBe null
            estimateFailure shouldBe AndroidDataEstimate.Failure.STATISTICS_UNAVAILABLE
            sizes.getValue("/a").bytes shouldBe 1000L
        }
    }

    @Test
    fun `cancellation while querying statistics publishes nothing`() = runTest {
        val target = estimatedWalk()
        val entered = CompletableDeferred<Unit>()
        coEvery { externalStorageStats.query(target) } coAnswers {
            entered.complete(Unit)
            awaitCancellation()
        }
        val store = DirectorySizeStore()
        val job = launch { operation(store, realIoDispatchers).perform(context()).collect() }
        entered.await()
        job.cancelAndJoin()
        store.snapshot.value.scans shouldBe emptyMap()
    }

    @Test
    fun `invalidation during the statistics query discards the whole estimate`() = runTest {
        val target = estimatedWalk()
        val store = DirectorySizeStore()
        store.markRunning(root)
        coEvery { externalStorageStats.query(target) } coAnswers {
            store.invalidate(listOf(root.child("visible")))
            ExternalStorageStatsProvider.Snapshot(5000, Instant.DISTANT_PAST)
        }
        val completed = operation(store, realIoDispatchers).perform(context()).last() as ExplorerOperation.State.Completed
        (completed.report as CalculateSizesOperation.Report).wasDiscarded shouldBe true
        store.snapshot.value.scans shouldBe emptyMap()
    }

    @Test
    fun `a failed location is reported and the totals are published`() = runTest {
        coEvery { gateway.walk(any(), any(), any()) } coAnswers {
            val options = thirdArg<APathGateway.WalkOptions<LocalPath, LocalPathLookup>>()
            flow {
                options.onError!!.invoke(lookup("/a/locked", FileType.DIRECTORY), IOException("denied"))
                emit(lookup("/a/b", FileType.DIRECTORY))
                emit(lookup("/a/b/file", FileType.FILE, size = 10L))
            }
        }
        val store = DirectorySizeStore()

        val completed = operation(store, realIoDispatchers)
            .perform(context())
            .last() as ExplorerOperation.State.Completed

        completed.error shouldBe null
        val report = completed.report as CalculateSizesOperation.Report
        report.errorCount shouldBe 1
        report.itemCount shouldBe 2

        val scan = store.snapshot.value.scanFor(root).shouldNotBeNull()
        scan.sizes.getValue("/a").bytes shouldBe 10L
        scan.sizes.getValue("/a").isComplete shouldBe false
        scan.sizes.getValue("/a/b").isComplete shouldBe true
        coVerify(exactly = 0) { externalStorageStats.query(any()) }
    }

    @Test
    fun `a scan invalidated while it ran reports that it was discarded`() = runTest {
        coEvery { gateway.walk(any(), any(), any()) } returns flow {
            emit(lookup("/a/file", FileType.FILE, size = 10L))
        }
        val store = DirectorySizeStore()
        store.markRunning(root)
        store.invalidate(listOf(LocalPath.build("/a/x")))

        val completed = operation(store, realIoDispatchers)
            .perform(context())
            .last() as ExplorerOperation.State.Completed

        completed.error shouldBe null
        val report = completed.report as CalculateSizesOperation.Report
        report.wasDiscarded shouldBe true

        store.snapshot.value.scanFor(root) shouldBe null
    }

    @Test
    fun `a cancelled scan publishes nothing`() = runTest {
        coEvery { gateway.walk(any(), any(), any()) } returns flow { awaitCancellation() }
        val store = DirectorySizeStore()

        val job = launch {
            operation(store, TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)))
                .perform(context())
                .collect()
        }
        job.cancelAndJoin()

        store.snapshot.value.scans shouldBe emptyMap()
    }

    @Test
    fun `progress counts the root's children as the walk passes them`() = runTest {
        coEvery { gatewaySwitch.listFiles(root) } returns listOf(
            LocalPath.build("/a/one"),
            LocalPath.build("/a/two"),
        )
        coEvery { gateway.walk(any(), any(), any()) } returns flow {
            emit(lookup("/a/one", FileType.DIRECTORY))
            emit(lookup("/a/one/file", FileType.FILE, size = 10L))
            emit(lookup("/a/two", FileType.DIRECTORY))
            emit(lookup("/a/two/file", FileType.FILE, size = 20L))
        }

        val states = operation(DirectorySizeStore(), realIoDispatchers)
            .perform(context())
            .toList()
            .filterIsInstance<ExplorerOperation.State.Active>()

        val onSecondChild = states.firstOrNull {
            val count = it.primaryProgress.count
            count is Progress.Count.Counter && count.current == 1L && count.max == 2L
        }.shouldNotBeNull()

        onSecondChild.secondaryProgress.shouldNotBeNull().primary.get(stringContext) shouldBe "two"

        // The state sent right after the file entry `/a/one/file`, which names its parent
        val onFirstChildsFile = states.last {
            val count = it.primaryProgress.count
            count is Progress.Count.Counter && count.current == 0L && count.max == 2L
        }
        onFirstChildsFile.secondaryProgress.shouldNotBeNull().primary.get(stringContext) shouldBe "one"
    }

    @Test
    fun `progress names the scanned directory relative to the root`() = runTest {
        coEvery { gateway.walk(any(), any(), any()) } returns flow {
            emit(lookup("/a/one", FileType.DIRECTORY))
            emit(lookup("/a/one/deep", FileType.DIRECTORY))
            emit(lookup("/a/one/deep/file", FileType.FILE, size = 10L))
        }

        val states = operation(DirectorySizeStore(), realIoDispatchers)
            .perform(context())
            .toList()
            .filterIsInstance<ExplorerOperation.State.Active>()

        states.last().secondaryProgress.shouldNotBeNull().primary.get(stringContext) shouldBe "one/deep"
    }

    @Test
    fun `progress names a location the walk could not enter`() = runTest {
        coEvery { gateway.walk(any(), any(), any()) } coAnswers {
            val options = thirdArg<APathGateway.WalkOptions<LocalPath, LocalPathLookup>>()
            flow {
                emit(lookup("/a/one", FileType.DIRECTORY))
                options.onError!!.invoke(lookup("/a/locked", FileType.UNKNOWN), IOException("denied"))
                emit(lookup("/a/one/file", FileType.FILE, size = 10L))
            }
        }

        val states = operation(DirectorySizeStore(), realIoDispatchers)
            .perform(context())
            .toList()
            .filterIsInstance<ExplorerOperation.State.Active>()

        states.mapNotNull { it.secondaryProgress?.primary?.get(stringContext) } shouldContain "locked"
    }

    @Test
    fun `a root that cannot be listed keeps an indeterminate count`() = runTest {
        coEvery { gatewaySwitch.listFiles(root) } throws IOException("denied")
        coEvery { gateway.walk(any(), any(), any()) } returns flow {
            emit(lookup("/a/one", FileType.DIRECTORY))
            emit(lookup("/a/one/file", FileType.FILE, size = 10L))
        }

        val states = operation(DirectorySizeStore(), realIoDispatchers)
            .perform(context())
            .toList()
            .filterIsInstance<ExplorerOperation.State.Active>()

        states.shouldNotBeEmpty()
        states.forEach { it.primaryProgress.count should beInstanceOf<Progress.Count.Indeterminate>() }
    }

    @Test
    fun `the report's performance history spans the items that were scanned`() = runTest {
        coEvery { gateway.walk(any(), any(), any()) } returns flow {
            emit(lookup("/a/one", FileType.DIRECTORY))
            emit(lookup("/a/one/file", FileType.FILE, size = 10L))
        }

        val completed = operation(DirectorySizeStore(), realIoDispatchers)
            .perform(context())
            .last() as ExplorerOperation.State.Completed

        val report = completed.report as CalculateSizesOperation.Report
        report.itemCount shouldBe 2
        report.performanceHistory?.totalItems shouldBe 2
    }

    @Test
    fun `a long scan keeps the start of its performance history`() = runTest {
        coEvery { gateway.walk(any(), any(), any()) } returns flow {
            emit(lookup("/a/one", FileType.DIRECTORY))
            repeat(1100) { emit(lookup("/a/one/file$it", FileType.FILE, size = 10L)) }
        }

        val completed = operation(DirectorySizeStore(), realIoDispatchers)
            .perform(context())
            .last() as ExplorerOperation.State.Completed

        val report = completed.report as CalculateSizesOperation.Report
        val samples = report.performanceHistory.shouldNotBeNull().samples
        samples.size shouldBeLessThanOrEqualTo 1000
        samples.minOf { it.totalItemsProcessed } shouldBeLessThanOrEqualTo 2
    }
}
