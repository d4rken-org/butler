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
import eu.darken.butler.common.files.archive.ArchiveNotSeekableException
import eu.darken.butler.common.files.archive.ArchivePasswordRequiredException
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.files.archive.SequentialAbortException
import eu.darken.butler.common.files.archive.SequentialEntry
import eu.darken.butler.common.files.archive.SequentialOutcome
import eu.darken.butler.common.files.archive.SequentialResult
import eu.darken.butler.common.files.errors.ReadException
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.operations.IssueHandler
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import org.junit.Before
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

class ExtractOperationTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val archiveService = mockk<ArchiveService>()
    private val issueHandler = mockk<IssueHandler>()
    private val fileSystemHinter = mockk<FileSystemHinter>(relaxed = true)

    private val archivePath = LocalPath.build("/test/source/archive.zip")
    private val destDir = LocalPath.build("/test/dest")
    private val baseDir = destDir.child("archive")

    private val writtenStreams = mutableMapOf<String, ByteArrayOutputStream>()
    private val moves = mutableListOf<Pair<APath<*>, APath<*>>>()
    private val existingPaths = mutableSetOf<String>()

    private fun lookupOf(path: LocalPath, isDir: Boolean = false) = LocalPathLookup(
        lookedUp = path,
        fileType = if (isDir) FileType.DIRECTORY else FileType.FILE,
        size = 100L,
        modifiedAt = null,
    )

    private fun isTemp(path: APath<*>) = path.name.endsWith(".part")

    @Before
    fun setup() {
        writtenStreams.clear()
        moves.clear()
        existingPaths.clear()
        coEvery { gatewaySwitch.lookup(any(), any<LookupOptions>()) } answers {
            @Suppress("UNCHECKED_CAST")
            lookupOf(firstArg<LocalPath>()) as APathLookup<APath<*>>
        }
        // A path only "exists" after a successful move committed it there.
        coEvery { gatewaySwitch.exists(any()) } answers { firstArg<APath<*>>().path in existingPaths }
        coEvery { gatewaySwitch.createDir(any(), any()) } returns Unit
        coEvery { gatewaySwitch.canonicalize(any()) } answers { firstArg<LocalPath>() }
        coEvery { gatewaySwitch.openOutputStream(any(), any()) } answers {
            ByteArrayOutputStream().also { writtenStreams[firstArg<LocalPath>().path] = it }
        }
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } coAnswers {
            moves += firstArg<APath<*>>() to secondArg<APath<*>>()
            existingPaths += secondArg<APath<*>>().path
            MoveOutcome.Moved
        }
        coEvery { gatewaySwitch.delete(any<APath<*>>(), any<Boolean>()) } returns true
        coEvery { archiveService.withOutputCommitLock(any(), any()) } coAnswers {
            secondArg<suspend () -> Unit>().invoke()
        }
    }

    private fun meta(vararg segments: String, dir: Boolean = false) = ArchiveEntryMeta(
        segments = segments.toList(),
        rawName = segments.joinToString("/") + if (dir) "/" else "",
        isDirectory = dir,
        size = 4L,
        modifiedAt = null,
    )

    private fun indexOf(vararg metas: ArchiveEntryMeta) = ArchiveIndex(
        container = archivePath,
        format = ArchiveFormat.ZIP,
        fingerprint = "fp",
        entriesBySegments = metas.associateBy { it.segments },
        childrenBySegments = emptyMap(),
        skippedUnsafe = 0,
        skippedSpecial = 0,
    )

    private fun mockSeekable(vararg metas: ArchiveEntryMeta) {
        coEvery { archiveService.index(archivePath) } returns indexOf(*metas)
        coEvery { archiveService.requiresPassword(archivePath) } returns false
        coEvery { archiveService.useEntryStreams(archivePath, any(), any()) } coAnswers {
            val entries = secondArg<Collection<ArchiveEntryMeta>>()
            val action = thirdArg<suspend (ArchiveEntryMeta, InputStream) -> Unit>()
            entries.forEach { action(it, ByteArrayInputStream("data-${it.rawName}".toByteArray())) }
        }
    }

    private fun mockNotSeekable() {
        coEvery { archiveService.index(archivePath) } throws ArchiveNotSeekableException(archivePath)
        coEvery { archiveService.statContainer(archivePath) } returns
            ArchiveService.ContainerStat(size = 1000L, modifiedAt = null)
    }

    private fun command(entries: Set<List<String>>? = null) = ExplorerCommand.Extract(
        archive = archivePath,
        destinationDir = destDir,
        entries = entries,
    )

    private fun operation(command: ExplorerCommand.Extract) = ExtractOperation(
        workspaceId = workspaceId,
        command = command,
        issueHandler = issueHandler,
        gatewaySwitch = gatewaySwitch,
        archiveService = archiveService,
        fileSystemHinter = fileSystemHinter,
    )

    private fun context() = Operation.Context(id = Operation.Id(), startedAt = Clock.System.now())

    private fun List<ExplorerOperation.State>.completed() =
        filterIsInstance<ExplorerOperation.State.Completed>().single()

    @Test
    fun `seekable flow writes entries via temp sibling and atomic move`() = runTest2 {
        mockSeekable(meta("a.txt"), meta("sub", dir = true), meta("sub", "b.txt"))

        val states = operation(command()).perform(context()).toList()

        val completed = states.completed()
        completed.error.shouldBeNull()
        val report = completed.report as ExtractOperationReport
        report.extractedFiles shouldBe 2
        report.skippedEntries shouldBe emptyList()
        report.affectedPaths.map { it.path } shouldContainExactlyInAnyOrder listOf(
            baseDir.child("a.txt"),
            baseDir.child("sub", "b.txt"),
        )

        // Every write goes to a temp sibling that is then moved onto the real name.
        moves.size shouldBe 2
        moves.forEach { (temp, dest) ->
            temp.name shouldContain ".part"
            temp.name shouldContain dest.name
        }
        moves.map { it.second } shouldContainExactlyInAnyOrder listOf(
            baseDir.child("a.txt"),
            baseDir.child("sub", "b.txt"),
        )
        val tempForA = moves.first { it.second == baseDir.child("a.txt") }.first
        writtenStreams[tempForA.path]!!.toString() shouldBe "data-a.txt"
    }

    @Test
    fun `seekable flow skips an existing destination when the user says skip`() = runTest2 {
        mockSeekable(meta("a.txt"), meta("b.txt"))
        coEvery { gatewaySwitch.exists(baseDir.child("a.txt")) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Skip()

        val states = operation(command()).perform(context()).toList()

        val report = states.completed().report as ExtractOperationReport
        report.extractedFiles shouldBe 1
        report.skippedEntries shouldContain "a.txt"
        report.affectedPaths.map { it.path } shouldBe listOf(baseDir.child("b.txt"))
    }

    @Test
    fun `cancel resolution cancels the operation instead of skipping`() = runTest2 {
        mockSeekable(meta("a.txt"))
        coEvery { gatewaySwitch.exists(baseDir.child("a.txt")) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Cancel()

        shouldThrow<CancellationException> {
            operation(command()).perform(context()).toList()
        }

        coVerify(exactly = 0) { gatewaySwitch.openOutputStream(any(), any()) }
        coVerify(exactly = 0) { gatewaySwitch.delete(any<APath<*>>(), any<Boolean>()) }
    }

    @Test
    fun `destination appearing mid-operation is never deleted without authorization`() = runTest2 {
        mockSeekable(meta("a.txt"))
        val destFile = baseDir.child("a.txt")
        // Absent at the conflict check (no prompt), present at commit time.
        coEvery { gatewaySwitch.exists(destFile) } returns false andThen true
        coEvery { gatewaySwitch.exists(match<APath<*>> { isTemp(it) }) } returns true

        val states = operation(command()).perform(context()).toList()

        val completed = states.completed()
        val error = completed.error.shouldBeInstanceOf<WriteException>()
        error.message shouldContain "appeared"
        (completed.report as ExtractOperationReport).extractedFiles shouldBe 0
        coVerify(exactly = 0) { gatewaySwitch.delete(destFile, any<Boolean>()) }
        coVerify(exactly = 0) { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) }
        coVerify { gatewaySwitch.delete(match<APath<*>> { isTemp(it) }, any<Boolean>()) }
    }

    @Test
    fun `failed delete of the existing destination aborts before the move`() = runTest2 {
        mockSeekable(meta("a.txt"))
        val destFile = baseDir.child("a.txt")
        coEvery { gatewaySwitch.exists(destFile) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
        coEvery { gatewaySwitch.delete(destFile, any<Boolean>()) } returns false
        coEvery { gatewaySwitch.exists(match<APath<*>> { isTemp(it) }) } returns true

        val states = operation(command()).perform(context()).toList()

        val completed = states.completed()
        completed.error.shouldBeInstanceOf<WriteException>()
        // Nothing was destroyed: the move never ran and the temp is a discardable orphan.
        coVerify(exactly = 0) { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) }
        coVerify { gatewaySwitch.delete(match<APath<*>> { isTemp(it) }, any<Boolean>()) }
    }

    @Test
    fun `failed move after deleting the existing destination keeps the temp`() = runTest2 {
        mockSeekable(meta("a.txt"))
        val destFile = baseDir.child("a.txt")
        coEvery { gatewaySwitch.exists(destFile) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns
            MoveOutcome.NotSupported("test")

        val states = operation(command()).perform(context()).toList()

        val completed = states.completed()
        val error = completed.error.shouldBeInstanceOf<WriteException>()
        error.message shouldContain "data kept as"
        // The deleted destination may only survive as the temp - it must not be cleaned up.
        coVerify(exactly = 0) { gatewaySwitch.delete(match<APath<*>> { isTemp(it) }, any<Boolean>()) }
    }

    @Test
    fun `move reported as success without a destination is a failure`() = runTest2 {
        mockSeekable(meta("a.txt"))
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns MoveOutcome.Moved
        coEvery { gatewaySwitch.exists(match<APath<*>> { isTemp(it) }) } returns true

        val states = operation(command()).perform(context()).toList()

        val completed = states.completed()
        completed.error.shouldBeInstanceOf<WriteException>()
        (completed.report as ExtractOperationReport).extractedFiles shouldBe 0
        coVerify { gatewaySwitch.delete(match<APath<*>> { isTemp(it) }, any<Boolean>()) }
    }

    @Test
    fun `sequential flow restarts after password prompt with processed ordinals`() = runTest2 {
        mockNotSeekable()
        val processedPerCall = mutableListOf<Set<Int>>()
        val fingerprints = mutableListOf<String?>()
        var calls = 0
        coEvery { archiveService.extractZipSequential(any(), any(), any(), any(), any()) } coAnswers {
            processedPerCall += arg<Set<Int>>(1)
            fingerprints += arg<String?>(2)
            val action = arg<suspend (SequentialEntry, InputStream) -> SequentialOutcome>(4)
            calls++
            if (calls == 1) {
                action(
                    SequentialEntry(ordinal = 0, segments = listOf("a.txt"), rawName = "a.txt", isEncrypted = false),
                    ByteArrayInputStream("alpha".toByteArray()),
                )
                throw ArchivePasswordRequiredException(archivePath)
            } else {
                action(
                    SequentialEntry(ordinal = 1, segments = listOf("b.txt"), rawName = "b.txt", isEncrypted = true),
                    ByteArrayInputStream("beta".toByteArray()),
                )
                SequentialResult(extracted = 1, skippedUnsafe = 0)
            }
        }
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.ArchivePasswordRequired.Resolution.Submit("hunter2")

        val states = operation(command()).perform(context()).toList()

        calls shouldBe 2
        processedPerCall[0] shouldBe emptySet()
        // The entry committed before the password prompt is not re-delivered on restart.
        processedPerCall[1] shouldBe setOf(0)
        val expectedFingerprint = ArchiveService.ContainerStat(size = 1000L, modifiedAt = null).fingerprint
        fingerprints shouldBe listOf(expectedFingerprint, expectedFingerprint)

        val completed = states.completed()
        completed.error.shouldBeNull()
        val report = completed.report as ExtractOperationReport
        report.extractedFiles shouldBe 2
        report.affectedPaths.map { it.path } shouldContainExactlyInAnyOrder listOf(
            baseDir.child("a.txt"),
            baseDir.child("b.txt"),
        )
    }

    @Test
    fun `sequential flow with existing base dir aborts on dismissed merge prompt`() = runTest2 {
        mockNotSeekable()
        coEvery { gatewaySwitch.exists(baseDir) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Cancel()

        val states = operation(command()).perform(context()).toList()

        val completed = states.completed()
        completed.error.shouldBeNull()
        val report = completed.report as ExtractOperationReport
        report.extractedFiles shouldBe 0
        report.affectedPaths shouldBe emptyList()
        coVerify(exactly = 0) { archiveService.extractZipSequential(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `sequential failure surfaces a partial report with the committed entry`() = runTest2 {
        mockNotSeekable()
        coEvery { archiveService.extractZipSequential(any(), any(), any(), any(), any()) } coAnswers {
            val action = arg<suspend (SequentialEntry, InputStream) -> SequentialOutcome>(4)
            action(
                SequentialEntry(ordinal = 0, segments = listOf("a.txt"), rawName = "a.txt", isEncrypted = false),
                ByteArrayInputStream("alpha".toByteArray()),
            )
            throw SequentialAbortException("corrupted stream", archivePath, 1, 0)
        }

        val states = operation(command()).perform(context()).toList()

        val completed = states.completed()
        completed.error.shouldBeInstanceOf<SequentialAbortException>()
        val report = completed.report as ExtractOperationReport
        report.extractedFiles shouldBe 1
        report.affectedPaths.map { it.path } shouldBe listOf(baseDir.child("a.txt"))
    }

    @Test
    fun `entry selection on a non-seekable archive fails the flow`() = runTest2 {
        mockNotSeekable()

        val e = shouldThrow<ReadException> {
            operation(command(entries = setOf(listOf("a.txt")))).perform(context()).toList()
        }

        e.message shouldContain "selection"
        coVerify(exactly = 0) { archiveService.extractZipSequential(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `entries whose canonical parent escapes the destination are skipped`() = runTest2 {
        mockSeekable(meta("sub", "evil.txt"))
        coEvery { gatewaySwitch.canonicalize(match<APath<*>> { it.segments.lastOrNull() == "sub" }) } returns
            LocalPath.build("/outside")

        val states = operation(command()).perform(context()).toList()

        val completed = states.completed()
        completed.error.shouldBeNull()
        val report = completed.report as ExtractOperationReport
        report.extractedFiles shouldBe 0
        report.skippedEntries shouldContain "sub/evil.txt"
        moves shouldBe emptyList()
    }

    @Test
    fun `the path plan targets the archive and lands in the destination folder`() {
        val plan = operation(command()).metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(archivePath)
        plan.destination shouldBe OperationPathPlan.Destination.Container(destDir)
        plan.scopePaths shouldContainExactly listOf(archivePath, destDir)
        plan.representativePath shouldBe archivePath
    }
}
