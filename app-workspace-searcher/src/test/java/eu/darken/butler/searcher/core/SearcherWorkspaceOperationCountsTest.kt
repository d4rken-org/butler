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
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.error.recordingIncidentStore

/**
 * A running search is work in flight even though no managed operation exists for it, so it has to
 * reach the same counters everything else reads - the tab markers, the manager's operations filter,
 * the global badge - rather than only the searcher's own pausability.
 */
class SearcherWorkspaceOperationCountsTest : BaseTest() {

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

    private fun createWorkspace(
        engineResults: List<SearchItem> = emptyList(),
        hangSearch: Boolean = false,
    ): SearcherWorkspace {
        val engine = mockk<SearchEngine> {
            every { targetState } returns MutableStateFlow(emptyList())
            every { setupRequirements } returns MutableStateFlow(PathRequirements())
            every { accessErrorRequirements } returns MutableStateFlow(PathRequirements())
            every { clearTargetProgress() } just Runs
            every { targetProgressState } returns MutableStateFlow(emptyList())
            if (hangSearch) {
                coEvery { search(any(), any()) } coAnswers { awaitCancellation() }
            } else {
                coEvery { search(any(), any()) } returns SearchEngine.Result.Success(
                    engineResults
                        .map { SearchBackend.BackendResult(it, SearchBackend.BackendResult.RANK_FILESYSTEM) }
                        .asFlow()
                )
            }
        }
        return SearcherWorkspace(
            id = Workspace.Id(),
            creationArguments = SearcherArguments.Default(),
            dispatcherProvider = TestDispatcherProvider(),
            issueHandler = mockk<IssueHandler>(),
            operationsManager = mockk<OperationsManager> {
                every { operations } returns MutableStateFlow(emptyList<ManagedOperation>())
            },
            deleteOperationFactory = mockk<DeleteOperation.Factory>(),
            searchEngineFactory = mockk<SearchEngine.Factory> { every { create(any(), any()) } returns engine },
            fileSystemHinter = FileSystemHinter(),
            folderPreviewResolver = mockk<FolderPreviewResolver>(relaxUnitFun = true),
            errorIncidentStore = recordingIncidentStore(),
        )
    }

    private fun searchCommand() = SearcherCommand.Search(
        filenameQuery = FilenameQuery(pattern = "test"),
        targets = emptyList(),
    )

    private fun awaitStatus(workspace: SearcherWorkspace, status: SearcherWorkspace.State.SearchStatus) = runBlocking {
        withTimeout(10_000) { workspace.state.first { it.searchStatus == status } }
    }

    private fun awaitInfo(workspace: SearcherWorkspace, predicate: (Workspace.Info) -> Boolean) = runBlocking {
        withTimeout(10_000) { workspace.info.first(predicate) }
    }

    @Test
    fun `a running search counts as running work`() {
        val workspace = createWorkspace(hangSearch = true)

        workspace.execute(searchCommand())
        awaitStatus(workspace, SearcherWorkspace.State.SearchStatus.SEARCHING)

        val info = awaitInfo(workspace) { it.operationCount == 1 }
        info.activeCount shouldBe 1
    }

    @Test
    fun `a finished search stops counting`() {
        val workspace = createWorkspace(listOf(item("/sdcard/a.txt")))

        workspace.execute(searchCommand())
        awaitStatus(workspace, SearcherWorkspace.State.SearchStatus.COMPLETED)

        val info = awaitInfo(workspace) { it.operationCount == 0 }
        info.activeCount shouldBe 0
    }

    /**
     * The counters and pausability are written by two collectors of the same flow, and only one of
     * them may see held results as busy: results are state that cannot survive a pause, not work.
     */
    @Test
    fun `results left over after a search still block pausing`() {
        val workspace = createWorkspace(listOf(item("/sdcard/a.txt")))

        workspace.execute(searchCommand())
        awaitStatus(workspace, SearcherWorkspace.State.SearchStatus.COMPLETED)

        val info = awaitInfo(workspace) { it.operationCount == 0 && !it.isPausable }
        info.activeCount shouldBe 0
    }
}
