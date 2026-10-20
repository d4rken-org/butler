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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
    private val addedHints = mutableListOf<APathLookup<*>>()
    private val removedHints = mutableListOf<APathLookup<*>>()

    private fun lookupOf(path: LocalPath, isDir: Boolean = false) = LocalPathLookup(
        lookedUp = path,
        fileType = if (isDir) FileType.DIRECTORY else FileType.FILE,
        size = 100L,
        modifiedAt = null,
    )

    @Suppress("UNCHECKED_CAST")
    private fun typedLookupOf(path: LocalPath, isDir: Boolean = false) =
        lookupOf(path, isDir) as APathLookup<APath<*>>

    private fun Collection<APathLookup<*>>.typed() = map { it.lookedUp to it.fileType }

    private fun isTemp(path: APath<*>) = path.name.endsWith(".part")

    @Before
    fun setup() {
        writtenStreams.clear()
        moves.clear()
        existingPaths.clear()
        addedHints.clear()
        removedHints.clear()
        coEvery { fileSystemHinter.trackPathsAdded(any(), any()) } answers {
            addedHints += secondArg<Collection<APathLookup<*>>>()
        }
        coEvery { fileSystemHinter.trackPathsRemoved(any(), any()) } answers {
            removedHints += secondArg<Collection<APathLookup<*>>>()
        }
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

    /*
     * The subject is what the history row is labelled with. An extraction reports one change per
     * written entry, in archive-enumeration order, so it must never be derived from those.
     */

    @Test
    fun `a successful extraction is about the archive, not an entry inside it`() = runTest2 {
        mockSeekable(meta("aaa.txt"), meta("sub", "bbb.txt"))

        val report = operation(command()).perform(context()).toList().completed().report
            as ExtractOperationReport

        report.subjectPath shouldBe archivePath
        report.affectedPaths.map { it.path } shouldNotContain archivePath
    }

    @Test
    fun `a dismissed password prompt still names the archive`() = runTest2 {
        mockSeekable(meta("a.txt"))
        coEvery { archiveService.requiresPassword(archivePath) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.ArchivePasswordRequired.Resolution.Cancel()

        val report = operation(command()).perform(context()).toList().completed().report
            as ExtractOperationReport

        report.subjectPath shouldBe archivePath
        report.affectedPaths.shouldBeEmpty()
    }

    @Test
    fun `a dismissed merge prompt still names the archive`() = runTest2 {
        mockNotSeekable()
        coEvery { gatewaySwitch.exists(baseDir) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Cancel()

        val report = operation(command()).perform(context()).toList().completed().report
            as ExtractOperationReport

        report.subjectPath shouldBe archivePath
    }

    @Test
    fun `a declined merge into a file at the base path still names the archive`() = runTest2 {
        mockNotSeekable()
        coEvery { gatewaySwitch.exists(baseDir) } returns true
        // A regular file at the base path cannot be merged under, so Skip aborts the extraction.
        coEvery { gatewaySwitch.lookup(baseDir, any<LookupOptions>()) } answers {
            @Suppress("UNCHECKED_CAST")
            lookupOf(baseDir, isDir = false) as APathLookup<APath<*>>
        }
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Skip()

        val report = operation(command()).perform(context()).toList().completed().report
            as ExtractOperationReport

        report.subjectPath shouldBe archivePath
        coVerify(exactly = 0) { archiveService.extractZipSequential(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a partial failure names the archive, not the entry that made it through`() = runTest2 {
        mockNotSeekable()
        coEvery { archiveService.extractZipSequential(any(), any(), any(), any(), any()) } coAnswers {
            val action = arg<suspend (SequentialEntry, InputStream) -> SequentialOutcome>(4)
            action(
                SequentialEntry(ordinal = 0, segments = listOf("a.txt"), rawName = "a.txt", isEncrypted = false),
                ByteArrayInputStream("alpha".toByteArray()),
            )
            throw SequentialAbortException("corrupted stream", archivePath, 1, 0)
        }

        val completed = operation(command()).perform(context()).toList().completed()

        completed.error.shouldBeInstanceOf<SequentialAbortException>()
        val report = completed.report as ExtractOperationReport
        report.affectedPaths.map { it.path } shouldBe listOf(baseDir.child("a.txt"))
        report.subjectPath shouldBe archivePath
    }

    @Test
    fun `an encrypted entry list prompts before indexing and extracts after unlock`() = runTest2 {
        mockSeekable(meta("a.txt"))
        coEvery { archiveService.index(archivePath) } throws
            ArchivePasswordRequiredException(archivePath) andThen indexOf(meta("a.txt"))
        val issues = mutableListOf<PathActionIssue.ArchivePasswordRequired>()
        coEvery { issueHandler.handleIssue(any(), any()) } answers {
            issues += secondArg<PathActionIssue.ArchivePasswordRequired>()
            PathActionIssue.ArchivePasswordRequired.Resolution.Submit("secret")
        }

        val completed = operation(command()).perform(context()).toList().completed()

        completed.error.shouldBeNull()
        issues.map { it.attemptFailed } shouldBe listOf(false)
        (completed.report as ExtractOperationReport).extractedFiles shouldBe 1
    }

    @Test
    fun `a wrong password for an encrypted entry list prompts again as a failed attempt`() = runTest2 {
        mockSeekable(meta("a.txt"))
        coEvery { archiveService.index(archivePath) } throws
            ArchivePasswordRequiredException(archivePath) andThenThrows
            ArchivePasswordRequiredException(archivePath, attemptFailed = true) andThen
            indexOf(meta("a.txt"))
        val issues = mutableListOf<PathActionIssue.ArchivePasswordRequired>()
        coEvery { issueHandler.handleIssue(any(), any()) } answers {
            issues += secondArg<PathActionIssue.ArchivePasswordRequired>()
            PathActionIssue.ArchivePasswordRequired.Resolution.Submit("secret")
        }

        operation(command()).perform(context()).toList().completed().error.shouldBeNull()

        issues.map { it.attemptFailed } shouldBe listOf(false, true)
    }

    @Test
    fun `dismissing the prompt for an encrypted entry list writes nothing`() = runTest2 {
        coEvery { archiveService.index(archivePath) } throws ArchivePasswordRequiredException(archivePath)
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.ArchivePasswordRequired.Resolution.Cancel()

        val completed = operation(command()).perform(context()).toList().completed()

        completed.error.shouldBeNull()
        (completed.report as ExtractOperationReport).subjectPath shouldBe archivePath
        writtenStreams shouldBe emptyMap()
        coVerify(exactly = 0) { archiveService.useEntryStreams(any(), any(), any()) }
    }

    /** First pass delivers `a.txt`, then the password fails; the next pass gets whatever was not delivered. */
    private fun mockPasswordFailingAfterFirstEntry(): MutableList<List<String>> {
        val passes = mutableListOf<List<String>>()
        coEvery { archiveService.useEntryStreams(archivePath, any(), any()) } coAnswers {
            val entries = secondArg<Collection<ArchiveEntryMeta>>().toList()
            val action = thirdArg<suspend (ArchiveEntryMeta, InputStream) -> Unit>()
            passes += entries.map { it.rawName }
            entries.forEach { meta ->
                if (passes.size == 1 && meta.rawName != "a.txt") {
                    throw ArchivePasswordRequiredException(archivePath, attemptFailed = true, mayBeDamaged = true)
                }
                action(meta, ByteArrayInputStream("data-${meta.rawName}".toByteArray()))
            }
        }
        return passes
    }

    @Test
    fun `a password failing mid-extraction re-prompts and resumes with what is left`() = runTest2 {
        mockSeekable(meta("a.txt"), meta("b.txt"))
        val passes = mockPasswordFailingAfterFirstEntry()
        val issues = mutableListOf<PathActionIssue.ArchivePasswordRequired>()
        coEvery { issueHandler.handleIssue(any(), any()) } answers {
            issues += secondArg<PathActionIssue.ArchivePasswordRequired>()
            PathActionIssue.ArchivePasswordRequired.Resolution.Submit("secret")
        }

        val completed = operation(command()).perform(context()).toList().completed()

        completed.error.shouldBeNull()
        passes shouldBe listOf(listOf("a.txt", "b.txt"), listOf("b.txt"))
        issues.single().attemptFailed shouldBe true
        issues.single().mayBeDamaged shouldBe true
        val report = completed.report as ExtractOperationReport
        report.extractedFiles shouldBe 2
        moves.count { it.second.path.endsWith("/a.txt") } shouldBe 1
    }

    @Test
    fun `dismissing the mid-extraction prompt reports the entries left behind`() = runTest2 {
        mockSeekable(meta("a.txt"), meta("b.txt"))
        mockPasswordFailingAfterFirstEntry()
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.ArchivePasswordRequired.Resolution.Cancel()

        val completed = operation(command()).perform(context()).toList().completed()

        completed.error.shouldBeNull()
        val report = completed.report as ExtractOperationReport
        report.extractedFiles shouldBe 1
        report.skippedEntries shouldContain "b.txt"
        report.partialErrorCount shouldBe 1
    }

    @Test
    fun `a failed re-index during the up-front check keeps its flags`() = runTest2 {
        mockSeekable(meta("a.txt"))
        coEvery { archiveService.requiresPassword(archivePath) } throws
            ArchivePasswordRequiredException(archivePath, attemptFailed = true, mayBeDamaged = true) andThen false
        val issues = mutableListOf<PathActionIssue.ArchivePasswordRequired>()
        coEvery { issueHandler.handleIssue(any(), any()) } answers {
            issues += secondArg<PathActionIssue.ArchivePasswordRequired>()
            PathActionIssue.ArchivePasswordRequired.Resolution.Submit("secret")
        }

        operation(command()).perform(context()).toList().completed().error.shouldBeNull()

        issues.single().attemptFailed shouldBe true
        issues.single().mayBeDamaged shouldBe true
    }

    @Test
    fun `a non-seekable 7z fails instead of taking the zip fallback`() = runTest2 {
        val sevenZ = LocalPath.build("/test/source/archive.7z")
        coEvery { archiveService.index(sevenZ) } throws ArchiveNotSeekableException(sevenZ)

        shouldThrow<ArchiveNotSeekableException> {
            operation(command().copy(archive = sevenZ)).perform(context()).toList()
        }

        coVerify(exactly = 0) { archiveService.extractZipSequential(any(), any(), any(), any(), any()) }
    }

    private fun mockSequentialSingleEntry() {
        coEvery { archiveService.extractZipSequential(any(), any(), any(), any(), any()) } coAnswers {
            val action = arg<suspend (SequentialEntry, InputStream) -> SequentialOutcome>(4)
            action(
                SequentialEntry(ordinal = 0, segments = listOf("a.txt"), rawName = "a.txt", isEncrypted = false),
                ByteArrayInputStream("alpha".toByteArray()),
            )
            SequentialResult(extracted = 1, skippedUnsafe = 0)
        }
    }

    @Test
    fun `a whole-archive extraction hints its output folder`() = runTest2 {
        mockSeekable(meta("a.txt"), meta("sub", "b.txt"))
        coEvery { gatewaySwitch.lookup(baseDir, any<LookupOptions>()) } returns typedLookupOf(baseDir, isDir = true)

        val report = operation(command()).perform(context()).toList().completed().report
            as ExtractOperationReport

        addedHints.typed() shouldContainExactlyInAnyOrder listOf(
            baseDir to FileType.DIRECTORY,
            baseDir.child("a.txt") to FileType.FILE,
            baseDir.child("sub", "b.txt") to FileType.FILE,
        )
        report.outputDir shouldBe baseDir
    }

    @Test
    fun `a sequential whole-archive extraction hints its output folder`() = runTest2 {
        mockNotSeekable()
        mockSequentialSingleEntry()
        coEvery { gatewaySwitch.lookup(baseDir, any<LookupOptions>()) } returns typedLookupOf(baseDir, isDir = true)

        val report = operation(command()).perform(context()).toList().completed().report
            as ExtractOperationReport

        addedHints.typed() shouldContainExactlyInAnyOrder listOf(
            baseDir to FileType.DIRECTORY,
            baseDir.child("a.txt") to FileType.FILE,
        )
        report.outputDir shouldBe baseDir
    }

    @Test
    fun `a failed lookup of the output folder still hints the extracted files`() = runTest2 {
        mockSeekable(meta("a.txt"))
        coEvery { gatewaySwitch.lookup(baseDir, any<LookupOptions>()) } throws ReadException("gone", baseDir)

        val completed = operation(command()).perform(context()).toList().completed()

        completed.error.shouldBeNull()
        addedHints.typed() shouldContainExactly listOf(baseDir.child("a.txt") to FileType.FILE)
    }

    @Test
    fun `a password prompt dismissed before writing reports no output folder`() = runTest2 {
        mockSeekable(meta("a.txt"))
        coEvery { archiveService.requiresPassword(archivePath) } returns true
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.ArchivePasswordRequired.Resolution.Cancel()

        val report = operation(command()).perform(context()).toList().completed().report
            as ExtractOperationReport

        report.outputDir.shouldBeNull()
        addedHints.map { it.lookedUp } shouldNotContain baseDir
    }

    @Test
    fun `a dismissed sequential merge prompt reports no output folder`() = runTest2 {
        mockNotSeekable()
        coEvery { gatewaySwitch.exists(baseDir) } returns true
        coEvery { gatewaySwitch.lookup(baseDir, any<LookupOptions>()) } returns typedLookupOf(baseDir, isDir = true)
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Cancel()

        val report = operation(command()).perform(context()).toList().completed().report
            as ExtractOperationReport

        report.outputDir.shouldBeNull()
        addedHints.map { it.lookedUp } shouldNotContain baseDir
    }

    @Test
    fun `a declined sequential merge into a file at the base path reports no output folder`() = runTest2 {
        mockNotSeekable()
        coEvery { gatewaySwitch.exists(baseDir) } returns true
        coEvery { gatewaySwitch.lookup(baseDir, any<LookupOptions>()) } returns typedLookupOf(baseDir)
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Skip()

        val report = operation(command()).perform(context()).toList().completed().report
            as ExtractOperationReport

        report.outputDir.shouldBeNull()
        addedHints.map { it.lookedUp } shouldNotContain baseDir
    }

    @Test
    fun `overwriting a file at the sequential base path removes its row before the folder is added`() = runTest2 {
        mockNotSeekable()
        mockSequentialSingleEntry()
        coEvery { gatewaySwitch.exists(baseDir) } returns true
        // The up-front check sees the file, the lookup after extraction sees the folder that replaced it.
        coEvery { gatewaySwitch.lookup(baseDir, any<LookupOptions>()) } returns
            typedLookupOf(baseDir) andThen typedLookupOf(baseDir, isDir = true)
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()

        val report = operation(command()).perform(context()).toList().completed().report
            as ExtractOperationReport

        coVerifyOrder {
            fileSystemHinter.trackPathsRemoved(
                any(),
                match { it.typed() == listOf(baseDir to FileType.FILE) },
            )
            fileSystemHinter.trackPathsAdded(
                any(),
                match { (baseDir to FileType.DIRECTORY) in it.typed() },
            )
        }
        report.outputDir shouldBe baseDir
    }

    @Test
    fun `a selection hints the folder it created in the destination`() = runTest2 {
        mockSeekable(meta("sub", dir = true), meta("sub", "b.txt"), meta("other.txt"))
        val subDir = destDir.child("sub")
        coEvery { gatewaySwitch.lookup(subDir, any<LookupOptions>()) } returns typedLookupOf(subDir, isDir = true)

        val report = operation(command(entries = setOf(listOf("sub")))).perform(context()).toList().completed()
            .report as ExtractOperationReport

        addedHints.typed() shouldContainExactlyInAnyOrder listOf(
            subDir to FileType.DIRECTORY,
            subDir.child("b.txt") to FileType.FILE,
        )
        report.outputDir.shouldBeNull()
    }

    @Test
    fun `a selection whose first write fails still hints the folder it created`() = runTest2 {
        mockSeekable(meta("sub", "b.txt"))
        val subDir = destDir.child("sub")
        coEvery { gatewaySwitch.lookup(subDir, any<LookupOptions>()) } returns typedLookupOf(subDir, isDir = true)
        coEvery { gatewaySwitch.openOutputStream(any(), any()) } throws WriteException("disk full", subDir)

        val completed = operation(command(entries = setOf(listOf("sub")))).perform(context()).toList().completed()

        completed.error.shouldBeInstanceOf<WriteException>()
        val report = completed.report as ExtractOperationReport
        report.extractedFiles shouldBe 0
        report.affectedPaths.shouldBeEmpty()
        addedHints.typed() shouldContainExactly listOf(subDir to FileType.DIRECTORY)
    }

    @Test
    fun `the whole-archive folder is named after the archive without its extension`() {
        command().wholeArchiveDir shouldBe destDir.child("archive")
        ExplorerCommand.Extract(
            archive = LocalPath.build("/test/source/x.tar.gz"),
            destinationDir = destDir,
        ).wholeArchiveDir shouldBe destDir.child("x")
        ExplorerCommand.Extract(
            archive = LocalPath.build("/test/source/y.7z"),
            destinationDir = destDir,
        ).wholeArchiveDir shouldBe destDir.child("y")
    }

    @Test
    fun `a failing container stat after the output folder exists still hints it`() = runTest2 {
        mockNotSeekable()
        coEvery { archiveService.statContainer(archivePath) } throws ReadException("stat failed", archivePath)
        coEvery { gatewaySwitch.lookup(baseDir, any<LookupOptions>()) } returns typedLookupOf(baseDir, isDir = true)

        val completed = operation(command()).perform(context()).toList().completed()

        completed.error.shouldBeInstanceOf<ReadException>()
        addedHints.typed() shouldContain (baseDir to FileType.DIRECTORY)
    }

    @Test
    fun `replacing a folder with a file removes the folder row before the file is added`() = runTest2 {
        mockSeekable(meta("a"))
        val destA = destDir.child("a")
        coEvery { gatewaySwitch.exists(destA) } returns true
        // The conflict check sees the folder, the lookup after the move sees the file.
        coEvery { gatewaySwitch.lookup(destA, any<LookupOptions>()) } returns
            typedLookupOf(destA, isDir = true) andThen typedLookupOf(destA)
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()

        operation(command(entries = setOf(listOf("a")))).perform(context()).toList().completed()
            .error.shouldBeNull()

        coVerifyOrder {
            fileSystemHinter.trackPathsRemoved(
                any(),
                match { it.typed() == listOf(destA to FileType.DIRECTORY) },
            )
            fileSystemHinter.trackPathsAdded(
                any(),
                match { it.typed() == listOf(destA to FileType.FILE) },
            )
        }
    }

    @Test
    fun `a replacement whose delete fails hints no removal`() = runTest2 {
        mockSeekable(meta("a"))
        val destA = destDir.child("a")
        coEvery { gatewaySwitch.exists(destA) } returns true
        coEvery { gatewaySwitch.lookup(destA, any<LookupOptions>()) } returns typedLookupOf(destA, isDir = true)
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
        coEvery { gatewaySwitch.delete(destA, any<Boolean>()) } returns false

        operation(command(entries = setOf(listOf("a")))).perform(context()).toList().completed()
            .error.shouldBeInstanceOf<WriteException>()

        coVerify(exactly = 0) { fileSystemHinter.trackPathsRemoved(any(), any()) }
    }

    @Test
    fun `a replacement whose move fails after the delete still hints the removal`() = runTest2 {
        mockSeekable(meta("a"))
        val destA = destDir.child("a")
        coEvery { gatewaySwitch.exists(destA) } returns true
        coEvery { gatewaySwitch.lookup(destA, any<LookupOptions>()) } returns typedLookupOf(destA, isDir = true)
        coEvery { issueHandler.handleIssue(any(), any()) } returns
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite()
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns MoveOutcome.NotSupported("test")

        operation(command(entries = setOf(listOf("a")))).perform(context()).toList().completed()
            .error.shouldBeInstanceOf<WriteException>()

        removedHints.typed() shouldContainExactly listOf(destA to FileType.DIRECTORY)
    }

    @Test
    fun `a cancellation while looking up the output folder cancels the operation`() = runTest2 {
        mockSeekable(meta("a.txt"))
        coEvery { gatewaySwitch.lookup(baseDir, any<LookupOptions>()) } throws CancellationException("cancelled")

        shouldThrow<CancellationException> {
            operation(command()).perform(context()).toList()
        }

        coVerify(exactly = 0) { fileSystemHinter.trackPathsAdded(any(), any()) }
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
