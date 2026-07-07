package eu.darken.butler.searcher.core

import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.searcher.core.operations.DeleteOperation
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.SearcherArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider

class SearcherWorkspaceCapTest : BaseTest() {

    private fun mockItems(count: Int): List<SearchItem> = List(count) { mockk<SearchItem>() }

    private fun createWorkspace(engineResults: List<SearchItem>): SearcherWorkspace {
        val engine = mockk<SearchEngine> {
            every { targetState } returns MutableStateFlow(emptyList())
            every { setupRequirements } returns MutableStateFlow(PathRequirements())
            every { targetProgressState } returns MutableStateFlow(emptyList())
            coEvery { search(any(), any()) } returns SearchEngine.Result.Success(engineResults.asFlow())
        }
        val engineFactory = mockk<SearchEngine.Factory> {
            every { create(any(), any()) } returns engine
        }
        val operationsManager = mockk<OperationsManager> {
            every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
        }
        return SearcherWorkspace(
            id = Workspace.Id(),
            creationArguments = SearcherArguments.Default(),
            dispatcherProvider = TestDispatcherProvider(),
            issueHandler = mockk<IssueHandler>(),
            operationsManager = operationsManager,
            deleteOperationFactory = mockk<DeleteOperation.Factory>(),
            searchEngineFactory = engineFactory,
        )
    }

    private fun searchCommand(maxResults: Int?) = SearcherCommand.Search(
        filenameQuery = FilenameQuery(pattern = "test"),
        targets = emptyList(),
        options = SearchQuery.Options(maxResults = maxResults),
    )

    private fun awaitTerminalState(workspace: SearcherWorkspace): SearcherWorkspace.State = runBlocking {
        withTimeout(10_000) {
            workspace.state.first {
                it.searchStatus != SearcherWorkspace.State.SearchStatus.IDLE &&
                    it.searchStatus != SearcherWorkspace.State.SearchStatus.SEARCHING
            }
        }
    }

    @Test
    fun `reaching the result cap completes the search instead of cancelling it`() {
        val workspace = createWorkspace(mockItems(8))
        workspace.execute(searchCommand(maxResults = 5))

        val state = awaitTerminalState(workspace)

        state.searchStatus shouldBe SearcherWorkspace.State.SearchStatus.COMPLETED
        state.results.size shouldBe 5
        state.limitReached shouldBe true
    }

    @Test
    fun `exactly the cap many results is not reported as limit reached`() {
        val workspace = createWorkspace(mockItems(5))
        workspace.execute(searchCommand(maxResults = 5))

        val state = awaitTerminalState(workspace)

        state.searchStatus shouldBe SearcherWorkspace.State.SearchStatus.COMPLETED
        state.results.size shouldBe 5
        state.limitReached shouldBe false
    }

    @Test
    fun `results below the cap complete without limit flag`() {
        val workspace = createWorkspace(mockItems(3))
        workspace.execute(searchCommand(maxResults = 5))

        val state = awaitTerminalState(workspace)

        state.searchStatus shouldBe SearcherWorkspace.State.SearchStatus.COMPLETED
        state.results.size shouldBe 3
        state.limitReached shouldBe false
    }

    @Test
    fun `no cap streams everything`() {
        val workspace = createWorkspace(mockItems(7))
        workspace.execute(searchCommand(maxResults = null))

        val state = awaitTerminalState(workspace)

        state.searchStatus shouldBe SearcherWorkspace.State.SearchStatus.COMPLETED
        state.results.size shouldBe 7
        state.limitReached shouldBe false
    }
}
