package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.archive.ArchiveEntryMeta
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.ArchiveIndex
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.toList
import org.junit.Before
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import kotlin.time.Clock

class ExtractOperationTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val archiveService = mockk<ArchiveService>()
    private val issueHandler = mockk<IssueHandler>()
    private val fileSystemHinter = mockk<FileSystemHinter>(relaxed = true)

    private val archivePath = LocalPath.build("/test/archive.zip")
    private val destinationDir = LocalPath.build("/out")
    private val destPath = destinationDir.child("file.txt")

    private val entryMeta = ArchiveEntryMeta(
        segments = listOf("file.txt"),
        rawName = "file.txt",
        isDirectory = false,
        size = 5L,
        modifiedAt = null,
    )

    private fun lookupOf(path: LocalPath, isDir: Boolean = false) = LocalPathLookup(
        lookedUp = path,
        fileType = if (isDir) FileType.DIRECTORY else FileType.FILE,
        size = 5L,
        modifiedAt = null,
    )

    private fun isTemp(path: APath<*>) = path.name.endsWith(".part")

    @Before
    fun setup() {
        coEvery { archiveService.index(archivePath) } returns ArchiveIndex(
            container = archivePath,
            format = ArchiveFormat.ZIP,
            fingerprint = "fp",
            entriesBySegments = mapOf(entryMeta.segments to entryMeta),
            childrenBySegments = emptyMap(),
            skippedUnsafe = 0,
            skippedSpecial = 0,
        )
        coEvery { archiveService.requiresPassword(archivePath) } returns false
        coEvery { archiveService.useEntryStreams(any(), any(), any()) } coAnswers {
            thirdArg<suspend (ArchiveEntryMeta, InputStream) -> Unit>()
                .invoke(entryMeta, ByteArrayInputStream("hello".toByteArray()))
        }
        coEvery { archiveService.withOutputCommitLock(any(), any()) } coAnswers {
            secondArg<suspend () -> Unit>().invoke()
        }

        coEvery { gatewaySwitch.createDir(any(), any()) } returns Unit
        coEvery { gatewaySwitch.canonicalize(any()) } answers { firstArg() }
        coEvery { gatewaySwitch.openOutputStream(any(), any()) } returns ByteArrayOutputStream()
        coEvery { gatewaySwitch.lookup(any(), any<LookupOptions>()) } answers {
            @Suppress("UNCHECKED_CAST")
            lookupOf(firstArg<LocalPath>()) as APathLookup<APath<*>>
        }
        coEvery { gatewaySwitch.exists(any()) } returns false
        coEvery { gatewaySwitch.delete(any<APath<*>>(), any<Boolean>()) } returns true
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns MoveOutcome.Moved
    }

    private fun operation() = ExtractOperation(
        workspaceId = workspaceId,
        command = ExplorerCommand.Extract(
            archive = archivePath,
            destinationDir = destinationDir,
            entries = setOf(listOf("file.txt")),
        ),
        issueHandler = issueHandler,
        gatewaySwitch = gatewaySwitch,
        archiveService = archiveService,
        fileSystemHinter = fileSystemHinter,
    )

    private fun context() = Operation.Context(id = Operation.Id(), startedAt = Clock.System.now())

    private suspend fun performToCompletion(): ExtractOperationReport {
        val states = operation().perform(context()).toList()
        return states.last()
            .shouldBeInstanceOf<ExplorerOperation.State.Completed>()
            .report
            .shouldBeInstanceOf<ExtractOperationReport>()
    }

    @Test
    fun `entry extracts via temp and commit move`() = runTest2 {
        var moved = false
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } answers {
            moved = true
            MoveOutcome.Moved
        }
        coEvery { gatewaySwitch.exists(destPath) } answers { moved }

        val report = performToCompletion()

        report.extractedFiles shouldBe 1
        report.skippedEntries shouldBe emptyList()
        coVerify { gatewaySwitch.move(match<APath<*>> { isTemp(it) }, destPath) }
        coVerify(exactly = 0) { gatewaySwitch.delete(destPath, any()) }
    }

    @Test
    fun `failed delete of the existing destination aborts before the move`() = runTest2 {
        coEvery { gatewaySwitch.exists(destPath) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
        coEvery { gatewaySwitch.delete(destPath, any()) } returns false
        coEvery { gatewaySwitch.exists(match { isTemp(it) }) } returns true

        shouldThrow<WriteException> {
            operation().perform(context()).toList()
        }

        // Nothing was destroyed: the move never ran and the temp is a discardable orphan.
        coVerify(exactly = 0) { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) }
        coVerify { gatewaySwitch.delete(match<APath<*>> { isTemp(it) }, any<Boolean>()) }
    }

    @Test
    fun `failed move after deleting the existing destination keeps the temp`() = runTest2 {
        coEvery { gatewaySwitch.exists(destPath) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
        coEvery { gatewaySwitch.delete(destPath, any()) } returns true
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns
            MoveOutcome.NotSupported("test")

        shouldThrow<WriteException> {
            operation().perform(context()).toList()
        }

        // The deleted destination may only survive as the temp — it must not be cleaned up.
        coVerify(exactly = 0) { gatewaySwitch.delete(match<APath<*>> { isTemp(it) }, any<Boolean>()) }
    }

    @Test
    fun `move reported as success without a destination is a failure`() = runTest2 {
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns MoveOutcome.Moved
        coEvery { gatewaySwitch.exists(destPath) } returns false
        coEvery { gatewaySwitch.exists(match { isTemp(it) }) } returns true

        shouldThrow<WriteException> {
            operation().perform(context()).toList()
        }

        coVerify { gatewaySwitch.delete(match<APath<*>> { isTemp(it) }, any<Boolean>()) }
    }

    @Test
    fun `destination appearing mid-operation is never deleted without authorization`() = runTest2 {
        // Absent at the conflict check (no prompt), present at commit time.
        coEvery { gatewaySwitch.exists(destPath) } returns false andThen true
        coEvery { gatewaySwitch.exists(match { isTemp(it) }) } returns true

        shouldThrow<WriteException> {
            operation().perform(context()).toList()
        }

        coVerify(exactly = 0) { gatewaySwitch.delete(destPath, any()) }
        coVerify(exactly = 0) { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) }
        coVerify { gatewaySwitch.delete(match<APath<*>> { isTemp(it) }, any<Boolean>()) }
    }

    @Test
    fun `cancel resolution cancels the operation instead of skipping`() = runTest2 {
        coEvery { gatewaySwitch.exists(destPath) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Cancel()

        shouldThrow<CancellationException> {
            operation().perform(context()).toList()
        }

        coVerify(exactly = 0) { gatewaySwitch.openOutputStream(any(), any()) }
        coVerify(exactly = 0) { gatewaySwitch.delete(any<APath<*>>(), any<Boolean>()) }
    }

    @Test
    fun `skip resolution leaves the destination untouched`() = runTest2 {
        coEvery { gatewaySwitch.exists(destPath) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Skip()

        val report = performToCompletion()

        report.extractedFiles shouldBe 0
        report.skippedEntries shouldBe listOf("file.txt")
        coVerify(exactly = 0) { gatewaySwitch.openOutputStream(any(), any()) }
        coVerify(exactly = 0) { gatewaySwitch.delete(any<APath<*>>(), any<Boolean>()) }
    }
}
