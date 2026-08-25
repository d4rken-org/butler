package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Test
import testhelpers.BaseTest

/**
 * The path plans of the Explorer producers that have no operation test of their own. Each assertion
 * is what the history scope index and the details card read.
 */
class ExplorerOperationPathPlanTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val first = LocalPath.build("/sdcard/Download/a.txt")
    private val second = LocalPath.build("/sdcard/Download/b.txt")
    private val folder = LocalPath.build("/sdcard/Backup")

    private fun moveOperation(command: ExplorerCommand.Move) = MoveOperation(
        workspaceId = workspaceId,
        command = command,
        issueHandler = mockk(),
        gatewaySwitch = mockk(),
        dispatcherProvider = mockk(),
        fileSystemHinter = mockk(),
    )

    private fun copyOperation(command: ExplorerCommand.Copy) = CopyOperation(
        workspaceId = workspaceId,
        command = command,
        issueHandler = mockk(),
        gatewaySwitch = mockk(),
        fileSystemHinter = mockk(),
    )

    @Test
    fun `a paste or drop move lands the sources inside the target folder`() {
        val plan = moveOperation(
            ExplorerCommand.Move(
                sources = setOf(first, second),
                destination = OperationPathPlan.Destination.Container(folder),
                intent = Operation.Metadata.Intent.PASTE_MOVE,
            ),
        ).metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(first, second)
        plan.destination shouldBe OperationPathPlan.Destination.Container(folder)
        plan.scopePaths shouldContainExactly listOf(first, second, folder)
        plan.representativePath shouldBe first
    }

    @Test
    fun `a rename move names the exact path the user asked for`() {
        val renamed = first.parent!!.child("renamed.txt")
        val plan = moveOperation(
            ExplorerCommand.Move(
                sources = setOf(first),
                destination = OperationPathPlan.Destination.RequestedTarget(renamed),
                intent = Operation.Metadata.Intent.RENAME,
            ),
        ).metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(first)
        plan.destination shouldBe OperationPathPlan.Destination.RequestedTarget(renamed)
        plan.scopePaths shouldContainExactly listOf(first, renamed)
    }

    @Test
    fun `a paste or drop copy lands the sources inside the target folder`() {
        val plan = copyOperation(
            ExplorerCommand.Copy(
                sources = setOf(first),
                destination = OperationPathPlan.Destination.Container(folder),
                intent = Operation.Metadata.Intent.DROP_COPY,
            ),
        ).metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(first)
        plan.destination shouldBe OperationPathPlan.Destination.Container(folder)
        plan.scopePaths shouldContainExactly listOf(first, folder)
    }

    @Test
    fun `the destination path reaches the gateway unchanged whichever kind it is`() {
        val container = ExplorerCommand.Move(
            sources = setOf(first),
            destination = OperationPathPlan.Destination.Container(folder),
        )
        val requested = ExplorerCommand.Copy(
            sources = setOf(first),
            destination = OperationPathPlan.Destination.RequestedTarget(folder),
        )

        container.destination.path shouldBe folder
        requested.destination.path shouldBe folder
    }

    @Test
    fun `a create targets the path that will exist, not the parent folder`() {
        val plan = CreateOperation(
            workspaceId = workspaceId,
            command = ExplorerCommand.Create(
                parentPath = folder,
                name = "notes.txt",
                type = ExplorerCommand.Create.Type.FILE,
            ),
            issueHandler = mockk(),
            gatewaySwitch = mockk(),
            dispatcherProvider = mockk(),
            fileSystemHinter = mockk(),
        ).metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(folder.child("notes.txt"))
        plan.destination shouldBe null
    }

    @Test
    fun `a delete targets the paths and has no destination`() {
        val plan = DeleteOperation(
            workspaceId = workspaceId,
            command = ExplorerCommand.Delete(targets = setOf(first, second)),
            issueHandler = mockk(),
            fileSystemHinter = mockk(),
            coreDeleteExecutor = mockk(),
        ).metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(first, second)
        plan.destination shouldBe null
        plan.scopePaths shouldContainExactly listOf(first, second)
    }
}
