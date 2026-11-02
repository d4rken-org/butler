package eu.darken.butler.searcher.core

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.engine.SearchEngine
import eu.darken.butler.searcher.core.engine.backend.SearchBackend
import eu.darken.butler.searcher.core.operations.DeleteOperation
import eu.darken.butler.searcher.core.operations.RenameOperation
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
import io.kotest.matchers.collections.shouldContain
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.error.recordingIncidentStore
import java.io.File
import kotlin.time.Instant

class SearcherWorkspaceRenameTest : BaseTest() {

    private val defaultRoot = LocalPath.build(File("/tmp/search-root"))
    private val hinter = FileSystemHinter()
    private val gatewaySwitch = mockk<GatewaySwitch>()

    /** Every rename the workspaces handed to an operation, with the listener the operation calls. */
    private val renames = MutableStateFlow<List<Pair<SearcherCommand.Rename, RenameOperation.MoveListener>>>(
        emptyList()
    )

    private fun LocalPath.at(relative: String): LocalPath = child(*relative.split('/').toTypedArray())

    @Suppress("UNCHECKED_CAST")
    private fun lookup(path: LocalPath, fileType: FileType = FileType.FILE, size: Long = 42L): APathLookup<APath<*>> =
        LocalPathLookup(
            lookedUp = path,
            fileType = fileType,
            size = size,
            modifiedAt = Instant.DISTANT_PAST,
        ) as APathLookup<APath<*>>

    private val matchContext = SearchItem.MatchContext(
        matchType = SearchItem.MatchContext.MatchType.CONTENT,
        lineNumber = 3,
        matchedLine = "the quick fox",
        startIndex = 4,
        endIndex = 9,
    )

    private fun fileItem(path: LocalPath, context: SearchItem.MatchContext? = null): SearchItem =
        SearchItem.RegularFile(lookup = lookup(path), matchedQuery = "q", matchContext = context)

    private fun dirItem(path: LocalPath): SearchItem =
        SearchItem.RegularDirectory(lookup = lookup(path, FileType.DIRECTORY), matchedQuery = "q")

