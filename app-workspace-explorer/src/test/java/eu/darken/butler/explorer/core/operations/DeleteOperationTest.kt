package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.operations.deleteGeneric
import eu.darken.butler.common.trash.TrashManager
import eu.darken.butler.common.trash.TrashSettings
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.CoreDeleteExecutor
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.junit.Before
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import testhelpers.mockDataStoreValue
import kotlin.time.Clock

/**
 * A permanent recursive delete walks the tree post-order, so the folder the user selected is the
 * LAST reported removal. The history subject has to survive that.
 *
 * The delete runs through the real engine over an in-memory [FileSystemOps]; that ops object is not
 * a routed local one, so the batch subtree route is unavailable and the recursive walk is what
 * produces the ordering.
 */
class DeleteOperationTest : BaseTest() {

    private val folder = LocalPath.build("/sdcard/Download/nested")
    private val innerFile = folder.child("aaa.txt")

    private val fileSystemOps = mockk<FileSystemOps<LocalPath, LocalPathLookup>>()
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val trashManager = mockk<TrashManager>()
    private val trashSettings = mockk<TrashSettings>()

    private fun lookupOf(path: LocalPath, isDir: Boolean) = LocalPathLookup(
        lookedUp = path,
        fileType = if (isDir) FileType.DIRECTORY else FileType.FILE,
        size = 4L,
        modifiedAt = null,
    )

    @Before
    fun setup() {
        every { trashSettings.enabled } returns mockDataStoreValue(false)
        coEvery { fileSystemOps.lookup(folder, any<LookupOptions>()) } returns lookupOf(folder, isDir = true)
        coEvery { fileSystemOps.lookup(innerFile, any<LookupOptions>()) } returns lookupOf(innerFile, isDir = false)
        coEvery { fileSystemOps.listFiles(folder) } returns listOf(innerFile)
        coEvery { fileSystemOps.delete(any(), any()) } returns true
        coEvery { gatewaySwitch.delete(any<Set<APath<*>>>(), any<DeleteAction.Options<APath<*>>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            firstArg<Set<LocalPath>>()
                .deleteGeneric(fileSystemOps = fileSystemOps, recursive = true, ignoreMissing = false)
                    as Flow<DeleteAction.State<APath<*>, APathLookup<APath<*>>>>
        }
    }

    private fun operation(targets: Set<APath<*>>) = DeleteOperation(
        workspaceId = Workspace.Id(),
        command = ExplorerCommand.Delete(targets = targets),
        issueHandler = mockk<IssueHandler>(),
        fileSystemHinter = mockk<FileSystemHinter>(relaxed = true),
        coreDeleteExecutor = CoreDeleteExecutor(
            gatewaySwitch = gatewaySwitch,
            trashManager = trashManager,
            trashSettings = trashSettings,
        ),
    )

    private fun context() = Operation.Context(id = Operation.Id(), startedAt = Clock.System.now())

    @Test
    fun `a permanent recursive folder delete is about the folder, not a descendant`() = runTest2 {
        val completed = operation(setOf(folder)).perform(context()).toList()
            .filterIsInstance<ExplorerOperation.State.Completed>().single()

        val report = completed.report as DeleteOperationReport
        report.affectedPaths.map { it.path } shouldBe listOf(innerFile, folder)
        // Documents why the subject cannot be read off the reported changes.
        report.affectedPaths.first().path shouldNotBe folder
        report.subjectPath shouldBe folder
    }

    @Test
    fun `an empty selection names no subject instead of throwing`() = runTest2 {
        val completed = operation(emptySet()).perform(context()).toList()
            .filterIsInstance<ExplorerOperation.State.Completed>().single()

        (completed.report as DeleteOperationReport).subjectPath shouldBe null
    }
}
