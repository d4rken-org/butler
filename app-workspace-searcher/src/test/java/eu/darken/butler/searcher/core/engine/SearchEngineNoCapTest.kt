package eu.darken.butler.searcher.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearcherSettings
import eu.darken.butler.searcher.core.engine.backend.SearchBackend
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class SearchEngineNoCapTest : BaseTest() {

    private class StubBackend(private val items: List<SearchItem>) : SearchBackend {
        override val priority: Int = 0
        override fun canHandle(target: SearchTarget): Boolean = true
        override fun supports(condition: FilterCondition): Boolean = true
        override fun monitorRequirements(target: SearchTarget): Flow<PathRequirements> = flowOf(PathRequirements())
        override suspend fun scan(session: SearchBackend.ScanSession): Flow<SearchBackend.BackendResult> =
            items.map { SearchBackend.BackendResult(it, SearchBackend.BackendResult.RANK_FILESYSTEM) }.asFlow()
    }

    @Test
    fun `engine streams past maxResults and completes normally`(): Unit = runTest {
        val items = List(25) { mockk<SearchItem>() }
        val target = SearchTarget.Path.from(LocalPath.build("/sdcard"))
        val engine = SearchEngine(
            workspaceId = Workspace.Id(),
            workspaceScope = backgroundScope,
            backends = setOf(StubBackend(items)),
            dispatcherProvider = TestDispatcherProvider(),
            storageManager2 = mockk(),
            searcherSettings = mockk<SearcherSettings> {
                every { searchDefaultTargets } returns mockk {
                    every { flow } returns flowOf(listOf<SearchTarget>(target))
                }
                every { contentSearchBinaries } returns mockk {
                    every { flow } returns flowOf(false)
                }
            },
            pathPermissionCheck = mockk {
                every { monitor(any<Collection<APath<*>>>()) } returns flowOf(PathRequirements())
            },
        )

        val result = engine.search(
            SearcherCommand.Search(
                filenameQuery = FilenameQuery(pattern = "x"),
                targets = listOf(target),
                // The engine must IGNORE the cap: stopping at the limit is the collector's job,
                // implemented as a normal flow completion instead of a cancellation
                options = SearchQuery.Options(maxResults = 10),
            )
        )

        result.shouldBeInstanceOf<SearchEngine.Result.Success>()
        val collected = result.results.toList()
        collected.size shouldBe 25
    }
}
