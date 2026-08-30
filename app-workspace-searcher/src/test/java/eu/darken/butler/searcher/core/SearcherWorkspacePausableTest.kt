package eu.darken.butler.searcher.core

import android.content.Context
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
import eu.darken.butler.workspace.core.operations.Operation
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
import kotlin.time.Clock

/**
 * [Workspace.Info.isPausable] for the searcher: [SearcherWorkspace.createArguments] always persists
 * `startSearch = false` and never carries results, so neither a running search nor a populated
 * result set survives being paused.
 */
class SearcherWorkspacePausableTest : BaseTest() {

    // Never touched: the tab identity under test is a direct string, not a resource lookup
    private val context: Context = mockk()

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
        operations: MutableStateFlow<List<ManagedOperation>> = MutableStateFlow(emptyList()),
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
        val engineFactory = mockk<SearchEngine.Factory> {
            every { create(any(), any()) } returns engine
        }
        val operationsManager = mockk<OperationsManager> {
            every { this@mockk.operations } returns operations
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

    private fun queuedOperation(workspaceId: Workspace.Id): ManagedOperation = mockk {
        every { id } returns Operation.Id()
        every { state } returns MutableStateFlow(Operation.State.Queued(startedAt = Clock.System.now()))
        every { metadata } returns mockk {
            every { origin } returns Operation.Metadata.Origin.Searcher(workspaceId)
        }
    }

    private fun searchCommand() = SearcherCommand.Search(
        filenameQuery = FilenameQuery(pattern = "test"),
        targets = emptyList(),
    )

    private fun awaitStatus(workspace: SearcherWorkspace, status: SearcherWorkspace.State.SearchStatus) = runBlocking {
        withTimeout(10_000) { workspace.state.first { it.searchStatus == status } }
    }

    @Test
    fun `a searcher that never ran can be paused`() {
        createWorkspace().info.value.isPausable shouldBe true
    }

    @Test
    fun `a running search blocks pausing`() {
        val workspace = createWorkspace(hangSearch = true)

        workspace.execute(searchCommand())
        awaitStatus(workspace, SearcherWorkspace.State.SearchStatus.SEARCHING)

        workspace.info.value.isPausable shouldBe false
    }

    @Test
    fun `holding results blocks pausing`() {
        val workspace = createWorkspace(listOf(item("/sdcard/a.txt"), item("/sdcard/b.txt")))

        workspace.execute(searchCommand())
        awaitStatus(workspace, SearcherWorkspace.State.SearchStatus.COMPLETED)

        workspace.info.value.isPausable shouldBe false
    }

    @Test
    fun `the other info writers cannot clobber pausability`() {
        val operations = MutableStateFlow<List<ManagedOperation>>(emptyList())
        val workspace = createWorkspace(listOf(item("/sdcard/a.txt")), operations = operations)

        // Drives the identity writer: publishIdentity() republishes the query as the tab title
        workspace.execute(searchCommand())
        awaitStatus(workspace, SearcherWorkspace.State.SearchStatus.COMPLETED)
        workspace.info.value.isPausable shouldBe false

        // Drives the operation-count writer while the results are still held
        operations.value = listOf(queuedOperation(workspace.id))
        runBlocking { withTimeout(10_000) { workspace.info.first { it.operationCount == 1 } } }

        workspace.info.value.isPausable shouldBe false
        workspace.info.value.title.get(context) shouldBe "test"
    }

    @Test
    fun `a finished search without results can be paused`() {
        val workspace = createWorkspace()

        workspace.execute(searchCommand())
        awaitStatus(workspace, SearcherWorkspace.State.SearchStatus.COMPLETED)

        workspace.info.value.isPausable shouldBe true
    }
}