    private fun createWorkspace(
        engineFlows: List<Flow<SearchItem>>,
        root: LocalPath = defaultRoot,
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
            coEvery { submit(any()) } returns Operation.Id()
        }
        val renameFactory = mockk<RenameOperation.Factory> {
            every { create(any(), any(), any()) } answers {
                renames.update { it + (secondArg<SearcherCommand.Rename>() to thirdArg<RenameOperation.MoveListener>()) }
                mockk<RenameOperation>()
            }
        }
        return SearcherWorkspace(
            id = Workspace.Id(),
            creationArguments = SearcherArguments.Default(),
            dispatcherProvider = TestDispatcherProvider(),
            issueHandler = mockk<IssueHandler>(),
            operationsManager = operationsManager,
            deleteOperationFactory = mockk<DeleteOperation.Factory>(),
            renameOperationFactory = renameFactory,
            searchEngineFactory = engineFactory,
            fileSystemHinter = hinter,
            folderPreviewResolver = mockk(relaxUnitFun = true),
            errorIncidentStore = recordingIncidentStore(),
            gatewaySwitch = gatewaySwitch,
        )
    }

    private fun search(root: LocalPath = defaultRoot) = SearcherCommand.Search(
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

    private fun searched(items: List<SearchItem>, root: LocalPath = defaultRoot): SearcherWorkspace =
        createWorkspace(listOf(items.asFlow()), root).also {
            it.execute(search(root))
            awaitTerminal(it)
        }

    /** Requests the rename and hands back the listener its operation would call. */
    private suspend fun SearcherWorkspace.requestRename(
        source: SearchItem,
        newName: String,
    ): RenameOperation.MoveListener {
        val before = renames.value.size
        execute(SearcherCommand.Rename(source = source, newName = newName))
        return withTimeout(10_000) { renames.first { it.size > before } }.last().second
    }

    private suspend fun SearcherWorkspace.results(): List<SearchItem> = state.first().results

    private suspend fun SearcherWorkspace.paths(): List<APath<*>> = results().map { it.path }

    @Test
    fun `an idle rename replaces the row and keeps its match`(): Unit = runBlocking {
        val renamed = fileItem(defaultRoot.at("notes.txt"), matchContext)
        val other = fileItem(defaultRoot.at("other.txt"))
        val workspace = searched(listOf(renamed, other))

        val listener = workspace.requestRename(renamed, "journal.txt")
        listener.onMoved(
            operationId = Operation.Id(),
            moved = setOf(lookup(defaultRoot.at("notes.txt")) to lookup(defaultRoot.at("journal.txt"))),
            skipped = emptySet(),
        )

        val results = workspace.results()
        results.map { it.path } shouldBe listOf(defaultRoot.at("journal.txt"), defaultRoot.at("other.txt"))
        results[0].name shouldBe "journal.txt"
        results[0].matchedQuery shouldBe "q"
        results[0].matchContext shouldBe matchContext
        results[1] shouldBe other
    }

    @Test
    fun `a conflict resolved to another name shows that name`(): Unit = runBlocking {
        val renamed = fileItem(defaultRoot.at("notes.txt"))
        val workspace = searched(listOf(renamed))

        val listener = workspace.requestRename(renamed, "journal.txt")
        listener.onMoved(
            operationId = Operation.Id(),
            moved = setOf(lookup(defaultRoot.at("notes.txt")) to lookup(defaultRoot.at("journal (1).txt"))),
            skipped = emptySet(),
        )

        workspace.paths() shouldBe listOf(defaultRoot.at("journal (1).txt"))
    }

    @Test
    fun `renaming onto an existing result leaves one row with the renamed item, source first`(): Unit = runBlocking {
        val first = fileItem(defaultRoot.at("a.txt"))
        val renamed = fileItem(defaultRoot.at("notes.txt"), matchContext)
        val middle = fileItem(defaultRoot.at("m.txt"))
        val existing = fileItem(defaultRoot.at("journal.txt"))
        val workspace = searched(listOf(first, renamed, middle, existing))

        val listener = workspace.requestRename(renamed, "journal.txt")
        listener.onMoved(
            operationId = Operation.Id(),
            moved = setOf(lookup(defaultRoot.at("notes.txt")) to lookup(defaultRoot.at("journal.txt"), size = 7L)),
            skipped = emptySet(),
        )

        val results = workspace.results()
        results.map { it.path } shouldBe listOf(
            defaultRoot.at("a.txt"),
            defaultRoot.at("journal.txt"),
            defaultRoot.at("m.txt"),
        )
        results[1].size shouldBe 7L
        results[1].matchContext shouldBe matchContext
    }

    @Test
    fun `renaming onto an existing result leaves one row with the renamed item, destination first`(): Unit =
        runBlocking {
            val existing = fileItem(defaultRoot.at("journal.txt"))
            val middle = fileItem(defaultRoot.at("m.txt"))
            val renamed = fileItem(defaultRoot.at("notes.txt"), matchContext)
            val workspace = searched(listOf(existing, middle, renamed))

            val listener = workspace.requestRename(renamed, "journal.txt")
            listener.onMoved(
                operationId = Operation.Id(),
                moved = setOf(lookup(defaultRoot.at("notes.txt")) to lookup(defaultRoot.at("journal.txt"), size = 7L)),
                skipped = emptySet(),
            )

            val results = workspace.results()
            results.map { it.path } shouldBe listOf(defaultRoot.at("m.txt"), defaultRoot.at("journal.txt"))
            results[1].size shouldBe 7L
            results[1].matchContext shouldBe matchContext
        }

    @Test
    fun `nothing moved leaves the results unchanged`(): Unit = runBlocking {
        val renamed = fileItem(defaultRoot.at("notes.txt"))
        val other = fileItem(defaultRoot.at("other.txt"))
        val workspace = searched(listOf(renamed, other))

        val listener = workspace.requestRename(renamed, "journal.txt")
        listener.onMoved(
            operationId = Operation.Id(),
            moved = emptySet(),
            skipped = setOf(lookup(defaultRoot.at("notes.txt"))),
        )

        workspace.results() shouldBe listOf(renamed, other)
    }

    @Test
    fun `a folder rename carries its descendants along`(): Unit = runBlocking {
        val folder = dirItem(defaultRoot.at("docs"))
        val explicit = fileItem(defaultRoot.at("docs/explicit.txt"))
        val skipped = fileItem(defaultRoot.at("docs/skipped.txt"))
        val rebased = fileItem(defaultRoot.at("docs/sub/rebased.txt"), matchContext)
        val gone = fileItem(defaultRoot.at("docs/gone.txt"))
        val outside = fileItem(defaultRoot.at("other.txt"))
        val workspace = searched(listOf(folder, explicit, skipped, rebased, gone, outside))

        coEvery { gatewaySwitch.lookup(any(), any()) } answers {
            val path = firstArg<APath<*>>()
            if (path == defaultRoot.at("papers/sub/rebased.txt")) {
                lookup(defaultRoot.at("papers/sub/rebased.txt"), size = 5L)
            } else {
                throw ReadException(path = path)
            }
        }

        val listener = workspace.requestRename(folder, "papers")
        listener.onMoved(
            operationId = Operation.Id(),
            moved = setOf(
                lookup(defaultRoot.at("docs"), FileType.DIRECTORY) to
                    lookup(defaultRoot.at("papers"), FileType.DIRECTORY),
                lookup(defaultRoot.at("docs/explicit.txt")) to lookup(defaultRoot.at("papers/explicit.txt"), size = 9L),
            ),
            skipped = setOf(lookup(defaultRoot.at("docs/skipped.txt"))),
        )

        val results = workspace.results()
        results.map { it.path } shouldBe listOf(
            defaultRoot.at("papers"),
            defaultRoot.at("papers/explicit.txt"),
            defaultRoot.at("docs/skipped.txt"),
            defaultRoot.at("papers/sub/rebased.txt"),
            defaultRoot.at("other.txt"),
        )
        results[0].fileType shouldBe FileType.DIRECTORY
        results[1].size shouldBe 9L
        results[2] shouldBe skipped
        results[3].size shouldBe 5L
        results[3].matchContext shouldBe matchContext
        results[4] shouldBe outside
    }

    @Test
    fun `a clear before the rename lands keeps the cleared state`(): Unit = runBlocking {
        val renamed = fileItem(defaultRoot.at("notes.txt"))
        val workspace = searched(listOf(renamed))

        val listener = workspace.requestRename(renamed, "journal.txt")
        workspace.execute(SearcherCommand.Clear)
        listener.onMoved(
            operationId = Operation.Id(),
            moved = setOf(lookup(defaultRoot.at("notes.txt")) to lookup(defaultRoot.at("journal.txt"))),
            skipped = emptySet(),
        )

        workspace.results() shouldBe emptyList()
    }

    @Test
    fun `a new search before the rename lands keeps its own results`(): Unit = runBlocking {
        val renamed = fileItem(defaultRoot.at("notes.txt"))
        val fresh = listOf(fileItem(defaultRoot.at("notes.txt")), fileItem(defaultRoot.at("fresh.txt")))
        val workspace = createWorkspace(listOf(listOf(renamed).asFlow(), fresh.asFlow()))
        workspace.execute(search())
        awaitTerminal(workspace)

        val listener = workspace.requestRename(renamed, "journal.txt")
        workspace.execute(search())
        withTimeout(10_000) {
            workspace.state.first {
                it.searchStatus == SearcherWorkspace.State.SearchStatus.COMPLETED && it.results.size == 2
            }
        }
        listener.onMoved(
            operationId = Operation.Id(),
            moved = setOf(lookup(defaultRoot.at("notes.txt")) to lookup(defaultRoot.at("journal.txt"))),
            skipped = emptySet(),
        )

        workspace.results() shouldBe fresh
    }

    @Test
    fun `another tab prunes the renamed source on the removed event`(): Unit = runBlocking {
        val renamed = fileItem(defaultRoot.at("notes.txt"))
        val other = fileItem(defaultRoot.at("other.txt"))
        val renaming = searched(listOf(renamed, other))
        val bystander = searched(listOf(renamed, other))

        val listener = renaming.requestRename(renamed, "journal.txt")
        val moved = setOf(lookup(defaultRoot.at("notes.txt")) to lookup(defaultRoot.at("journal.txt")))
        val operationId = Operation.Id()
        listener.onMoved(operationId = operationId, moved = moved, skipped = emptySet())
        hinter.trackPathsRemoved(operationId, moved.map { it.first })
        hinter.trackPathsAdded(operationId, moved.map { it.second })

        withTimeout(10_000) {
            bystander.state.first { state -> state.results.map { it.path } == listOf(defaultRoot.at("other.txt")) }
        }
        renaming.paths() shouldBe listOf(defaultRoot.at("journal.txt"), defaultRoot.at("other.txt"))
    }

    @Test
    fun `an alias spelling of the source still finds its row`(): Unit = runBlocking {
        val original = ResultPathKeys.primaryStorage
        try {
            ResultPathKeys.primaryStorage = "/storage/emulated/0"
            val canonicalRoot = LocalPath.build("/storage/emulated/0/Download")
            val aliasRoot = LocalPath.build("/sdcard/Download")
            val row = fileItem(canonicalRoot.at("notes.txt"), matchContext)
            val workspace = searched(listOf(row, fileItem(canonicalRoot.at("other.txt"))), root = canonicalRoot)

            val aliasSource = fileItem(aliasRoot.at("notes.txt"))
            val listener = workspace.requestRename(aliasSource, "journal.txt")
            listener.onMoved(
                operationId = Operation.Id(),
                moved = setOf(lookup(aliasRoot.at("notes.txt")) to lookup(aliasRoot.at("journal.txt"))),
                skipped = emptySet(),
            )

            val results = workspace.results()
            results.map { it.path } shouldBe listOf(aliasRoot.at("journal.txt"), canonicalRoot.at("other.txt"))
            results[0].matchContext shouldBe matchContext
        } finally {
            ResultPathKeys.primaryStorage = original
        }
    }

    @Test
    fun `own removal event keeps a skipped child`(): Unit = runBlocking {
        val root = LocalPath.build("/storage/emulated/0")
        val folder = dirItem(root.at("A"))
        val kept = fileItem(root.at("A/kept.txt"))
        val moving = fileItem(root.at("A/moved.txt"))
        val workspace = searched(listOf(folder, kept, moving), root = root)
        val bystander = searched(listOf(folder, kept, moving), root = root)

        val listener = workspace.requestRename(folder, "B")
        val moved = setOf(
            lookup(root.at("A"), FileType.DIRECTORY) to lookup(root.at("B"), FileType.DIRECTORY),
            lookup(root.at("A/moved.txt")) to lookup(root.at("B/moved.txt")),
        )
        val operationId = Operation.Id()
        listener.onMoved(operationId = operationId, moved = moved, skipped = setOf(lookup(root.at("A/kept.txt"))))
        hinter.trackPathsRemoved(operationId, moved.map { it.first }.toSet())
        hinter.trackPathsAdded(operationId, moved.map { it.second }.toSet())

        // Barrier: the bystander tab has processed the same Removed event.
        withTimeout(10_000) { bystander.state.first { it.results.isEmpty() } }

        val paths = workspace.paths()
        paths shouldContain root.at("A/kept.txt")
        paths shouldContain root.at("B/moved.txt")
    }
}
