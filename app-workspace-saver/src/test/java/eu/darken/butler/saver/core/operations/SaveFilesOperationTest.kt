package eu.darken.butler.saver.core.operations

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.toList
import org.junit.Before
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import kotlin.time.Clock

class SaveFilesOperationTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>().also {
        every { it.contentResolver } returns resolver
    }
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val issueHandler = mockk<IssueHandler>()

    private val sourceUri = mockk<Uri>()
    private val targetDirectory = LocalPath.build("/save")
    private val targetPath = targetDirectory.child("file.txt")
    private val secondPath = targetDirectory.child("second.txt")

    private val capturedIssues = mutableListOf<PathActionIssue>()
    private var conflictResolution: PathActionIssue.Resolution =
        PathActionIssue.PathAlreadyExists.Resolution.Overwrite()

    private fun lookupOf(path: LocalPath) = LocalPathLookup(
        lookedUp = path,
        fileType = FileType.FILE,
        size = 4L,
        modifiedAt = null,
    )

    @Before
    fun setup() {
        every { resolver.openInputStream(any()) } returns ByteArrayInputStream("data".toByteArray())

        coEvery { gatewaySwitch.exists(any()) } returns false
        coEvery { gatewaySwitch.exists(targetPath) } returns true
        coEvery { gatewaySwitch.lookup(any(), any<LookupOptions>()) } answers {
            @Suppress("UNCHECKED_CAST")
            lookupOf(firstArg<LocalPath>()) as APathLookup<APath<*>>
        }
        coEvery { gatewaySwitch.createFile(any(), any()) } returns Unit
        coEvery { gatewaySwitch.openOutputStream(any(), any()) } returns ByteArrayOutputStream()
        coEvery { gatewaySwitch.delete(any<APath<*>>(), any<Boolean>()) } returns true
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns MoveOutcome.Moved

        coEvery { issueHandler.handleIssue(any(), any()) } answers {
            val issue = secondArg<PathActionIssue>()
            capturedIssues += issue
            when (issue) {
                is PathActionIssue.PathAlreadyExists -> conflictResolution
                is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
                else -> PathActionIssue.UnknownError.Resolution.Skip()
            }
        }
    }

    private fun operation(filenames: List<String> = listOf("file.txt")) = SaveFilesOperation(
        workspaceId = workspaceId,
        command = SaveFilesOperation.Command(
            sources = filenames.map { SaveFilesOperation.Command.SourceFile(sourceUri, it, 4L) },
            targetDirectory = targetDirectory,
        ),
        context = context,
        gatewaySwitch = gatewaySwitch,
        issueHandler = issueHandler,
    )

    private suspend fun performToReport(filenames: List<String> = listOf("file.txt")): SaveFilesReport {
        val states = operation(filenames)
            .perform(Operation.Context(id = Operation.Id(), startedAt = Clock.System.now()))
            .toList()
        return states.last()
            .shouldBeInstanceOf<SaveFilesOperation.State.Completed>()
            .report
            .shouldBeInstanceOf<SaveFilesReport>()
    }

    @Test
    fun `overwrite with successful delete writes the file`() = runTest2 {
        val report = performToReport()

        report.results.single().shouldBeInstanceOf<SaveFilesReport.FileResult.Success>()
        coVerify { gatewaySwitch.delete(targetPath, false) }
        coVerify { gatewaySwitch.createFile(targetPath, false) }
    }

    @Test
    fun `overwrite with failed delete does not write and surfaces the error`() = runTest2 {
        coEvery { gatewaySwitch.delete(targetPath, any()) } returns false

        val report = performToReport()

        report.results.single().shouldBeInstanceOf<SaveFilesReport.FileResult.Skipped>()
        capturedIssues.filterIsInstance<PathActionIssue.InsufficientPermission>()
            .single().exception.shouldBeInstanceOf<WriteException>()
        coVerify(exactly = 0) { gatewaySwitch.createFile(any(), any()) }
    }

    @Test
    fun `rename destination with successful move writes to the original path`() = runTest2 {
        conflictResolution = PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("file (1).txt")

        val report = performToReport()

        val success = report.results.single().shouldBeInstanceOf<SaveFilesReport.FileResult.Success>()
        success.savedPath shouldBe targetPath
        coVerify { gatewaySwitch.move(targetPath, targetDirectory.child("file (1).txt")) }
    }

    @Test
    fun `rename destination that does not move does not write and surfaces the error`() = runTest2 {
        conflictResolution = PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("file (1).txt")
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns MoveOutcome.NotSupported("test")

        val report = performToReport()

        report.results.single().shouldBeInstanceOf<SaveFilesReport.FileResult.Skipped>()
        capturedIssues.filterIsInstance<PathActionIssue.InsufficientPermission>()
            .single().exception.shouldBeInstanceOf<WriteException>()
        coVerify(exactly = 0) { gatewaySwitch.createFile(any(), any()) }
    }

    /*
     * A save takes many files, so its history label is defined by the PLAN order, not by whichever
     * file happened to succeed first.
     */

    @Test
    fun `the subject is the first planned file when everything succeeds`() = runTest2 {
        val report = performToReport(listOf("file.txt", "second.txt"))

        report.successes.map { it.savedPath } shouldContainExactly listOf(targetPath, secondPath)
        report.subjectPath shouldBe targetPath
    }

    @Test
    fun `the subject moves on when the first planned file is skipped`() = runTest2 {
        conflictResolution = PathActionIssue.PathAlreadyExists.Resolution.Skip()

        val report = performToReport(listOf("file.txt", "second.txt"))

        report.results.first().shouldBeInstanceOf<SaveFilesReport.FileResult.Skipped>()
        report.subjectPath shouldBe secondPath
    }

    @Test
    fun `a save that wrote nothing names no subject`() = runTest2 {
        conflictResolution = PathActionIssue.PathAlreadyExists.Resolution.Skip()

        val report = performToReport(listOf("file.txt"))

        report.successes.shouldBeEmpty()
        report.subjectPath shouldBe null
    }

    @Test
    fun `the path plan targets the files to write and keeps the folder out of the scope`() {
        val plan = operation().metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(targetPath)
        plan.destination shouldBe OperationPathPlan.Destination.Container(targetDirectory)
        plan.scopePaths shouldContainExactly listOf(targetPath)
        plan.representativePath shouldBe targetPath
    }
}
