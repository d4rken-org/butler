package eu.darken.butler.common.files.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.errors.WriteException
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** `runBlocking`, not `runTest`: virtual time would skip the waits the code under test needs on a real FUSE mount. */
@RunWith(AndroidJUnit4::class)
class LocalPathMoveDeviceTest {

    @get:Rule val storage = DeviceStorageRule()

    private val ops = LocalFileSystemOps(ownershipResolver = mockk<OwnershipResolver>(relaxed = true))

    private fun deterministicBytes(size: Int, seed: Int = 0) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private fun File.fileContents(): Map<String, List<Byte>> = walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(this).path to it.readBytes().toList() }

    @Test
    fun crossFilesystemFileMove() = runBlocking<Unit> {
        val content = deterministicBytes(300 * 1024)
        val source = File(storage.privateRoot, "data.bin").apply { writeBytes(content) }

        val result = LocalPath.build(source)
            .move(ops, LocalPath.build(storage.sharedRoot), options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        result.bytesMoved shouldBe content.size.toLong()
        val moved = File(storage.sharedRoot, "data.bin")
        moved.exists() shouldBe true
        moved.readBytes().contentEquals(content) shouldBe true
        source.exists() shouldBe false
    }

    @Test
    fun crossFilesystemFolderMove() = runBlocking<Unit> {
        val tree = File(storage.privateRoot, "tree")
        File(tree, "sub/deeper").mkdirs() shouldBe true
        File(tree, "a.txt").writeBytes(deterministicBytes(4 * 1024, seed = 1))
        File(tree, "sub/b.txt").writeBytes(deterministicBytes(8 * 1024, seed = 2))
        File(tree, "sub/deeper/c.txt").writeBytes(deterministicBytes(16 * 1024, seed = 3))
        val expected = tree.fileContents()

        LocalPath.build(tree)
            .move(ops, LocalPath.build(storage.sharedRoot), options = MoveAction.Options(attemptAtomicMove = false))
            .last() as MoveAction.State.Completed<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>

        File(storage.sharedRoot, "tree").fileContents() shouldBe expected
        expected.keys shouldBe setOf("a.txt", "sub/b.txt", "sub/deeper/c.txt")
        tree.exists() shouldBe false
    }

    @Test
    fun symlinkMoveOntoSharedStorageKeepsSource() = runBlocking<Unit> {
        val target = File(storage.privateRoot, "target.txt").apply { writeText("target") }
        val link = File(storage.privateRoot, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        shouldThrow<WriteException> {
            LocalPath.build(link)
                .move(
                    ops,
                    LocalPath.build(storage.sharedRoot),
                    options = MoveAction.Options(attemptAtomicMove = false),
                    onIssue = null,
                )
                .collect()
        }

        Files.isSymbolicLink(link.toPath()) shouldBe true
        Files.exists(File(storage.sharedRoot, "link").toPath(), LinkOption.NOFOLLOW_LINKS) shouldBe false
    }

    private fun skippingIssueHandler(
        issues: MutableList<PathActionIssue>,
    ): suspend (PathActionIssue) -> PathActionIssue.Resolution = { issue ->
        issues.add(issue)
        when (issue) {
            is PathActionIssue.PathAlreadyExists -> PathActionIssue.PathAlreadyExists.Resolution.Skip()
            is PathActionIssue.InsufficientPermission -> PathActionIssue.InsufficientPermission.Resolution.Skip()
            is PathActionIssue.InsufficientSpace -> PathActionIssue.InsufficientSpace.Resolution.Cancel()
            is PathActionIssue.UnknownError -> PathActionIssue.UnknownError.Resolution.Skip()
            is PathActionIssue.ArchivePasswordRequired -> PathActionIssue.ArchivePasswordRequired.Resolution.Skip()
            is PathActionIssue.TrashSizeLimitExceeded -> PathActionIssue.TrashSizeLimitExceeded.Resolution.Cancel()
            is PathActionIssue.TrashNotSupported -> PathActionIssue.TrashNotSupported.Resolution.Skip
            is PathActionIssue.TrashMoveFailed -> PathActionIssue.TrashMoveFailed.Resolution.Skip
        }
    }

    private fun evidence(error: Throwable?, issues: List<PathActionIssue>) = buildString {
        appendLine("error=${error?.stackTraceToString()}")
        appendLine("issues=$issues")
        append("sharedTree=${storage.sharedRoot.walkTopDown().map { it.relativeTo(storage.sharedRoot).path }.toList()}")
    }

    @Test
    fun caseOnlyFileRenameOnSharedStorage() = runBlocking<Unit> {
        val content = deterministicBytes(4 * 1024, seed = 4)
        val source = File(storage.sharedRoot, "a.txt").apply { writeBytes(content) }
        val issues = mutableListOf<PathActionIssue>()

        val error = runCatching {
            LocalPath.build(source)
                .move(
                    ops,
                    LocalPath.build(storage.sharedRoot, "A.txt"),
                    options = MoveAction.Options(attemptAtomicMove = false),
                    onIssue = skippingIssueHandler(issues),
                )
                .collect()
        }.exceptionOrNull()

        withClue(evidence(error, issues)) {
            error shouldBe null
            issues shouldBe emptyList()
            storage.sharedRoot.list()!!.toList() shouldBe listOf("A.txt")
            File(storage.sharedRoot, "A.txt").readBytes().contentEquals(content) shouldBe true
        }
    }

    @Test
    fun caseOnlyFolderRenameOnSharedStorage() = runBlocking<Unit> {
        val folder = File(storage.sharedRoot, "photos")
        folder.mkdirs() shouldBe true
        val content = deterministicBytes(4 * 1024, seed = 5)
        File(folder, "file.txt").writeBytes(content)
        val issues = mutableListOf<PathActionIssue>()

        val error = runCatching {
            LocalPath.build(folder)
                .move(
                    ops,
                    LocalPath.build(storage.sharedRoot, "Photos"),
                    options = MoveAction.Options(attemptAtomicMove = false),
                    onIssue = skippingIssueHandler(issues),
                )
                .collect()
        }.exceptionOrNull()

        withClue(evidence(error, issues)) {
            error shouldBe null
            issues shouldBe emptyList()
            storage.sharedRoot.list()!!.toList() shouldBe listOf("Photos")
            val renamed = File(storage.sharedRoot, "Photos")
            renamed.list()!!.toList() shouldBe listOf("file.txt")
            File(renamed, "file.txt").readBytes().contentEquals(content) shouldBe true
        }
    }
}
