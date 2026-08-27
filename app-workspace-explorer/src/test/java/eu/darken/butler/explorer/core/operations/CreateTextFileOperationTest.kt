package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import java.io.ByteArrayOutputStream
import kotlin.time.Instant

class CreateTextFileOperationTest : BaseTest() {

    private val directory = LocalPath.build("/test/dir")
    private val requestedPath = directory.child("snippet.txt")
    private val renamedPath = directory.child("snippet-2.txt")
    private val content = "Hello World!"

    private fun lookupOf(path: APath<*>, size: Long?) = LocalPathLookup(
        lookedUp = path as LocalPath,
        fileType = FileType.FILE,
        size = size,
        modifiedAt = Instant.DISTANT_PAST,
    )

    /**
     * @param createdAs where [CreateAction] says the file actually landed, which is not the
     * requested path once the user resolves a name conflict by renaming.
     */
    @Suppress("UNCHECKED_CAST")
    private fun setup(
        gatewaySwitch: GatewaySwitch,
        createdAs: APath<*>,
        writtenTo: MutableList<APath<*>>,
        sink: ByteArrayOutputStream,
        lookupOptions: MutableList<LookupOptions>,
    ) {
        coEvery { gatewaySwitch.useRes(any<suspend (Any) -> Any?>()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(gatewaySwitch)
        }
        coEvery { gatewaySwitch.create(any(), any(), any()) } returns flowOf(
            CreateAction.State.Completed(lookupOf(createdAs, size = 0L) as APathLookup<APath<*>>)
        )
        val writeTarget = slot<APath<*>>()
        coEvery { gatewaySwitch.openOutputStream(capture(writeTarget), any()) } answers {
            writtenTo.add(writeTarget.captured)
            sink
        }
        val options = slot<LookupOptions>()
        coEvery { gatewaySwitch.lookup(any(), capture(options)) } answers {
            lookupOptions.add(options.captured)
            lookupOf(firstArg(), size = sink.size().toLong()) as APathLookup<APath<*>>
        }
    }

    private fun operation(gatewaySwitch: GatewaySwitch, hinter: FileSystemHinter) = CreateTextFileOperation(
        workspaceId = Workspace.Id(),
        command = ExplorerCommand.CreateTextFile(path = requestedPath, content = content),
        issueHandler = mockk<IssueHandler>(),
        gatewaySwitch = gatewaySwitch,
        fileSystemHinter = hinter,
    )

    @Test
    fun `the created file is reported with a size, not an unknown one`(): Unit = runTest {
        val hinter = FileSystemHinter()
        val events = mutableListOf<FileSystemEvent>()
        hinter.events.onEach { events.add(it) }.launchIn(backgroundScope)
        runCurrent()

        val gatewaySwitch = mockk<GatewaySwitch>()
        val writtenTo = mutableListOf<APath<*>>()
        val lookupOptions = mutableListOf<LookupOptions>()
        val sink = ByteArrayOutputStream()
        setup(gatewaySwitch, createdAs = requestedPath, writtenTo, sink, lookupOptions)

        val completed = operation(gatewaySwitch, hinter)
            .perform(Operation.Context(id = Operation.Id(), startedAt = Instant.DISTANT_PAST))
            .last() as ExplorerOperation.State.Completed
        runCurrent()

        completed.error shouldBe null
        writtenTo shouldBe listOf(requestedPath)
        sink.toString(Charsets.UTF_8.name()) shouldBe content

        // The lookup that feeds the listing has to carry the size, or the row renders "?" until
        // the directory is re-entered.
        lookupOptions.last().fetchSize shouldBe true
        val added = events.single().shouldBeInstanceOf<FileSystemEvent.Added>().paths.single()
        added.lookedUp shouldBe requestedPath
        added.size shouldNotBe null
    }

    @Test
    fun `renaming past a name conflict writes to the new file, not the existing one`(): Unit = runTest {
        val hinter = FileSystemHinter()
        val events = mutableListOf<FileSystemEvent>()
        hinter.events.onEach { events.add(it) }.launchIn(backgroundScope)
        runCurrent()

        val gatewaySwitch = mockk<GatewaySwitch>()
        val writtenTo = mutableListOf<APath<*>>()
        val lookupOptions = mutableListOf<LookupOptions>()
        val sink = ByteArrayOutputStream()
        setup(gatewaySwitch, createdAs = renamedPath, writtenTo, sink, lookupOptions)

        val completed = operation(gatewaySwitch, hinter)
            .perform(Operation.Context(id = Operation.Id(), startedAt = Instant.DISTANT_PAST))
            .last() as ExplorerOperation.State.Completed
        runCurrent()

        completed.error shouldBe null
        // Writing to the requested path would truncate the file the rename was meant to preserve.
        writtenTo shouldBe listOf(renamedPath)
        sink.toString(Charsets.UTF_8.name()) shouldBe content

        // Reporting the requested path leaves the renamed file out of the listing until a refresh.
        completed.report.affectedPaths.single().let {
            it.path shouldBe renamedPath
            it.change shouldBe Operation.Report.PathChange.Change.ADDED
        }
        completed.report.subjectPath shouldBe renamedPath
        events.single().shouldBeInstanceOf<FileSystemEvent.Added>().paths.single().lookedUp shouldBe renamedPath
    }

    @Test
    fun `the path plan targets the file being created`() {
        val plan = operation(mockk(), FileSystemHinter()).metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(requestedPath)
        plan.destination shouldBe null
        plan.scopePaths shouldContainExactly listOf(requestedPath)
    }
}
