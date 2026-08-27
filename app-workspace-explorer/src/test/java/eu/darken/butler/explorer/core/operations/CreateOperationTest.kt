package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CreateAction
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import kotlin.time.Clock
import kotlin.time.Instant

class CreateOperationTest : BaseTest() {

    private val parentPath = LocalPath.build("/sdcard/Download")
    private val requestedPath = parentPath.child("notes")
    private val renamedPath = parentPath.child("notes-2")

    @Suppress("UNCHECKED_CAST")
    private fun gateway(createdAs: APath<*>) = mockk<GatewaySwitch>().apply {
        coEvery { useRes(any<suspend (Any) -> Any?>()) } coAnswers {
            firstArg<suspend (Any) -> Any?>().invoke(this@apply)
        }
        coEvery { create(any(), any(), any()) } returns flowOf(
            CreateAction.State.Completed(
                LocalPathLookup(
                    lookedUp = createdAs as LocalPath,
                    fileType = FileType.DIRECTORY,
                    size = null,
                    modifiedAt = Instant.DISTANT_PAST,
                ) as APathLookup<APath<*>>
            )
        )
    }

    private fun operation(gatewaySwitch: GatewaySwitch) = CreateOperation(
        workspaceId = Workspace.Id(),
        command = ExplorerCommand.Create(
            parentPath = parentPath,
            name = "notes",
            type = ExplorerCommand.Create.Type.DIRECTORY,
        ),
        issueHandler = mockk<IssueHandler>(),
        gatewaySwitch = gatewaySwitch,
        dispatcherProvider = mockk(),
        fileSystemHinter = mockk<FileSystemHinter>(relaxed = true),
    )

    private fun context() = Operation.Context(id = Operation.Id(), startedAt = Clock.System.now())

    @Test
    fun `the subject is the created folder`() = runTest2 {
        val completed = operation(gateway(requestedPath)).perform(context())
            .last() as ExplorerOperation.State.Completed

        (completed.report as CreateOperationReport).subjectPath shouldBe requestedPath
    }

    @Test
    fun `a name conflict resolved by renaming names the path that was created`() = runTest2 {
        val completed = operation(gateway(renamedPath)).perform(context())
            .last() as ExplorerOperation.State.Completed

        // Naming the requested path would label the row with a folder that was never created.
        (completed.report as CreateOperationReport).subjectPath shouldBe renamedPath
    }
}
