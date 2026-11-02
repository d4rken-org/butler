package eu.darken.butler.searcher.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.errors.PathAlreadyExistsException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.Operation.Report.Paths.PathChange
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import kotlin.time.Clock

class RenameOperationTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val fileSystemHinter = mockk<FileSystemHinter>(relaxed = true)
    private val moveListener = mockk<RenameOperation.MoveListener>(relaxed = true)

    private val parent = LocalPath.build("/storage/emulated/0/Download")

    @Suppress("UNCHECKED_CAST")
    private fun lookupOf(path: LocalPath, fileType: FileType = FileType.FILE): APathLookup<APath<*>> =
        LocalPathLookup(
            lookedUp = path,
            fileType = fileType,
            size = 4L,
            modifiedAt = null,
        ) as APathLookup<APath<*>>

    private fun item(path: LocalPath, fileType: FileType = FileType.FILE): SearchItem =
        SearchItem.fromLookup(lookup = lookupOf(path, fileType), matchedQuery = "q")

    private fun operation(source: SearchItem, newName: String) = RenameOperation(
        workspaceId = workspaceId,
        command = SearcherCommand.Rename(source = source, newName = newName),
        moveListener = moveListener,
        issueHandler = mockk<IssueHandler>(),
        gatewaySwitch = gatewaySwitch,
        fileSystemHinter = fileSystemHinter,
    )

    private fun context() = Operation.Context(id = Operation.Id(), startedAt = Clock.System.now())

    private fun destinationIs(path: LocalPath, fileType: FileType) {
        coEvery { gatewaySwitch.lookup(path, any()) } returns lookupOf(path, fileType)
    }

    private fun moveCompletes(
        moved: Set<Pair<APathLookup<APath<*>>, APathLookup<APath<*>>>>,
        skipped: Set<APathLookup<APath<*>>> = emptySet(),
    ) {
        coEvery { gatewaySwitch.move(any(), any(), any(), any()) } returns flowOf(
            MoveAction.State.Completed(movedFiles = moved, skippedFiles = skipped, bytesMoved = 4L)
        )
    }

    @Test
    fun `an existing directory under the new name fails the rename and moves nothing`() = runTest2 {
        val source = parent.child("notes.txt")
        val destination = parent.child("Archive")
        destinationIs(destination, FileType.DIRECTORY)
        moveCompletes(setOf(lookupOf(source) to lookupOf(destination.child("notes.txt"))))

        shouldThrow<PathAlreadyExistsException> {
            operation(item(source), "Archive").perform(context()).toList()
        }

        coVerify(exactly = 0) { gatewaySwitch.move(any(), any(), any(), any()) }
        coVerify(exactly = 0) { moveListener.onMoved(any(), any(), any()) }
    }

    /** On a case-insensitive filesystem the new spelling finds the folder being renamed itself. */
    @Test
    fun `a case-only rename of a folder passes the existing-directory guard`() = runTest2 {
        val source = parent.child("docs")
        val destination = parent.child("Docs")
        destinationIs(destination, FileType.DIRECTORY)
        coEvery { gatewaySwitch.listFiles(parent) } returns listOf(source)
        moveCompletes(setOf(lookupOf(source, FileType.DIRECTORY) to lookupOf(destination, FileType.DIRECTORY)))

        operation(item(source, FileType.DIRECTORY), "Docs").perform(context()).toList()

        coVerify(exactly = 1) { gatewaySwitch.move(setOf(source), destination, any(), any()) }
    }

    @Test
    fun `case variant that is a separate directory is refused`() = runTest2 {
        val source = parent.child("docs")
        val destination = parent.child("Docs")
        destinationIs(destination, FileType.DIRECTORY)
        coEvery { gatewaySwitch.listFiles(parent) } returns listOf(source, destination)
        moveCompletes(setOf(lookupOf(source, FileType.DIRECTORY) to lookupOf(destination.child("docs"), FileType.DIRECTORY)))

        shouldThrow<PathAlreadyExistsException> {
            operation(item(source, FileType.DIRECTORY), "Docs").perform(context()).toList()
        }

        coVerify(exactly = 0) { gatewaySwitch.move(any(), any(), any(), any()) }
        coVerify(exactly = 0) { moveListener.onMoved(any(), any(), any()) }
    }

    @Test
    fun `the hinter hears about confirmed pairs only`() = runTest2 {
        val source = parent.child("docs")
        val destination = parent.child("papers")
        val movedChild = lookupOf(source.child("a.txt")) to lookupOf(destination.child("a.txt"))
        val movedRoot = lookupOf(source, FileType.DIRECTORY) to lookupOf(destination, FileType.DIRECTORY)
        val skippedChild = lookupOf(source.child("locked.txt"))
        destinationIs(destination, FileType.UNKNOWN)
        moveCompletes(setOf(movedChild, movedRoot), skipped = setOf(skippedChild))
        val context = context()

        operation(item(source, FileType.DIRECTORY), "papers").perform(context).toList()

        coVerify(exactly = 1) {
            fileSystemHinter.trackPathsRemoved(context.id, setOf(movedChild.first, movedRoot.first))
        }
        coVerify(exactly = 1) {
            fileSystemHinter.trackPathsAdded(context.id, setOf(movedChild.second, movedRoot.second))
        }
        coVerify(exactly = 1) {
            moveListener.onMoved(context.id, setOf(movedChild, movedRoot), setOf(skippedChild))
        }
    }

    @Test
    fun `the listener hears about the move before the hinter`() = runTest2 {
        val source = parent.child("notes.txt")
        val destination = parent.child("journal.txt")
        destinationIs(destination, FileType.UNKNOWN)
        moveCompletes(setOf(lookupOf(source) to lookupOf(destination)))

        operation(item(source), "journal.txt").perform(context()).toList()

        coVerifyOrder {
            moveListener.onMoved(any(), any(), any())
            fileSystemHinter.trackPathsRemoved(any(), any())
            fileSystemHinter.trackPathsAdded(any(), any())
        }
    }

    @Test
    fun `the report lists the moves and is about the top-level destination`() = runTest2 {
        val source = parent.child("docs")
        val destination = parent.child("papers")
        destinationIs(destination, FileType.UNKNOWN)
        // The descendant leads the set: a subject read positionally would pick it up.
        moveCompletes(
            setOf(
                lookupOf(source.child("a.txt")) to lookupOf(destination.child("a.txt")),
                lookupOf(source, FileType.DIRECTORY) to lookupOf(destination, FileType.DIRECTORY),
            )
        )

        val completed = operation(item(source, FileType.DIRECTORY), "papers").perform(context()).toList()
            .filterIsInstance<SearcherOperation.State.Completed>().single()

        val report = completed.report as RenameOperationReport
        report.subjectPath shouldBe destination
        report.affectedPaths.toSet() shouldBe setOf(
            PathChange(destination.child("a.txt"), PathChange.Change.MOVED, previousPath = source.child("a.txt")),
            PathChange(destination, PathChange.Change.MOVED, previousPath = source),
        )
        report.movedFiles shouldBe 1
        report.movedDirectories shouldBe 1
    }

    @Test
    fun `the metadata describes a rename from this search`() {
        val source = parent.child("notes.txt")

        val metadata = operation(item(source), "journal.txt").metadata

        metadata.kind shouldBe Operation.Metadata.Kind.MOVE
        metadata.intent shouldBe Operation.Metadata.Intent.RENAME
        metadata.origin shouldBe Operation.Metadata.Origin.Searcher(workspaceId)
        metadata.pathPlan shouldBe OperationPathPlan(
            targets = listOf(source),
            destination = OperationPathPlan.Destination.RequestedTarget(parent.child("journal.txt")),
        )
    }
}
