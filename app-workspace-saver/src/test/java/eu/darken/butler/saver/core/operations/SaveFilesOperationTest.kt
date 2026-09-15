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
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
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

    /**
     * What the target directory holds. A staged replacement draws a name that must not exist yet and
     * deletes/renames it afterwards, so existence has to follow the writes instead of being fixed.
     */
    private val present = mutableSetOf(targetPath.path)

    private fun isStagingOf(path: APath<*>, filename: String) =
        path.name.startsWith(".") && path.name.endsWith(".part.$filename")

    private fun isBackupOf(path: APath<*>, filename: String) =
        path.name.startsWith(".") && path.name.endsWith(".backup.$filename")

    private fun lookupOf(path: LocalPath) = LocalPathLookup(
        lookedUp = path,
        fileType = FileType.FILE,
        size = 4L,
        modifiedAt = null,
    )

    @Before
    fun setup() {
        every { resolver.openInputStream(any()) } returns ByteArrayInputStream("data".toByteArray())

        present.clear()
        present += targetPath.path

        coEvery { gatewaySwitch.exists(any()) } answers { firstArg<APath<*>>().path in present }
        coEvery { gatewaySwitch.lookup(any(), any<LookupOptions>()) } answers {
            @Suppress("UNCHECKED_CAST")
            lookupOf(firstArg<LocalPath>()) as APathLookup<APath<*>>
        }
        coEvery { gatewaySwitch.createFile(any(), any()) } answers {
            present += firstArg<APath<*>>().path
            Unit
        }
        coEvery { gatewaySwitch.openOutputStream(any(), any()) } returns ByteArrayOutputStream()
        coEvery { gatewaySwitch.delete(any<APath<*>>(), any<Boolean>()) } answers {
            present -= firstArg<APath<*>>().path
            true
        }
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } answers {
            present -= firstArg<APath<*>>().path
            present += secondArg<APath<*>>().path
            MoveOutcome.Moved
        }

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
    fun `overwrite writes a staging file and swaps it in`() = runTest2 {
        val report = performToReport()

        val success = report.results.single().shouldBeInstanceOf<SaveFilesReport.FileResult.Success>()
        success.savedPath shouldBe targetPath
        coVerify { gatewaySwitch.createFile(match<APath<*>> { isStagingOf(it, "file.txt") }, false) }
        coVerify { gatewaySwitch.move(targetPath, match<APath<*>> { isBackupOf(it, "file.txt") }) }
        coVerify { gatewaySwitch.delete(match<APath<*>> { isBackupOf(it, "file.txt") }, false) }
        coVerify { gatewaySwitch.move(match<APath<*>> { isStagingOf(it, "file.txt") }, targetPath) }
        coVerify(exactly = 0) { gatewaySwitch.openOutputStream(targetPath, any()) }
    }

    @Test
    fun `overwrite with failed delete keeps the existing file and surfaces the error`() = runTest2 {
        coEvery { gatewaySwitch.delete(targetPath, any()) } returns false
        coEvery {
            gatewaySwitch.move(targetPath, match<APath<*>> { isBackupOf(it, "file.txt") })
        } returns MoveOutcome.NotSupported("test: rename aside unsupported")

        val report = performToReport()

        report.results.single().shouldBeInstanceOf<SaveFilesReport.FileResult.Skipped>()
        capturedIssues.filterIsInstance<PathActionIssue.InsufficientPermission>()
            .single().exception.shouldBeInstanceOf<WriteException>()
        // The existing file was never written to, and the staged data was not thrown away with the
        // failed swap-in
        coVerify(exactly = 0) { gatewaySwitch.openOutputStream(targetPath, any()) }
        coVerify(exactly = 0) { gatewaySwitch.move(match<APath<*>> { isStagingOf(it, "file.txt") }, targetPath) }
        coVerify(exactly = 0) { gatewaySwitch.delete(match<APath<*>> { isStagingOf(it, "file.txt") }, any()) }
    }

    @Test
    fun `overwrite whose write fails mid-stream leaves the existing file untouched`() = runTest2 {
        coEvery { gatewaySwitch.openOutputStream(any(), any()) } returns object : OutputStream() {
            override fun write(b: Int) = throw IOException("No space left on device")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("No space left on device")
        }

        val report = performToReport()

        report.results.single().shouldBeInstanceOf<SaveFilesReport.FileResult.Skipped>()
        // The target was neither deleted nor written to, and the half-written staging file is gone
        coVerify(exactly = 0) { gatewaySwitch.delete(targetPath, any()) }
        coVerify(exactly = 0) { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) }
        coVerify { gatewaySwitch.delete(match<APath<*>> { isStagingOf(it, "file.txt") }, false) }
        present shouldBe setOf(targetPath.path)
    }

    @Test
    fun `F7 - overwriting a non-empty directory must not report success`() = runTest2 {
        // Given - the conflicting target is a DIRECTORY that still has children, so the non-recursive
        // delete the overwrite falls back to cannot remove it
        coEvery { gatewaySwitch.lookup(targetPath, any<LookupOptions>()) } answers {
            @Suppress("UNCHECKED_CAST")
            LocalPathLookup(
                lookedUp = targetPath,
                fileType = FileType.DIRECTORY,
                size = null,
                modifiedAt = null,
            ) as APathLookup<APath<*>>
        }
        coEvery { gatewaySwitch.delete(targetPath, false) } throws IOException("Directory not empty")

        val report = performToReport()

        withClue("a non-empty directory must not be replaced silently: $present") {
            report.results.single().shouldNotBeInstanceOf<SaveFilesReport.FileResult.Success>()
        }
    }

    @Test
    fun `F14 - overwriting a directory the gateway deletes wholesale must not report success`() = runTest2 {
        // Given - the conflicting target is a non-empty DIRECTORY, and the gateway's non-recursive
        // delete behaves the way SAFFileSystemOps.delete does: DocumentsContract.deleteDocument is
        // handed the tree and the provider takes the children with it, reporting success.
        val child = targetPath.child("keepme.txt")
        present += child.path
        coEvery { gatewaySwitch.lookup(targetPath, any<LookupOptions>()) } answers {
            @Suppress("UNCHECKED_CAST")
            LocalPathLookup(
                lookedUp = targetPath,
                fileType = FileType.DIRECTORY,
                size = null,
                modifiedAt = null,
            ) as APathLookup<APath<*>>
        }
        coEvery { gatewaySwitch.delete(targetPath, false) } answers {
            present.removeAll { it == targetPath.path || it.startsWith(targetPath.path + "/") }
            true
        }

        val report = performToReport()

        withClue("a non-empty directory must not be replaced silently: $present") {
            report.results.single().shouldNotBeInstanceOf<SaveFilesReport.FileResult.Success>()
        }
    }

    @Test
    fun `rename destination with successful move writes to the original path`() = runTest2 {
        conflictResolution = PathActionIssue.PathAlreadyExists.Resolution.RenameDestination("file (1).txt")

        val report = performToReport()

        val success = report.results.single().shouldBeInstanceOf<SaveFilesReport.FileResult.Success>()
        success.savedPath shouldBe targetPath
        coVerify { gatewaySwitch.move(targetPath, targetDirectory.child("file (1).txt")) }
        // The renamed-away target leaves the path free, so there is nothing to stage
        coVerify { gatewaySwitch.createFile(targetPath, false) }
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
