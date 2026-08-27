package eu.darken.butler.viewer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.CoreDeleteExecutor
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant

/** The history row for a delete names the selected target, whatever the engine removed first. */
class DeleteOperationSubjectTest : BaseTest() {

    private val folder = LocalPath.build("/sdcard/Download/nested")
    private val innerFile = folder.child("aaa.txt")

    private fun lookupOf(path: LocalPath, isDir: Boolean): APathLookup<*> = LocalPathLookup(
        lookedUp = path,
        fileType = if (isDir) FileType.DIRECTORY else FileType.FILE,
        size = 4L,
        modifiedAt = Instant.DISTANT_PAST,
    )

    private fun operation(targets: Set<APath<*>>, deleted: Set<APathLookup<*>>): DeleteOperation {
        val executor = mockk<CoreDeleteExecutor> {
            coEvery { execute(any(), any()) } returns flowOf(
                CoreDeleteExecutor.State.Completed(
                    result = CoreDeleteExecutor.Result(
                        deleted = deleted,
                        trashed = emptySet(),
                        skipped = emptySet(),
                        bytesFreed = 0L,
                        performanceHistory = null,
                    )
                )
            )
        }
        return DeleteOperation(
            workspaceId = Workspace.Id(),
            command = ViewerCommand.Delete(targets = targets),
            issueHandler = mockk<IssueHandler>(),
            coreDeleteExecutor = executor,
            fileSystemHinter = mockk<FileSystemHinter>(relaxed = true),
        )
    }

    private suspend fun DeleteOperation.report(): DeleteOperationReport =
        perform(Operation.Context(id = Operation.Id(), startedAt = Instant.DISTANT_PAST))
            .toList()
            .filterIsInstance<ViewerOperation.State.Completed>()
            .single()
            .report as DeleteOperationReport

    @Test
    fun `the subject is the selected target, not the descendant removed first`(): Unit = runTest {
        val report = operation(
            targets = setOf(folder),
            deleted = setOf(lookupOf(innerFile, isDir = false), lookupOf(folder, isDir = true)),
        ).report()

        report.affectedPaths.first().path shouldBe innerFile
        report.subjectPath shouldBe folder
    }

    @Test
    fun `an empty selection names no subject instead of throwing`(): Unit = runTest {
        operation(targets = emptySet(), deleted = emptySet()).report().subjectPath shouldBe null
    }
}
