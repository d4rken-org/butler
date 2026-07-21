package eu.darken.butler.searcher.core.engine

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.engine.backend.SearchBackend
import eu.darken.butler.searcher.core.engine.backend.UnsupportedFilterException
import eu.darken.butler.searcher.core.engine.backend.UnsupportedTargetException
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.FilterComparator
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchFilter
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class SearchEngineTest : BaseTest() {

    private class FakeBackend(
        override val priority: Int = 0,
        private val handles: (SearchTarget) -> Boolean = { true },
        private val supported: (FilterCondition) -> Boolean = { true },
        private val items: List<SearchItem> = emptyList(),
    ) : SearchBackend {
        val scannedTargets = mutableListOf<SearchTarget>()

        override fun canHandle(target: SearchTarget): Boolean = handles(target)
        override fun supports(condition: FilterCondition): Boolean = supported(condition)
        override fun monitorRequirements(target: SearchTarget): Flow<PathRequirements> = flowOf(PathRequirements())
        override suspend fun scan(session: SearchBackend.ScanSession): Flow<SearchBackend.BackendResult> {
            scannedTargets += session.target
            return items.map { SearchBackend.BackendResult(it, SearchBackend.BackendResult.RANK_FILESYSTEM) }.asFlow()
        }
    }

    private fun TestScope.createEngine(
        backends: Set<SearchBackend>,
        savedTargets: List<SearchTarget>,
    ) = SearchEngine(
        workspaceId = Workspace.Id(),
        workspaceScope = backgroundScope,
        backends = backends,
        dispatcherProvider = TestDispatcherProvider(),
        storageManager2 = mockk(),
        searcherSettings = mockk<SearcherSettings> {
            every { searchDefaultTargets } returns mockk {
                every { flow } returns flowOf(savedTargets)
            }
            every { contentSearchBinaries } returns mockk {
                every { flow } returns flowOf(false)
            }
        },
    )

    @Nested
    inner class ResultTests {

        @Test
        fun `InvalidQuery result type exists`() {
            val result: SearchEngine.Result = SearchEngine.Result.InvalidQuery()
            result.shouldBeInstanceOf<SearchEngine.Result.InvalidQuery>()
        }

        @Test
        fun `InvalidQuery holds optional reason`() {
            SearchEngine.Result.InvalidQuery().reason shouldBe null
            SearchEngine.Result.InvalidQuery("Dangling meta character").reason shouldBe "Dangling meta character"
        }

        @Test
        fun `NoTargets result type exists`() {
            val result: SearchEngine.Result = SearchEngine.Result.NoTargets
            result.shouldBeInstanceOf<SearchEngine.Result.NoTargets>()
        }

        @Test
        fun `PermissionsRequired result holds requirements`() {
            val requirements = PathRequirements(
                combos = setOf(setOf(SetupModule.Type.STORAGE)),
                complete = emptySet(),
            )
            val result = SearchEngine.Result.PermissionsRequired(requirements)

            result.requirements shouldBe requirements
            result.requirements.needsSetup shouldBe true
        }

        @Test
        fun `Error result holds exception`() {
            val exception = RuntimeException("Test error")
            val result = SearchEngine.Result.Error(exception)

            result.exception shouldBe exception
            result.exception.message shouldBe "Test error"
        }
    }

    @Nested
    inner class QueryValidationTests {

        @Test
        fun `invalid regex filename query returns InvalidQuery with reason`(): Unit = runTest {
            val target = SearchTarget.Path.from(LocalPath.build("/sdcard"))
            val engine = createEngine(setOf(FakeBackend()), listOf(target))

            val result = engine.search(
                SearcherCommand.Search(
                    filenameQuery = FilenameQuery(pattern = "[invalid", useRegex = true),
                    targets = listOf(target),
                )
            )

            result.shouldBeInstanceOf<SearchEngine.Result.InvalidQuery>()
            result.reason.shouldNotBeNull()
        }

        @Test
        fun `invalid regex content query returns InvalidQuery with reason`(): Unit = runTest {
            val target = SearchTarget.Path.from(LocalPath.build("/sdcard"))
            val engine = createEngine(setOf(FakeBackend()), listOf(target))

            val result = engine.search(
                SearcherCommand.Search(
                    contentQuery = ContentQuery(pattern = "(unclosed", useRegex = true),
                    targets = listOf(target),
                )
            )

            result.shouldBeInstanceOf<SearchEngine.Result.InvalidQuery>()
            result.reason.shouldNotBeNull()
        }

        @Test
        fun `content-only search with empty filename side does not trip validation`(): Unit = runTest {
            val target = SearchTarget.Path.from(LocalPath.build("/sdcard"))
            val engine = createEngine(setOf(FakeBackend()), listOf(target))

            val result = engine.search(
                SearcherCommand.Search(
                    // Empty pattern with regex enabled: validation must only run on non-empty sides
                    filenameQuery = FilenameQuery(pattern = "", useRegex = true),
                    contentQuery = ContentQuery(pattern = "needle"),
                    targets = listOf(target),
                )
            )

            result.shouldBeInstanceOf<SearchEngine.Result.Success>()
        }

        @Test
        fun `filter-only search is valid without any pattern`(): Unit = runTest {
            val target = SearchTarget.Path.from(LocalPath.build("/sdcard"))
            val engine = createEngine(setOf(FakeBackend()), listOf(target))

            val result = engine.search(
                SearcherCommand.Search(
                    targets = listOf(target),
                    filter = SearchFilter(
                        conditions = listOf(FilterCondition.Size(FilterComparator.GTE, 1024L)),
                    ),
                )
            )

            result.shouldBeInstanceOf<SearchEngine.Result.Success>()
        }

        @Test
        fun `no patterns and no filters returns InvalidQuery`(): Unit = runTest {
            val target = SearchTarget.Path.from(LocalPath.build("/sdcard"))
            val engine = createEngine(setOf(FakeBackend()), listOf(target))

            val result = engine.search(SearcherCommand.Search(targets = listOf(target)))

            result.shouldBeInstanceOf<SearchEngine.Result.InvalidQuery>()
        }
    }

    @Nested
    inner class BackendDispatchTests {

        @Test
        fun `target without a capable backend errors while others complete`(): Unit = runTest {
            val handledTarget = SearchTarget.Path.from(LocalPath.build("/sdcard"))
            val orphanTarget = SearchTarget.Path.from(LocalPath.build("/data/orphan"))
            val backend = FakeBackend(handles = { it == handledTarget })
            val engine = createEngine(setOf(backend), listOf(handledTarget, orphanTarget))

            val result = engine.search(
                SearcherCommand.Search(
                    filenameQuery = FilenameQuery(pattern = "x"),
                    targets = listOf(handledTarget, orphanTarget),
                )
            )

            result.shouldBeInstanceOf<SearchEngine.Result.Success>()
            result.results.toList()

            val progress = engine.targetProgressState.value
            progress.size shouldBe 2
            progress.first { it.target == handledTarget }.status shouldBe
                SearchEngine.SearchTargetProgress.Status.COMPLETED
            val orphanProgress = progress.first { it.target == orphanTarget }
            orphanProgress.status shouldBe SearchEngine.SearchTargetProgress.Status.ERROR
            orphanProgress.exception.shouldBeInstanceOf<UnsupportedTargetException>()
        }

        @Test
        fun `higher priority backend wins when both can handle a target`(): Unit = runTest {
            val target = SearchTarget.Path.from(LocalPath.build("/sdcard"))
            val lowPriority = FakeBackend(priority = 1)
            val highPriority = FakeBackend(priority = 5)
            val engine = createEngine(setOf(lowPriority, highPriority), listOf(target))

            val result = engine.search(
                SearcherCommand.Search(
                    filenameQuery = FilenameQuery(pattern = "x"),
                    targets = listOf(target),
                )
            )

            result.shouldBeInstanceOf<SearchEngine.Result.Success>()
            result.results.toList()

            highPriority.scannedTargets shouldBe listOf<SearchTarget>(target)
            lowPriority.scannedTargets.shouldBeEmpty()
        }

        @Test
        fun `unsupported filter condition errors the target`(): Unit = runTest {
            val target = SearchTarget.Path.from(LocalPath.build("/sdcard"))
            val backend = FakeBackend(supported = { false })
            val engine = createEngine(setOf(backend), listOf(target))

            val result = engine.search(
                SearcherCommand.Search(
                    filenameQuery = FilenameQuery(pattern = "x"),
                    targets = listOf(target),
                    filter = SearchFilter(
                        conditions = listOf(FilterCondition.Size(FilterComparator.GTE, 1024L)),
                    ),
                )
            )

            result.shouldBeInstanceOf<SearchEngine.Result.Success>()
            result.results.toList()

            val progress = engine.targetProgressState.value.single()
            progress.status shouldBe SearchEngine.SearchTargetProgress.Status.ERROR
            progress.exception.shouldBeInstanceOf<UnsupportedFilterException>()
            backend.scannedTargets.shouldBeEmpty()
        }
    }

    @Nested
    inner class SearchProgressTests {

        @Test
        fun `SearchProgress holds progress data`() {
            val path = LocalPath.build("/sdcard")
            val progress = SearchEngine.SearchProgress(
                currentPath = path,
                itemsScanned = 100,
                resultsFound = 5,
            )

            progress.currentPath shouldBe path
            progress.itemsScanned shouldBe 100
            progress.resultsFound shouldBe 5
        }

        @Test
        fun `SearchProgress can start with zero counts`() {
            val path = LocalPath.build("/sdcard")
            val progress = SearchEngine.SearchProgress(
                currentPath = path,
                itemsScanned = 0,
                resultsFound = 0,
            )

            progress.itemsScanned shouldBe 0
            progress.resultsFound shouldBe 0
        }
    }

    @Nested
    inner class SearchTargetProgressTests {

        @Test
        fun `SearchTargetProgress Status enum has expected values`() {
            val statuses = SearchEngine.SearchTargetProgress.Status.entries

            statuses.size shouldBe 4
            statuses.map { it.name } shouldBe listOf("SEARCHING", "COMPLETED", "ERROR", "CANCELLED")
        }

        @Test
        fun `SearchTargetProgress with SEARCHING status`() {
            val target = SearchTarget.Path(LocalPath.build("/sdcard"), enabled = true)
            val progress = SearchEngine.SearchTargetProgress(
                target = target,
                itemsScanned = 50,
                resultsFound = 2,
                status = SearchEngine.SearchTargetProgress.Status.SEARCHING,
            )

            progress.status shouldBe SearchEngine.SearchTargetProgress.Status.SEARCHING
            progress.exception shouldBe null
        }

        @Test
        fun `SearchTargetProgress with ERROR status holds exception`() {
            val target = SearchTarget.Path(LocalPath.build("/sdcard"), enabled = true)
            val exception = RuntimeException("Access denied")
            val progress = SearchEngine.SearchTargetProgress(
                target = target,
                itemsScanned = 10,
                resultsFound = 0,
                status = SearchEngine.SearchTargetProgress.Status.ERROR,
                exception = exception,
            )

            progress.status shouldBe SearchEngine.SearchTargetProgress.Status.ERROR
            progress.exception shouldBe exception
        }

        @Test
        fun `SearchTargetProgress with COMPLETED status`() {
            val target = SearchTarget.Path(LocalPath.build("/sdcard"), enabled = true)
            val progress = SearchEngine.SearchTargetProgress(
                target = target,
                itemsScanned = 1000,
                resultsFound = 25,
                status = SearchEngine.SearchTargetProgress.Status.COMPLETED,
            )

            progress.status shouldBe SearchEngine.SearchTargetProgress.Status.COMPLETED
            progress.itemsScanned shouldBe 1000
            progress.resultsFound shouldBe 25
        }

        @Test
        fun `SearchTargetProgress with CANCELLED status`() {
            val target = SearchTarget.Path(LocalPath.build("/sdcard"), enabled = true)
            val progress = SearchEngine.SearchTargetProgress(
                target = target,
                itemsScanned = 500,
                resultsFound = 10,
                status = SearchEngine.SearchTargetProgress.Status.CANCELLED,
            )

            progress.status shouldBe SearchEngine.SearchTargetProgress.Status.CANCELLED
        }

        @Test
        fun `SearchTargetProgress defaults to zero errors`() {
            val target = SearchTarget.Path(LocalPath.build("/sdcard"), enabled = true)
            val progress = SearchEngine.SearchTargetProgress(
                target = target,
                itemsScanned = 10,
                resultsFound = 1,
                status = SearchEngine.SearchTargetProgress.Status.COMPLETED,
            )

            progress.errorCount shouldBe 0
            progress.firstErrorPath shouldBe null
        }

        @Test
        fun `SearchTargetProgress carries error count and first error path`() {
            val target = SearchTarget.Path(LocalPath.build("/sdcard"), enabled = true)
            val errorPath = LocalPath.build("/sdcard/Android/data")
            val progress = SearchEngine.SearchTargetProgress(
                target = target,
                itemsScanned = 10,
                resultsFound = 1,
                status = SearchEngine.SearchTargetProgress.Status.COMPLETED,
                errorCount = 3,
                firstErrorPath = errorPath,
            )

            progress.errorCount shouldBe 3
            progress.firstErrorPath shouldBe errorPath
        }
    }
}
