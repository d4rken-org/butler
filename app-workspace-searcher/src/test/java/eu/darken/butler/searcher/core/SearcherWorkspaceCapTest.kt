package eu.darken.butler.searcher.core

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.searcher.core.engine.backend.SearchBackend
import eu.darken.butler.searcher.core.operations.DeleteOperation
import eu.darken.butler.searcher.core.operations.SearcherCommand
import eu.darken.butler.workspace.contracts.searcher.FilenameQuery
import eu.darken.butler.workspace.contracts.searcher.SearcherArguments
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.preview.FolderPreviewResolver
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.OperationsManager
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.error.recordingIncidentStore

class SearcherWorkspaceCapTest : BaseTest() {

    private fun item(path: String): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = null,
            target = null,
        ),
        matchedQuery = "",
    )

    private fun mockItems(count: Int): List<SearchItem> = List(count) { item("/sdcard/file_$it.txt") }

    private fun createWorkspace(engineResults: List<SearchItem>): SearcherWorkspace {
        val engine = mockk<SearchEngine> {
            every { targetState } returns MutableStateFlow(emptyList())
            every { setupRequirements } returns MutableStateFlow(PathRequirements())
            every { accessErrorRequirements } returns MutableStateFlow(PathRequirements())
            every { clearTargetProgress() } just Runs
            every { targetProgressState } returns MutableStateFlow(emptyList())
            coEvery { search(any(), any()) } returns SearchEngine.Result.Success(
                engineResults
                    .map { SearchBackend.BackendResult(it, SearchBackend.BackendResult.RANK_FILESYSTEM) }
                    .asFlow()
            )
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
            fileSystemHinter = FileSystemHinter(),
            folderPreviewResolver = mockk<FolderPreviewResolver>(relaxUnitFun = true),
            errorIncidentStore = recordingIncidentStore(),
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

    @Test
    fun `duplicates from overlapping targets do not consume the cap`() {
        // 7 emissions but only 4 unique paths: well below the cap of 5
        val unique = List(4) { item("/sdcard/overlap_$it.txt") }
        val stream = listOf(
            unique[0],
            item("/sdcard/overlap_0.txt"),
            unique[1],
            item("/sdcard/overlap_1.txt"),
            unique[2],
            item("/sdcard/overlap_2.txt"),
            unique[3],
        )
        val workspace = createWorkspace(stream)
        workspace.execute(searchCommand(maxResults = 5))

        val state = awaitTerminalState(workspace)

        state.searchStatus shouldBe SearcherWorkspace.State.SearchStatus.COMPLETED
        state.limitReached shouldBe false
        state.results.map { it.path.path } shouldContainExactly unique.map { it.path.path }
    }

    @Test
    fun `limit is computed on unique results and displayed results stay unique`() {
        // 9 emissions, 6 unique paths, cap 5: duplicates must not count toward the cap,
        // but the sixth unique item must still trip it
        val unique = List(6) { item("/sdcard/unique_$it.txt") }
        val stream = listOf(
            unique[0],
            item("/sdcard/unique_0.txt"),
            unique[1],
            item("/sdcard/unique_1.txt"),
            unique[2],
            unique[3],
            item("/sdcard/unique_0.txt"),
            unique[4],
            unique[5],
        )
        val workspace = createWorkspace(stream)
        workspace.execute(searchCommand(maxResults = 5))

        val state = awaitTerminalState(workspace)

        state.searchStatus shouldBe SearcherWorkspace.State.SearchStatus.COMPLETED
        state.limitReached shouldBe true
        state.results.size shouldBe 5
        state.results.map { it.path.path } shouldContainExactly unique.take(5).map { it.path.path }
    }
}
