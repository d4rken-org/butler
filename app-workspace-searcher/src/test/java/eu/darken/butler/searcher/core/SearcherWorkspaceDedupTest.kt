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
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.ManagedOperation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.preview.FolderPreviewResolver
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.error.recordingIncidentStore

class SearcherWorkspaceDedupTest : BaseTest() {

    private fun item(path: String, size: Long?): SearchItem = SearchItem.fromLookup(
        lookup = LocalPathLookup(
            lookedUp = LocalPath.build(path),
            fileType = FileType.FILE,
            size = size,
            modifiedAt = null,
        ),
        matchedQuery = "q",
    )

    private fun fs(path: String, size: Long? = 1L) =
        SearchBackend.BackendResult(item(path, size), SearchBackend.BackendResult.RANK_FILESYSTEM)

    private fun index(path: String, size: Long? = 1L) =
        SearchBackend.BackendResult(item(path, size), SearchBackend.BackendResult.RANK_INDEX)

    private fun createWorkspace(engineResults: Flow<SearchBackend.BackendResult>): SearcherWorkspace {
        val engine = mockk<SearchEngine> {
            every { targetState } returns MutableStateFlow(emptyList())
            every { setupRequirements } returns MutableStateFlow(PathRequirements())
            every { accessErrorRequirements } returns MutableStateFlow(PathRequirements())
            every { clearTargetProgress() } just Runs
            every { targetProgressState } returns MutableStateFlow(emptyList())
            coEvery { search(any(), any()) } returns SearchEngine.Result.Success(engineResults)
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

    private fun searchCommand(maxResults: Int? = null) = SearcherCommand.Search(
        filenameQuery = FilenameQuery(pattern = "q"),
        targets = emptyList(),
        options = SearchQuery.Options(maxResults = maxResults),
    )

    private fun awaitTerminal(workspace: SearcherWorkspace): SearcherWorkspace.State = runBlocking {
        withTimeout(10_000) {
            workspace.state.first {
                it.searchStatus != SearcherWorkspace.State.SearchStatus.IDLE &&
                    it.searchStatus != SearcherWorkspace.State.SearchStatus.SEARCHING
            }
        }
    }

    @Test
    fun `filesystem result replaces an earlier index result in place`() {
        val workspace = createWorkspace(
            listOf(
                index("/storage/emulated/0/a.jpg", size = 100L),
                fs("/storage/emulated/0/b.jpg", size = 1L),
                fs("/storage/emulated/0/a.jpg", size = 200L),
            ).asFlow()
        )

        workspace.search(searchCommand())
        val state = awaitTerminal(workspace)

        state.searchStatus shouldBe SearcherWorkspace.State.SearchStatus.COMPLETED
        state.results.map { it.path.path } shouldContainExactly
            listOf("/storage/emulated/0/a.jpg", "/storage/emulated/0/b.jpg")
        // Position preserved, filesystem metadata won
        state.results[0].size shouldBe 200L
    }

    @Test
    fun `index duplicate arriving after a filesystem result is dropped`() {
        val workspace = createWorkspace(
            listOf(
                fs("/storage/emulated/0/a.jpg", size = 200L),
                index("/storage/emulated/0/a.jpg", size = 100L),
            ).asFlow()
        )

        workspace.search(searchCommand())
        val state = awaitTerminal(workspace)

        state.results.map { it.size } shouldContainExactly listOf(200L)
    }

    @Test
    fun `alias spellings from different sources deduplicate`() {
        val workspace = createWorkspace(
            listOf(
                index("/storage/emulated/0/DCIM/a.jpg", size = 100L),
                fs("/sdcard/DCIM/a.jpg", size = 200L),
            ).asFlow()
        )

        workspace.search(searchCommand())
        val state = awaitTerminal(workspace)

        state.results.size shouldBe 1
        state.results.single().size shouldBe 200L
    }

    @Test
    fun `replacements do not consume the result cap`() {
        val workspace = createWorkspace(
            listOf(
                index("/storage/emulated/0/a.jpg", size = 100L),
                fs("/storage/emulated/0/a.jpg", size = 200L),
                fs("/storage/emulated/0/b.jpg"),
            ).asFlow()
        )

        workspace.search(searchCommand(maxResults = 2))
        val state = awaitTerminal(workspace)

        state.searchStatus shouldBe SearcherWorkspace.State.SearchStatus.COMPLETED
        state.limitReached shouldBe false
        state.results.size shouldBe 2
    }

    @Test
    fun `cap keeps whatever source won before cancellation - best effort by design`() {
        // The sentinel (c.jpg) ends collection before the filesystem duplicate of a.jpg arrives:
        // the index-sourced item stays. This pins the documented best-effort boundary.
        val workspace = createWorkspace(
            listOf(
                index("/storage/emulated/0/a.jpg", size = 100L),
                index("/storage/emulated/0/b.jpg"),
                index("/storage/emulated/0/c.jpg"),
                fs("/storage/emulated/0/a.jpg", size = 200L),
            ).asFlow()
        )

        workspace.search(searchCommand(maxResults = 2))
        val state = awaitTerminal(workspace)

        state.limitReached shouldBe true
        state.results.map { it.path.path } shouldContainExactly
            listOf("/storage/emulated/0/a.jpg", "/storage/emulated/0/b.jpg")
        state.results[0].size shouldBe 100L
    }
}
