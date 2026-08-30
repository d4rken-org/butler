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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.error.recordingIncidentStore
import java.io.File
import kotlin.time.Instant

class SearcherWorkspaceLiveUpdateTest : BaseTest() {

    private val root = LocalPath.build(File("/tmp/search-root"))
    private val hinter = FileSystemHinter()

    private fun lookup(relative: String, fileType: FileType = FileType.FILE): LocalPathLookup = LocalPathLookup(
        lookedUp = root.child(*relative.split('/').toTypedArray()),
        fileType = fileType,
        size = 42L,
        modifiedAt = Instant.DISTANT_PAST,
    )

    private fun fileItem(relative: String): SearchItem =
        SearchItem.RegularFile(lookup = lookup(relative), matchedQuery = "q")

    private fun dirItem(relative: String): SearchItem =
        SearchItem.RegularDirectory(lookup = lookup(relative, FileType.DIRECTORY), matchedQuery = "q")

    private fun createWorkspace(
        engineFlows: List<kotlinx.coroutines.flow.Flow<SearchItem>>,
        resolver: FolderPreviewResolver = mockk(relaxUnitFun = true),
    ): SearcherWorkspace {
        var searchCount = 0
        val engine = mockk<SearchEngine> {
            every { targetState } returns MutableStateFlow(listOf(SearchTarget.Path(root)))
            every { setupRequirements } returns MutableStateFlow(PathRequirements())
            every { accessErrorRequirements } returns MutableStateFlow(PathRequirements())
            every { clearTargetProgress() } just Runs
            every { targetProgressState } returns MutableStateFlow(emptyList())
            coEvery { search(any(), any()) } answers {
                SearchEngine.Result.Success(
                    engineFlows[minOf(searchCount++, engineFlows.size - 1)]
                        .map { SearchBackend.BackendResult(it, SearchBackend.BackendResult.RANK_FILESYSTEM) }
                )
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
            folderPreviewResolver = resolver,
            errorIncidentStore = recordingIncidentStore(),
        )
    }

    private fun search() = SearcherCommand.Search(
        filenameQuery = FilenameQuery(pattern = "q"),
        targets = listOf(SearchTarget.Path(root)),
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
    fun `removed event prunes result and descendants after completion`(): Unit = runBlocking {
        val items = listOf(fileItem("keep.txt"), fileItem("gone.txt"), fileItem("doomed/nested.txt"))
        val workspace = createWorkspace(listOf(items.asFlow()))
        workspace.execute(search())
        awaitTerminal(workspace)

        hinter.trackPathsRemoved(mockk<Operation.Id>(), listOf(lookup("gone.txt")))
        hinter.trackPathsRemoved(mockk<Operation.Id>(), listOf(lookup("doomed", FileType.DIRECTORY)))

        withTimeout(10_000) {
            workspace.state.first { state -> state.results.map { it.lookup.name } == listOf("keep.txt") }
        }
    }

    @Test
    fun `removed events outside the search targets are ignored`(): Unit = runBlocking {
        val items = listOf(fileItem("keep.txt"))
        val workspace = createWorkspace(listOf(items.asFlow()))
        workspace.execute(search())
        awaitTerminal(workspace)

        val unrelated = LocalPathLookup(
            lookedUp = LocalPath.build(File("/tmp/unrelated/file.txt")),
            fileType = FileType.FILE,
            size = 1L,
            modifiedAt = Instant.DISTANT_PAST,
        )
        hinter.trackPathsRemoved(mockk<Operation.Id>(), listOf(unrelated))

        awaitTerminal(workspace).results.size shouldBe 1
    }

    @Test
    fun `tombstones reset on a new search`(): Unit = runBlocking {
        val items = listOf(fileItem("restored.txt"))
        val workspace = createWorkspace(listOf(items.asFlow(), items.asFlow()))
        workspace.execute(search())
        awaitTerminal(workspace)

        hinter.trackPathsRemoved(mockk<Operation.Id>(), listOf(lookup("restored.txt")))
        withTimeout(10_000) {
            workspace.state.first { it.results.isEmpty() }
        }

        // A fresh search that finds the recreated path shows it again
        workspace.execute(search())
        val state = awaitTerminal(workspace)
        state.results.map { it.lookup.name } shouldBe listOf("restored.txt")
    }

    @Test
    fun `removed event during an in-flight search tombstones the result`(): Unit = runBlocking {
        val releaseSecondBatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engineFlow = flow {
            emit(fileItem("early.txt"))
            emit(fileItem("victim.txt"))
            releaseSecondBatch.await()
            emit(fileItem("late.txt"))
        }
        val workspace = createWorkspace(listOf(engineFlow))
        workspace.execute(search())

        // Wait until the first batch is visible mid-search
        withTimeout(10_000) {
            workspace.state.first { state -> state.results.any { it.lookup.name == "victim.txt" } }
        }

        hinter.trackPathsRemoved(mockk<Operation.Id>(), listOf(lookup("victim.txt")))
        withTimeout(10_000) {
            workspace.state.first { state ->
                state.searchStatus == SearcherWorkspace.State.SearchStatus.SEARCHING &&
                    state.results.none { it.lookup.name == "victim.txt" }
            }
        }

        releaseSecondBatch.complete(Unit)
        val terminal = awaitTerminal(workspace)

        terminal.searchStatus shouldBe SearcherWorkspace.State.SearchStatus.COMPLETED
        terminal.results.map { it.lookup.name }.sorted() shouldBe listOf("early.txt", "late.txt")
    }

    @Test
    fun `cancelled search does not write its status into the replacing search`(): Unit = runBlocking {
        val hangingFlow = flow<SearchItem> {
            emit(fileItem("partial.txt"))
            awaitCancellation()
        }
        val quickFlow = listOf(fileItem("fresh.txt")).asFlow()
        val workspace = createWorkspace(listOf(hangingFlow, quickFlow))

        workspace.execute(search())
        runBlocking {
            withTimeout(10_000) {
                workspace.state.first { it.searchStatus == SearcherWorkspace.State.SearchStatus.SEARCHING }
            }
        }

        workspace.execute(search())
        val state = awaitTerminal(workspace)

        state.searchStatus shouldBe SearcherWorkspace.State.SearchStatus.COMPLETED
        state.results.map { it.lookup.name } shouldBe listOf("fresh.txt")
    }

    @Test
    fun `directory results invalidate their collage cache per batch`(): Unit = runBlocking {
        val resolver = mockk<FolderPreviewResolver>(relaxUnitFun = true)
        val dir = dirItem("photos")
        val workspace = createWorkspace(listOf(listOf(dir, fileItem("a.txt")).asFlow()), resolver = resolver)

        workspace.execute(search())
        awaitTerminal(workspace)

        io.mockk.verify { resolver.invalidateDirs(match { dir.path in it }) }
    }
}
