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
import eu.darken.butler.common.files.archive.ArchiveDiskCache
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.ArchivePasswordStore
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.files.archive.ArchiveWriteOptions
import eu.darken.butler.common.files.archive.CompressionPreset
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.saver.core.ContentUriHelper
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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.TestDispatcherProvider
import testhelpers.coroutine.runTest2
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

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

    /** Content of the files the operation wrote, following renames and deletes like [present]. */
    private val written = mutableMapOf<String, ByteArrayOutputStream>()

    private val archiveService = mockk<ArchiveService>()
    private val realArchiveService = ArchiveService(
        dispatcherProvider = TestDispatcherProvider(),
        gatewaySwitchLazy = { gatewaySwitch },
        diskCache = mockk<ArchiveDiskCache>(relaxed = true),
        passwordStore = ArchivePasswordStore(),
    )

    private val bundleTarget = targetDirectory.child("app.apks")
    private val memberNames = listOf("base.apk", "split_config.de.apk", "split_config.xxhdpi.apk")
    private val memberUris = memberNames.map { mockk<Uri>() }
    private val memberBytes = memberNames.mapIndexed { index, name ->
        Random(index).nextBytes(12_000) + name.toByteArray()
    }

    private fun isStagingOf(path: APath<*>, filename: String) =
        path.name.startsWith(".") && path.name.endsWith(".part.$filename")

    private fun isBackupOf(path: APath<*>, filename: String) =
        path.name.startsWith(".") && path.name.endsWith(".backup.$filename")

    private fun stagingLeftovers(filename: String) = present.filter { isStagingOf(LocalPath.build(it), filename) }

    private fun lookupOf(path: LocalPath) = LocalPathLookup(
        lookedUp = path,
        fileType = FileType.FILE,
        size = written[path.path]?.size()?.toLong() ?: 4L,
        modifiedAt = null,
    )

    private fun bundleSource() = SaveFilesOperation.Command.SourceFile(
        uri = sourceUri,
        filename = bundleTarget.name,
        size = memberBytes.sumOf { it.size.toLong() },
        bundleMembers = memberNames.indices.map { index ->
            ContentUriHelper.BundleMember(
                uri = memberUris[index],
                entryName = memberNames[index],
                size = memberBytes[index].size.toLong(),
            )
        },
    )

    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> = ZipInputStream(bytes.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.map { it.name to zip.readBytes() }.toList()
    }

    @Before
    fun setup() {
        every { resolver.openInputStream(any()) } returns ByteArrayInputStream("data".toByteArray())
        memberUris.forEachIndexed { index, uri ->
            every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(memberBytes[index]) }
        }

        coEvery { archiveService.compress(any(), any(), any(), any()) } coAnswers {
            realArchiveService.compress(arg(0), arg(1), arg(2), arg(3))
        }
        coEvery { archiveService.invalidate(any()) } just Runs

        present.clear()
        present += targetPath.path
        written.clear()

        coEvery { gatewaySwitch.exists(any()) } answers { firstArg<APath<*>>().path in present }
        coEvery { gatewaySwitch.lookup(any(), any<LookupOptions>()) } answers {
            @Suppress("UNCHECKED_CAST")
            lookupOf(firstArg<LocalPath>()) as APathLookup<APath<*>>
        }
        coEvery { gatewaySwitch.createFile(any(), any()) } answers {
            present += firstArg<APath<*>>().path
            Unit
        }
        // Create-on-write, like every gateway
        coEvery { gatewaySwitch.openOutputStream(any(), any()) } answers {
            val path = firstArg<APath<*>>().path
            present += path
            ByteArrayOutputStream().also { written[path] = it }
        }
        coEvery { gatewaySwitch.delete(any<APath<*>>(), any<Boolean>()) } answers {
            present -= firstArg<APath<*>>().path
            written -= firstArg<APath<*>>().path
            true
        }
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } answers {
            present -= firstArg<APath<*>>().path
            present += secondArg<APath<*>>().path
            written.remove(firstArg<APath<*>>().path)?.let { written[secondArg<APath<*>>().path] = it }
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

    private fun operation(filenames: List<String> = listOf("file.txt")) =
        operationOf(filenames.map { SaveFilesOperation.Command.SourceFile(sourceUri, it, 4L) })

    private fun operationOf(sources: List<SaveFilesOperation.Command.SourceFile>) = SaveFilesOperation(
        workspaceId = workspaceId,
        command = SaveFilesOperation.Command(
            sources = sources,
            targetDirectory = targetDirectory,
        ),
        context = context,
        gatewaySwitch = gatewaySwitch,
        issueHandler = issueHandler,
        archiveService = archiveService,
    )

    private suspend fun performToReport(filenames: List<String> = listOf("file.txt")): SaveFilesReport =
        performToReport(operation(filenames))

    private suspend fun performToReport(operation: SaveFilesOperation): SaveFilesReport {
        val states = operation
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

    @Test
    fun `the operation is active again right after the conflict prompt is answered`() = runTest2 {
        val stagingGate = CompletableDeferred<Unit>()
        coEvery { gatewaySwitch.createFile(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>()
            if (isStagingOf(path, "file.txt")) stagingGate.await()
            present += path.path
            Unit
        }

        val states = mutableListOf<Operation.State>()
        val job = launch {
            operation()
                .perform(Operation.Context(id = Operation.Id(), startedAt = Clock.System.now()))
                .collect { states += it }
        }
        advanceUntilIdle()

        states.filterIsInstance<SaveFilesOperation.State.Waiting>()
            .single().issue.shouldBeInstanceOf<PathActionIssue.PathAlreadyExists>()
        withClue("states while the staged write is pending: $states") {
            states.last().shouldBeInstanceOf<SaveFilesOperation.State.Active>()
        }

        stagingGate.complete(Unit)
        withTimeout(10.seconds) { job.join() }

        states.last().shouldBeInstanceOf<SaveFilesOperation.State.Completed>()
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

    @Test
    fun `a plain source is still copied byte for byte`() = runTest2 {
        present.clear()
        val content = Random(42).nextBytes(20_000)
        every { resolver.openInputStream(sourceUri) } answers { ByteArrayInputStream(content) }

        val report = performToReport()

        report.results.single().shouldBeInstanceOf<SaveFilesReport.FileResult.Success>().bytes shouldBe 20_000L
        written.getValue(targetPath.path).toByteArray() shouldBe content
        coVerify(exactly = 0) { archiveService.compress(any(), any(), any(), any()) }
    }

    @Test
    fun `a bundle is zipped onto a staging sibling and committed to its target`() = runTest2 {
        val report = performToReport(operationOf(listOf(bundleSource())))

        val success = report.results.single().shouldBeInstanceOf<SaveFilesReport.FileResult.Success>()
        success.savedPath shouldBe bundleTarget

        val entries = slot<List<ArchiveService.WriteEntry>>()
        coVerify {
            archiveService.compress(
                match<ArchiveWriteOptions> { it.format == ArchiveFormat.ZIP && it.preset == CompressionPreset.FAST },
                match<APath<*>> { isStagingOf(it, "app.apks") },
                capture(entries),
                any(),
            )
        }
        entries.captured.map { it.name } shouldContainExactly memberNames
        coVerify { gatewaySwitch.move(match<APath<*>> { isStagingOf(it, "app.apks") }, bundleTarget) }

        val archive = written.getValue(bundleTarget.path).toByteArray()
        unzip(archive).map { it.first } shouldContainExactly memberNames
        unzip(archive).map { it.second.toList() } shouldContainExactly memberBytes.map { it.toList() }
        success.bytes shouldBe archive.size.toLong()

        coVerify { archiveService.invalidate(bundleTarget) }
        verify(exactly = 0) { resolver.openInputStream(sourceUri) }
        stagingLeftovers("app.apks").shouldBeEmpty()
    }

    private fun failingStream() = object : InputStream() {
        override fun read(): Int = throw IOException("Provider went away")
        override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("Provider went away")
    }

    @Test
    fun `a bundle whose member fails to read leaves nothing under the target name`() = runTest2 {
        every { resolver.openInputStream(memberUris[1]) } answers { failingStream() }

        val report = performToReport(operationOf(listOf(bundleSource())))

        report.results.single().shouldNotBeInstanceOf<SaveFilesReport.FileResult.Success>()
        present shouldBe setOf(targetPath.path)
        stagingLeftovers("app.apks").shouldBeEmpty()
        coVerify(exactly = 0) { gatewaySwitch.move(any<APath<*>>(), bundleTarget) }
    }

    @Test
    fun `a bundle overwrite whose member fails to read keeps the existing target`() = runTest2 {
        present += bundleTarget.path
        written[bundleTarget.path] = ByteArrayOutputStream().apply { write("old archive".toByteArray()) }
        every { resolver.openInputStream(memberUris[1]) } answers { failingStream() }

        val report = performToReport(operationOf(listOf(bundleSource())))

        report.results.single().shouldNotBeInstanceOf<SaveFilesReport.FileResult.Success>()
        present shouldBe setOf(targetPath.path, bundleTarget.path)
        written.getValue(bundleTarget.path).toString() shouldBe "old archive"
        coVerify(exactly = 0) { gatewaySwitch.move(bundleTarget, any<APath<*>>()) }
    }

    @Test
    fun `a bundle cancelled mid-write leaves nothing under the target name`() = runTest2 {
        lateinit var job: Job
        every { resolver.openInputStream(memberUris[1]) } answers {
            object : InputStream() {
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    job.cancel()
                    b[off] = 1
                    return 1
                }
            }
        }

        job = launch {
            operationOf(listOf(bundleSource()))
                .perform(Operation.Context(id = Operation.Id(), startedAt = Clock.System.now()))
                .collect {}
        }
        job.join()

        job.isCancelled shouldBe true
        present shouldBe setOf(targetPath.path)
        stagingLeftovers("app.apks").shouldBeEmpty()
    }
}
