package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import org.junit.Before
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.time.Clock

class DownloadLocalCopyOperationTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val issueHandler = mockk<IssueHandler>()
    private val fileSystemHinter = mockk<FileSystemHinter>(relaxed = true)

    private val sourcePath = LocalPath.build("/remote/big.zip")
    private val destDir = LocalPath.build("/local/downloads")
    private val destPath = destDir.child("big.zip")

    private val content = "0123456789".repeat(10).toByteArray()
    private val writtenStreams = mutableMapOf<String, ByteArrayOutputStream>()
    private val moves = mutableListOf<Pair<APath<*>, APath<*>>>()
    private val existingPaths = mutableSetOf<String>()

    private fun lookupOf(path: LocalPath, size: Long = content.size.toLong()) = LocalPathLookup(
        lookedUp = path,
        fileType = FileType.FILE,
        size = size,
        modifiedAt = null,
    )

    @Before
    fun setup() {
        writtenStreams.clear()
        moves.clear()
        existingPaths.clear()
        coEvery { gatewaySwitch.lookup(any(), any<LookupOptions>()) } answers {
            @Suppress("UNCHECKED_CAST")
            lookupOf(firstArg<LocalPath>()) as APathLookup<APath<*>>
        }
        coEvery { gatewaySwitch.getFileSystem(any()) } returns FileSystem(freeSpace = 1_000_000L)
        coEvery { gatewaySwitch.createDir(any(), any()) } returns Unit
        // A path only "exists" after a successful move committed it there.
        coEvery { gatewaySwitch.exists(any()) } answers { firstArg<APath<*>>().path in existingPaths }
        coEvery { gatewaySwitch.openInputStream(sourcePath) } answers { ByteArrayInputStream(content) }
        coEvery { gatewaySwitch.openOutputStream(any(), any()) } answers {
            ByteArrayOutputStream().also { writtenStreams[firstArg<LocalPath>().path] = it }
        }
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } coAnswers {
            moves += firstArg<APath<*>>() to secondArg<APath<*>>()
            existingPaths += secondArg<APath<*>>().path
            MoveOutcome.Moved
        }
        coEvery { gatewaySwitch.delete(any<APath<*>>(), any<Boolean>()) } returns true
    }

    private fun operation() = DownloadLocalCopyOperation(
        workspaceId = workspaceId,
        command = ExplorerCommand.DownloadLocalCopy(source = sourcePath, destinationDir = destDir),
        issueHandler = issueHandler,
        gatewaySwitch = gatewaySwitch,
        fileSystemHinter = fileSystemHinter,
    )

    private fun context() = Operation.Context(id = Operation.Id(), startedAt = Clock.System.now())

    private fun List<ExplorerOperation.State>.completed() =
        filterIsInstance<ExplorerOperation.State.Completed>().single()

    @Test
    fun `copies the source via temp sibling and reports the destination`() = runTest2 {
        val states = operation().perform(context()).toList()

        val completed = states.completed()
        completed.error.shouldBeNull()
        val report = completed.report as CopyOperationReport
        report.copiedFiles shouldBe 1
        report.copiedBytes shouldBe content.size.toLong()
        report.affectedPaths.single().path shouldBe destPath

        val (temp, dest) = moves.single()
        dest shouldBe destPath
        temp.name shouldContain ".part"
        temp.name shouldContain destPath.name
        writtenStreams[temp.path]!!.toByteArray() shouldBe content
    }

    @Test
    fun `known source size larger than free space is rejected up front`() = runTest2 {
        coEvery { gatewaySwitch.getFileSystem(any()) } returns FileSystem(freeSpace = 50L)

        shouldThrow<WriteException> {
            operation().perform(context()).toList()
        }

        coVerify(exactly = 0) { gatewaySwitch.openInputStream(any()) }
    }

    @Test
    fun `unknown free space does not block the copy`() = runTest2 {
        coEvery { gatewaySwitch.getFileSystem(any()) } throws IOException("stat not supported")

        val states = operation().perform(context()).toList()

        states.completed().error.shouldBeNull()
        (states.completed().report as CopyOperationReport).copiedBytes shouldBe content.size.toLong()
    }

    @Test
    fun `dismissed conflict prompt aborts with an empty report`() = runTest2 {
        coEvery { gatewaySwitch.exists(destPath) } returns true
        val issue = slot<PathActionIssue.PathAlreadyExists>()
        coEvery { issueHandler.handleIssue(any(), capture(issue)) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Cancel()

        val states = operation().perform(context()).toList()

        issue.captured.canOverwrite shouldBe true
        issue.captured.canSkip shouldBe false
        val completed = states.completed()
        completed.error.shouldBeNull()
        val report = completed.report as CopyOperationReport
        report.copiedFiles shouldBe 0
        report.affectedPaths.shouldBeEmpty()
        coVerify(exactly = 0) { gatewaySwitch.openInputStream(any()) }
    }

    @Test
    fun `overwrite resolution replaces the existing destination`() = runTest2 {
        coEvery { gatewaySwitch.exists(destPath) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()

        val states = operation().perform(context()).toList()

        states.completed().error.shouldBeNull()
        coVerify { gatewaySwitch.delete(destPath, any()) }
        moves.single().second shouldBe destPath
    }

    @Test
    fun `failed move after deleting the existing destination keeps the temp`() = runTest2 {
        coEvery { gatewaySwitch.exists(destPath) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns
            MoveOutcome.NotSupported("test")

        val e = shouldThrow<WriteException> {
            operation().perform(context()).toList()
        }

        e.message shouldContain "data kept as"
        // The deleted destination may only survive as the temp - it must not be cleaned up.
        coVerify(exactly = 0) { gatewaySwitch.delete(match<APath<*>> { it.name.endsWith(".part") }, any<Boolean>()) }
    }

    @Test
    fun `mid-copy failure cleans up the temp file and propagates`() = runTest2 {
        coEvery { gatewaySwitch.openOutputStream(any(), any()) } returns object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
        }
        coEvery { gatewaySwitch.exists(match<APath<*>> { it.name.endsWith(".part") }) } returns true

        shouldThrow<IOException> {
            operation().perform(context()).toList()
        }

        coVerify { gatewaySwitch.delete(match<APath<*>> { it.name.endsWith(".part") }, any()) }
        moves.shouldBeEmpty()
    }

    @Test
    fun `destination appearing mid-download is never deleted without authorization`() = runTest2 {
        // Absent at the conflict check, so no prompt is shown, present by commit time.
        coEvery { gatewaySwitch.exists(destPath) } returns false andThen true
        coEvery { gatewaySwitch.exists(match<APath<*>> { it.name.endsWith(".part") }) } returns true

        val e = shouldThrow<WriteException> {
            operation().perform(context()).toList()
        }

        e.message shouldContain "appeared"
        coVerify(exactly = 0) { issueHandler.handleIssue(any(), any()) }
        coVerify(exactly = 0) { gatewaySwitch.delete(destPath, any<Boolean>()) }
        moves.shouldBeEmpty()
        // Nothing was destroyed, so the temp is a discardable orphan and gets cleaned up.
        coVerify { gatewaySwitch.delete(match<APath<*>> { it.name.endsWith(".part") }, any<Boolean>()) }
    }
}
