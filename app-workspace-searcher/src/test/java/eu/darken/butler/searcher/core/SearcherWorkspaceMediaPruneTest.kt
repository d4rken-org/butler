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
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.error.recordingIncidentStore

/** Live pruning for MediaStore-only searches: no Path target scopes the removal events. */
class SearcherWorkspaceMediaPruneTest : BaseTest() {

    private val hinter = FileSystemHinter()
    private val mediaTarget = SearchTarget.MediaStore(SearchTarget.MediaStore.Collection.IMAGES)

    private fun lookup(path: String, fileType: FileType = FileType.FILE) = LocalPathLookup(
        lookedUp = LocalPath.build(path),
        fileType = fileType,
        size = 1L,
        modifiedAt = null,
    )

    private fun indexItem(path: String) = SearchBackend.BackendResult(
        SearchItem.fromLookup(lookup = lookup(path), matchedQuery = "q"),
        SearchBackend.BackendResult.RANK_INDEX,
    )

    private fun createWorkspace(engineFlows: List<Flow<SearchBackend.BackendResult>>): SearcherWorkspace {
        var searchCount = 0
        val engine = mockk<SearchEngine> {
            every { targetState } returns MutableStateFlow(listOf<SearchTarget>(mediaTarget))
            every { setupRequirements } returns MutableStateFlow(PathRequirements())
            every { accessErrorRequirements } returns MutableStateFlow(PathRequirements())
            every { clearTargetProgress() } just Runs
            every { targetProgressState } returns MutableStateFlow(emptyList())
            coEvery { search(any(), any()) } answers {
                SearchEngine.Result.Success(engineFlows[minOf(searchCount++, engineFlows.size - 1)])
            }
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
            fileSystemHinter = hinter,
            folderPreviewResolver = mockk<FolderPreviewResolver>(relaxUnitFun = true),
            errorIncidentStore = recordingIncidentStore(),
        )
    }

    private fun search() = SearcherCommand.Search(
        filenameQuery = FilenameQuery(pattern = "q"),
        targets = listOf(mediaTarget),
        options = SearchQuery.Options(maxResults = null),
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
    fun `removing a local file prunes a completed media-only search`(): Unit = runBlocking {
        val items = listOf(
            indexItem("/storage/emulated/0/DCIM/keep.jpg"),
            indexItem("/storage/emulated/0/DCIM/gone.jpg"),
        )
        val workspace = createWorkspace(listOf(items.asFlow()))
        workspace.execute(search())
        awaitTerminal(workspace)

        hinter.trackPathsRemoved(mockk<Operation.Id>(), listOf(lookup("/storage/emulated/0/DCIM/gone.jpg")))

        withTimeout(10_000) {
            workspace.state.first { state -> state.results.map { it.lookup.name } == listOf("keep.jpg") }
        }
    }

    @Test
    fun `directory removal prunes media descendants`(): Unit = runBlocking {
        val items = listOf(
            indexItem("/storage/emulated/0/DCIM/doomed/nested.jpg"),
            indexItem("/storage/emulated/0/Pictures/keep.jpg"),
        )
        val workspace = createWorkspace(listOf(items.asFlow()))
        workspace.execute(search())
        awaitTerminal(workspace)

        hinter.trackPathsRemoved(
            mockk<Operation.Id>(),
            listOf(lookup("/storage/emulated/0/DCIM/doomed", FileType.DIRECTORY)),
        )

        withTimeout(10_000) {
            workspace.state.first { state -> state.results.map { it.lookup.name } == listOf("keep.jpg") }
        }
    }

    @Test
    fun `alias-spelled removal prunes the canonical media result`(): Unit = runBlocking {
        val items = listOf(indexItem("/storage/emulated/0/DCIM/gone.jpg"))
        val workspace = createWorkspace(listOf(items.asFlow()))
        workspace.execute(search())
        awaitTerminal(workspace)

        // Deleted via an alias path (e.g. an /sdcard-based Explorer view of the same file)
        hinter.trackPathsRemoved(mockk<Operation.Id>(), listOf(lookup("/sdcard/DCIM/gone.jpg")))

        withTimeout(10_000) {
            workspace.state.first { it.results.isEmpty() }
        }
    }

    @Test
    fun `removal during an in-flight media search tombstones the result`(): Unit = runBlocking {
        val releaseTail = CompletableDeferred<Unit>()
        val engineFlow = flow {
            emit(indexItem("/storage/emulated/0/DCIM/victim.jpg"))
            emit(indexItem("/storage/emulated/0/DCIM/early.jpg"))
            releaseTail.await()
            emit(indexItem("/storage/emulated/0/DCIM/late.jpg"))
        }
        val workspace = createWorkspace(listOf(engineFlow))
        workspace.execute(search())

        withTimeout(10_000) {
            workspace.state.first { state -> state.results.any { it.lookup.name == "victim.jpg" } }
        }

        hinter.trackPathsRemoved(mockk<Operation.Id>(), listOf(lookup("/storage/emulated/0/DCIM/victim.jpg")))
        withTimeout(10_000) {
            workspace.state.first { state -> state.results.none { it.lookup.name == "victim.jpg" } }
        }

        releaseTail.complete(Unit)
        val state = awaitTerminal(workspace)
        state.searchStatus shouldBe SearcherWorkspace.State.SearchStatus.COMPLETED
        state.results.map { it.lookup.name }.sorted() shouldBe listOf("early.jpg", "late.jpg")
    }
}
