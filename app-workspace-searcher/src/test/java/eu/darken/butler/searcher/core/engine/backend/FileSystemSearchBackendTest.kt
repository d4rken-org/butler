package eu.darken.butler.searcher.core.engine.backend

import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.MetadataRepo
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.engine.ContentMatcher
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.FilterComparator
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchFilter
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class FileSystemSearchBackendTest : BaseTest() {

    private val target = SearchTarget.Path.from(LocalPath.build("/sdcard"))

    private fun lookup(
        path: String,
        fileType: FileType = FileType.FILE,
        size: Long? = 100L,
    ) = LocalPathLookup(
        lookedUp = LocalPath.build(path),
        fileType = fileType,
        size = size,
        modifiedAt = null,
        target = null,
    )

    private inner class Harness {
        val walkOptionsSlot = slot<APathGateway.WalkOptions<LocalPath, LocalPathLookup>>()
        val lookupOptionsSlot = slot<LookupOptions>()
        val gateway = mockk<APathGateway<LocalPath, LocalPathLookup>>()
        val gatewaySwitch = mockk<GatewaySwitch> {
            coEvery { getGateway(any()) } returns gateway
        }
        val contentMatcher = mockk<ContentMatcher>()
        val backend = FileSystemSearchBackend(
            gatewaySwitch = gatewaySwitch,
            metadataRepo = mockk<MetadataRepo> {
                coEvery { extract(any()) } returns null
            },
            dispatcherProvider = TestDispatcherProvider(),
            contentMatcher = contentMatcher,
            pathPermissionCheck = mockk(),
        )
        val progressUpdates = mutableListOf<SearchBackend.ScanProgress>()

        fun stubWalk(block: suspend FlowCollector<LocalPathLookup>.() -> Unit) {
            coEvery {
                gateway.walk(any(), capture(lookupOptionsSlot), capture(walkOptionsSlot))
            } answers { flow(block) }
        }

        fun session(query: SearchQuery, includeBinaries: Boolean = false) = SearchBackend.ScanSession(
            workspaceId = Workspace.Id(),
            target = target,
            query = query,
            includeBinaries = includeBinaries,
            onProgress = { progressUpdates += it },
        )
    }

    @Test
    fun `filter conditions are applied to the walked items`(): Unit = runTest {
        val harness = Harness()
        harness.stubWalk {
            emit(lookup("/sdcard/small.txt", size = 50L))
            emit(lookup("/sdcard/big.txt", size = 200L))
        }
        val query = SearchQuery(
            targets = listOf(target),
            filter = SearchFilter(conditions = listOf(FilterCondition.Size(FilterComparator.GT, 100L))),
        )

        val results = harness.backend.scan(harness.session(query)).toList()

        results.map { it.path.path } shouldContainExactly listOf("/sdcard/big.txt")
        results.single().matchContext!!.matchType shouldBe SearchItem.MatchContext.MatchType.FILTER
    }

    @Test
    fun `walk errors are counted and reported through progress`(): Unit = runTest {
        val harness = Harness()
        val firstDenied = lookup("/sdcard/Android/data", fileType = FileType.DIRECTORY)
        val secondDenied = lookup("/sdcard/Android/obb", fileType = FileType.DIRECTORY)
        val onErrorReturns = mutableListOf<Boolean>()
        harness.stubWalk {
            val onError = harness.walkOptionsSlot.captured.onError!!
            onErrorReturns += onError.invoke(firstDenied, IOException("denied"))
            emit(lookup("/sdcard/needle.txt"))
            onErrorReturns += onError.invoke(secondDenied, IOException("denied too"))
        }
        val query = SearchQuery(
            filenameQuery = FilenameQuery(pattern = "needle"),
            targets = listOf(target),
        )

        val results = harness.backend.scan(harness.session(query)).toList()

        results.size shouldBe 1
        // The walk must continue after errors, they are reported via progress instead
        onErrorReturns shouldBe listOf(true, true)
        val finalProgress = harness.progressUpdates.last()
        finalProgress.errorCount shouldBe 2
        finalProgress.firstErrorPath shouldBe firstDenied.lookedUp
    }

    @Test
    fun `degraded content outcome counts as error but still yields the match`(): Unit = runTest {
        val harness = Harness()
        val file = lookup("/sdcard/huge-line.txt")
        harness.stubWalk { emit(file) }
        coEvery { harness.contentMatcher.matchesContent(any(), any(), any()) } returns ContentMatcher.Outcome.Match(
            context = SearchItem.MatchContext(matchType = SearchItem.MatchContext.MatchType.CONTENT),
            degraded = true,
        )
        val query = SearchQuery(
            contentQuery = ContentQuery(pattern = "needle"),
            targets = listOf(target),
        )

        val results = harness.backend.scan(harness.session(query)).toList()

        results.size shouldBe 1
        val finalProgress = harness.progressUpdates.last()
        finalProgress.errorCount shouldBe 1
        finalProgress.firstErrorPath shouldBe file.lookedUp
    }

    @Test
    fun `failed content outcome counts as error and yields no match`(): Unit = runTest {
        val harness = Harness()
        val file = lookup("/sdcard/unreadable.txt")
        harness.stubWalk { emit(file) }
        coEvery { harness.contentMatcher.matchesContent(any(), any(), any()) } returns ContentMatcher.Outcome.Failed(
            error = IOException("read failed"),
        )
        val query = SearchQuery(
            contentQuery = ContentQuery(pattern = "needle"),
            targets = listOf(target),
        )

        val results = harness.backend.scan(harness.session(query)).toList()

        results.size shouldBe 0
        val finalProgress = harness.progressUpdates.last()
        finalProgress.errorCount shouldBe 1
        finalProgress.firstErrorPath shouldBe file.lookedUp
    }

    @Test
    fun `unreadable UNKNOWN lookup is counted as error and never matched`(): Unit = runTest {
        val harness = Harness()
        // Its name would match the query, but the entry couldn't be read (continueOnError)
        val unreadable = LocalPathLookup.unknown(LocalPath.build("/sdcard/needle-locked.txt"), "Permission denied")
        harness.stubWalk {
            emit(unreadable)
            emit(lookup("/sdcard/needle.txt"))
        }
        val query = SearchQuery(
            filenameQuery = FilenameQuery(pattern = "needle"),
            targets = listOf(target),
        )

        val results = harness.backend.scan(harness.session(query)).toList()

        results.map { it.path.path } shouldContainExactly listOf("/sdcard/needle.txt")
        val finalProgress = harness.progressUpdates.last()
        finalProgress.errorCount shouldBe 1
        finalProgress.firstErrorPath shouldBe unreadable.lookedUp
    }

    @Test
    fun `walk receives the reduced lookup projection`(): Unit = runTest {
        val harness = Harness()
        harness.stubWalk { emit(lookup("/sdcard/needle.txt")) }
        val query = SearchQuery(
            filenameQuery = FilenameQuery(pattern = "needle"),
            targets = listOf(target),
        )

        harness.backend.scan(harness.session(query)).toList()

        harness.lookupOptionsSlot.captured shouldBe FileSystemSearchBackend.LOOKUP_PROJECTION
        harness.lookupOptionsSlot.captured shouldBe LookupOptions(
            continueOnError = true,
            fetchSize = true,
            fetchModifiedAt = true,
            fetchCreatedAt = true,
        )
    }

    @Test
    fun `followSymlinks from query options is propagated into the walk`(): Unit = runTest {
        val harness = Harness()
        harness.stubWalk { }
        val query = SearchQuery(
            filenameQuery = FilenameQuery(pattern = "needle"),
            targets = listOf(target),
            options = SearchQuery.Options(followSymlinks = true),
        )

        harness.backend.scan(harness.session(query)).toList()

        harness.walkOptionsSlot.captured.followSymlinks shouldBe true
    }

    @Test
    fun `followSymlinks defaults to false`(): Unit = runTest {
        val harness = Harness()
        harness.stubWalk { }
        val query = SearchQuery(
            filenameQuery = FilenameQuery(pattern = "needle"),
            targets = listOf(target),
        )

        harness.backend.scan(harness.session(query)).toList()

        harness.walkOptionsSlot.captured.followSymlinks shouldBe false
    }

    @Test
    fun `final progress flush reports accurate totals`(): Unit = runTest {
        val harness = Harness()
        harness.stubWalk {
            emit(lookup("/sdcard/one.txt"))
            emit(lookup("/sdcard/the-needle.txt"))
            emit(lookup("/sdcard/three.txt"))
        }
        val query = SearchQuery(
            filenameQuery = FilenameQuery(pattern = "needle"),
            targets = listOf(target),
        )

        val results = harness.backend.scan(harness.session(query)).toList()

        results.size shouldBe 1
        // Only 3 items scanned: no interval update fires, only the final flush
        harness.progressUpdates.size shouldBe 1
        val finalProgress = harness.progressUpdates.single()
        finalProgress.itemsScanned shouldBe 3
        finalProgress.resultsFound shouldBe 1
        finalProgress.errorCount shouldBe 0
        finalProgress.currentPath shouldBe target.path
    }
}
