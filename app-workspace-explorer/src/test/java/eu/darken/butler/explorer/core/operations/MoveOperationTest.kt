package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import kotlin.time.Clock

/**
 * A directory move reports one change per moved descendant. The history subject has to come from
 * the source-to-destination pair the command asked for, never from the position of a change.
 */
class MoveOperationTest : BaseTest() {

    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val fileSystemHinter = mockk<FileSystemHinter>(relaxed = true)

    private val sourceDir = LocalPath.build("/sdcard/Download/nested")
    private val destinationDir = LocalPath.build("/sdcard/Backup")
    private val movedDir = destinationDir.child("nested")
    private val movedChild = movedDir.child("aaa.txt")

    private fun lookupOf(path: LocalPath, isDir: Boolean = false): APathLookup<APath<*>> {
        @Suppress("UNCHECKED_CAST")
        return LocalPathLookup(
            lookedUp = path,
            fileType = if (isDir) FileType.DIRECTORY else FileType.FILE,
            size = 4L,
            modifiedAt = null,
        ) as APathLookup<APath<*>>
    }

    private fun operation() = MoveOperation(
        workspaceId = Workspace.Id(),
        command = ExplorerCommand.Move(
            sources = setOf(sourceDir),
            destination = OperationPathPlan.Destination.Container(destinationDir),
        ),
        issueHandler = mockk<IssueHandler>(),
        gatewaySwitch = gatewaySwitch,
        dispatcherProvider = mockk(),
        fileSystemHinter = fileSystemHinter,
    )

    private fun context() = Operation.Context(id = Operation.Id(), startedAt = Clock.System.now())

    @Test
    fun `a directory move is about the top-level destination, not a descendant`() = runTest2 {
        // The descendant leads the set: a subject read positionally would pick it up.
        coEvery { gatewaySwitch.move(any(), any(), any(), any()) } returns flowOf(
            MoveAction.State.Completed(
                movedFiles = setOf(
                    lookupOf(sourceDir.child("aaa.txt")) to lookupOf(movedChild),
                    lookupOf(sourceDir, isDir = true) to lookupOf(movedDir, isDir = true),
                ),
                bytesMoved = 8L,
            )
        )

        val completed = operation().perform(context()).toList()
            .filterIsInstance<ExplorerOperation.State.Completed>().single()

        val report = completed.report as MoveOperationReport
        report.subjectPath shouldBe movedDir
        report.affectedPaths.map { it.path } shouldContainExactlyInAnyOrder listOf(movedChild, movedDir)
    }

    @Test
    fun `a move that completed nothing names no subject`() = runTest2 {
        coEvery { gatewaySwitch.move(any(), any(), any(), any()) } returns flowOf(
            MoveAction.State.Completed(
                movedFiles = emptySet(),
                skippedFiles = setOf(lookupOf(sourceDir, isDir = true)),
                bytesMoved = 0L,
            )
        )

        val completed = operation().perform(context()).toList()
            .filterIsInstance<ExplorerOperation.State.Completed>().single()

        (completed.report as MoveOperationReport).subjectPath shouldBe null
    }
}
