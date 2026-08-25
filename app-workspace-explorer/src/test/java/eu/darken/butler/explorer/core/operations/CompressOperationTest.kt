package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.ArchivePasswordStore
import eu.darken.butler.common.files.archive.ArchiveService
import eu.darken.butler.common.files.archive.ArchiveWriteOptions
import eu.darken.butler.common.files.archive.CompressionPreset
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.workspace.core.filesystem.FileSystemHinter
import eu.darken.butler.workspace.core.Workspace
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationPathPlan
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import org.junit.Before
import org.junit.Test
import testhelpers.BaseTest
import testhelpers.coroutine.runTest2
import kotlin.time.Clock

class CompressOperationTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val gatewaySwitch = mockk<GatewaySwitch>()
    private val archiveService = mockk<ArchiveService>()
    private val passwordStore = ArchivePasswordStore()
    private val fileSystemHinter = mockk<FileSystemHinter>(relaxed = true)

    private val sourcePath = LocalPath.build("/test/source.txt")
    private val destinationDir = LocalPath.build("/test")
    private val outputPath = destinationDir.child("out.zip")

    private fun lookupOf(path: LocalPath, isDir: Boolean = false) = LocalPathLookup(
        lookedUp = path,
        fileType = if (isDir) FileType.DIRECTORY else FileType.FILE,
        size = 100L,
        modifiedAt = null,
    )

    @Before
    fun setup() {
        coEvery { gatewaySwitch.lookup(any(), any<LookupOptions>()) } answers {
            @Suppress("UNCHECKED_CAST")
            lookupOf(firstArg<LocalPath>()) as APathLookup<APath<*>>
        }
        coEvery { gatewaySwitch.exists(any()) } returns false
        coEvery { gatewaySwitch.exists(outputPath) } returns false andThen true
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns MoveOutcome.Moved
        coEvery { gatewaySwitch.delete(any<APath<*>>(), any<Boolean>()) } returns true
        coEvery { archiveService.compress(any(), any(), any(), any()) } returns Unit
        coEvery { archiveService.invalidate(any()) } returns Unit
        coEvery { archiveService.withOutputCommitLock(any(), any()) } coAnswers {
            secondArg<suspend () -> Unit>().invoke()
        }
    }

    private fun command(
        password: CharArray? = null,
        overwriteConfirmed: Boolean = false,
    ) = ExplorerCommand.Compress(
        sources = setOf(sourcePath),
        destinationDir = destinationDir,
        archiveName = "out.zip",
        format = ArchiveFormat.ZIP,
        options = ExplorerCommand.Compress.Options(
            preset = CompressionPreset.NORMAL,
            password = password,
        ),
        overwriteConfirmed = overwriteConfirmed,
    )

    private fun operation(command: ExplorerCommand.Compress) = CompressOperation(
        workspaceId = workspaceId,
        command = command,
        gatewaySwitch = gatewaySwitch,
        archiveService = archiveService,
        archivePasswordStore = passwordStore,
        fileSystemHinter = fileSystemHinter,
    )

    private fun context() = Operation.Context(id = Operation.Id(), startedAt = Clock.System.now())

    @Test
    fun `password is wiped after success and seeded under the output path`() = runTest2 {
        val password = "hunter2".toCharArray()
        operation(command(password)).perform(context()).toList()

        password.all { it == Char(0) } shouldBe true

        val seeded = passwordStore.get(outputPath)
        seeded.shouldNotBeNull()
        String(seeded) shouldBe "hunter2"
    }

    @Test
    fun `password is wiped even when compression fails`() = runTest2 {
        coEvery { archiveService.compress(any(), any(), any(), any()) } throws WriteException("boom")
        val password = "hunter2".toCharArray()

        shouldThrow<WriteException> {
            operation(command(password)).perform(context()).toList()
        }

        password.all { it == Char(0) } shouldBe true
        passwordStore.get(outputPath) shouldBe null
    }

    @Test
    fun `password reaches the service intact before being wiped`() = runTest2 {
        val seen = slot<ArchiveWriteOptions>()
        coEvery { archiveService.compress(capture(seen), any(), any(), any()) } answers {
            String(seen.captured.password!!) shouldBe "hunter2"
        }

        operation(command("hunter2".toCharArray())).perform(context()).toList()
        seen.captured.preset shouldBe CompressionPreset.NORMAL
    }

    @Test
    fun `failed move with no prior output discards the temp and seeds nothing`() = runTest2 {
        coEvery { gatewaySwitch.exists(outputPath) } returns false
        // The temp was written by compress(), so cleanup finds it present.
        coEvery { gatewaySwitch.exists(match<APath<*>> { it.name.endsWith(".part") }) } returns true
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns MoveOutcome.NotSupported("test")
        val password = "hunter2".toCharArray()

        shouldThrow<WriteException> {
            operation(command(password)).perform(context()).toList()
        }

        // Nothing was destroyed, so the leftover temp is a discardable orphan.
        coVerify { gatewaySwitch.delete(match<APath<*>> { it.name.endsWith(".part") }, any()) }
        coVerify(exactly = 0) { gatewaySwitch.delete(outputPath, any()) }
        passwordStore.get(outputPath) shouldBe null
        password.all { it == Char(0) } shouldBe true
    }

    @Test
    fun `failed move after deleting the existing archive keeps the temp`() = runTest2 {
        coEvery { gatewaySwitch.exists(outputPath) } returns true
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } returns MoveOutcome.NotSupported("test")

        shouldThrow<WriteException> {
            operation(command(overwriteConfirmed = true)).perform(context()).toList()
        }

        // The existing archive was already deleted, so the temp may be the only surviving copy.
        coVerify { gatewaySwitch.delete(outputPath, any()) }
        coVerify(exactly = 0) { gatewaySwitch.delete(match<APath<*>> { it.name.endsWith(".part") }, any()) }
    }

    @Test
    fun `plain archive evicts a stale password for the output path`() = runTest2 {
        passwordStore.set(outputPath, "stale".toCharArray())

        operation(command(password = null)).perform(context()).toList()

        passwordStore.get(outputPath) shouldBe null
        coVerify { archiveService.invalidate(outputPath) }
    }

    @Test
    fun `command logs never contain the password`() {
        val cmd = command("hunter2".toCharArray())
        cmd.toString() shouldNotContain "hunter2"
        cmd.options.toString() shouldNotContain "hunter2"
    }

    @Test
    fun `seeding happens only after a verified move`() = runTest2 {
        val moveOrder = mutableListOf<String>()
        coEvery { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) } answers {
            moveOrder += "move"
            MoveOutcome.Moved
        }
        val password = "hunter2".toCharArray()

        operation(command(password)).perform(context()).toList()

        val seeded = passwordStore.get(outputPath)
        seeded.shouldNotBeNull()
        moveOrder shouldBe listOf("move")
    }

    @Test
    fun `existing output without overwrite confirmation aborts without deleting it`() = runTest2 {
        coEvery { gatewaySwitch.exists(outputPath) } returns true
        val password = "hunter2".toCharArray()

        shouldThrow<WriteException> {
            operation(command(password, overwriteConfirmed = false)).perform(context()).toList()
        }

        // The existing archive must survive; only our temp is discarded.
        coVerify(exactly = 0) { gatewaySwitch.delete(outputPath, any()) }
        coVerify(exactly = 0) { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) }
        passwordStore.get(outputPath) shouldBe null
        password.all { it == Char(0) } shouldBe true
    }

    @Test
    fun `existing output with overwrite confirmation is replaced`() = runTest2 {
        coEvery { gatewaySwitch.exists(outputPath) } returns true

        operation(command(overwriteConfirmed = true)).perform(context()).toList()

        coVerify { gatewaySwitch.delete(outputPath, any()) }
        coVerify { gatewaySwitch.move(any<APath<*>>(), any<APath<*>>()) }
    }

    @Test
    fun `onDiscarded wipes the password when perform never runs`() {
        val password = "hunter2".toCharArray()
        operation(command(password)).onDiscarded()
        password.all { it == Char(0) } shouldBe true
    }

    @Test
    fun `the path plan points at the archive while the scope keeps the destination folder`() {
        val plan = operation(command()).metadata.pathPlan!!

        plan.targets shouldContainExactly listOf(sourcePath)
        plan.destination shouldBe OperationPathPlan.Destination.RequestedTarget(outputPath)
        plan.scopePaths shouldContainExactly listOf(sourcePath, destinationDir)
        plan.representativePath shouldBe sourcePath
    }

    @Test
    fun `an alias extension is not doubled onto the archive path`() {
        val aliased = ExplorerCommand.Compress(
            sources = setOf(sourcePath),
            destinationDir = destinationDir,
            archiveName = "out.tgz",
            format = ArchiveFormat.TAR_GZ,
        )

        operation(aliased).metadata.pathPlan!!.destination shouldBe
            OperationPathPlan.Destination.RequestedTarget(destinationDir.child("out.tgz"))
    }
}
