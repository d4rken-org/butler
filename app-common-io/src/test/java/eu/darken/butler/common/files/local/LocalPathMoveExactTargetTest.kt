package eu.darken.butler.common.files.local

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.actions.PathActionIssue
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File

/**
 * Renaming onto a taken name: with [MoveAction.Options.exactTarget] the destination is the final
 * path, so an existing folder there is a conflict instead of a folder to move into.
 */
class LocalPathMoveExactTargetTest : BaseTest() {

    private val ops = LocalFileSystemOps(
        ownershipResolver = mockk<OwnershipResolver>(relaxed = true),
    )

    private val issues = mutableListOf<PathActionIssue>()

    private fun answering(
        resolution: PathActionIssue.PathAlreadyExists.Resolution,
        onConflict: () -> Unit = {},
    ): suspend (PathActionIssue) -> PathActionIssue.Resolution = { issue ->
        issues.add(issue)
        when (issue) {
            is PathActionIssue.PathAlreadyExists -> {
                onConflict()
                resolution
            }

            else -> error("Unexpected issue: $issue")
        }
    }

    /** Relative path of every entry below this folder, folders end in `/`, files carry their content. */
    private fun File.tree(): Set<String> = walkTopDown()
        .filter { it != this }
        .map { entry ->
            val relative = entry.relativeTo(this).invariantSeparatorsPath
            if (entry.isDirectory) "$relative/" else "$relative=${entry.readText()}"
        }
        .toSet()

    private suspend fun Collection<File>.moveTo(
        destination: File,
        exactTarget: Boolean,
        resolution: PathActionIssue.PathAlreadyExists.Resolution,
        attemptAtomicMove: Boolean = true,
        onConflict: () -> Unit = {},
    ) = map { LocalPath.build(it) }.move(
        ops,
        LocalPath.build(destination),
        options = MoveAction.Options(attemptAtomicMove = attemptAtomicMove, exactTarget = exactTarget),
        onIssue = answering(resolution, onConflict),
    ).last().shouldBeInstanceOf<MoveAction.State.Completed<*, *, *, *>>()

    private fun fileOntoFolder(root: File): Pair<File, File> {
        val parent = File(root, "parent").apply { mkdirs() }
        val source = File(parent, "a.txt").apply { writeText("source") }
        val folder = File(parent, "Photos").apply { mkdirs() }
        File(folder, "keep.txt").writeText("kept")
        return source to folder
    }

    @Test
    fun `file onto an existing folder conflicts with exactly that folder`(@TempDir tempDir: File) = runTest {
        val (source, folder) = fileOntoFolder(tempDir)

        listOf(source).moveTo(folder, exactTarget = true, resolution = PathActionIssue.PathAlreadyExists.Resolution.Skip())

        val issue = issues.single().shouldBeInstanceOf<PathActionIssue.PathAlreadyExists>()
        issue.destination.lookedUp shouldBe LocalPath.build(folder)
        issue.canMerge shouldBe false
    }

    @Test
    fun `file onto an existing folder - skip leaves both alone`(@TempDir tempDir: File) = runTest {
        val (source, folder) = fileOntoFolder(tempDir)

        listOf(source).moveTo(folder, exactTarget = true, resolution = PathActionIssue.PathAlreadyExists.Resolution.Skip())

        source.readText() shouldBe "source"
        folder.tree() shouldBe setOf("keep.txt=kept")
    }

    @Test
    fun `file onto an existing folder - overwrite replaces the folder with the file`(@TempDir tempDir: File) =
        runTest {
            val (source, folder) = fileOntoFolder(tempDir)

            listOf(source).moveTo(
                folder,
                exactTarget = true,
                resolution = PathActionIssue.PathAlreadyExists.Resolution.Overwrite(),
            )

            source.exists() shouldBe false
            folder.isFile shouldBe true
            folder.readText() shouldBe "source"
            folder.parentFile.tree() shouldBe setOf("Photos=source")
        }

    @Test
    fun `file onto an existing folder - rename source puts the file beside the folder`(@TempDir tempDir: File) =
        runTest {
            val (source, folder) = fileOntoFolder(tempDir)

            listOf(source).moveTo(
                folder,
                exactTarget = true,
                resolution = PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Photos.txt"),
            )

            folder.parentFile.tree() shouldBe setOf("Photos/", "Photos/keep.txt=kept", "Photos.txt=source")
        }

    @Test
    fun `file onto an existing folder without exactTarget moves into it`(@TempDir tempDir: File) = runTest {
        val (source, folder) = fileOntoFolder(tempDir)

        listOf(source).moveTo(folder, exactTarget = false, resolution = PathActionIssue.PathAlreadyExists.Resolution.Skip())

        issues.shouldBeEmpty()
        source.exists() shouldBe false
        folder.tree() shouldBe setOf("keep.txt=kept", "a.txt=source")
    }

    private fun folderOntoFolder(root: File): Pair<File, File> {
        val parent = File(root, "parent").apply { mkdirs() }
        val source = File(parent, "Albums").apply { mkdirs() }
        File(source, "top.txt").writeText("top")
        File(source, "sub").mkdirs()
        File(source, "sub/inner.txt").writeText("inner")
        val folder = File(parent, "Photos").apply { mkdirs() }
        File(folder, "only.txt").writeText("only")
        return source to folder
    }

    private val sourceTree = setOf("top.txt=top", "sub/", "sub/inner.txt=inner")
    private val folderTree = setOf("only.txt=only")

    private suspend fun moveFolderOntoFolder(
        root: File,
        resolution: PathActionIssue.PathAlreadyExists.Resolution,
    ): Pair<File, File> {
        val (source, folder) = folderOntoFolder(root)
        var sourceAtConflict: Set<String>? = null
        var folderAtConflict: Set<String>? = null

        listOf(source).moveTo(
            folder,
            exactTarget = true,
            resolution = resolution,
            attemptAtomicMove = true,
            onConflict = {
                sourceAtConflict = source.tree()
                folderAtConflict = folder.tree()
            },
        )

        val issue = issues.single().shouldBeInstanceOf<PathActionIssue.PathAlreadyExists>()
        issue.destination.lookedUp shouldBe LocalPath.build(folder)
        issue.canMerge shouldBe true
        sourceAtConflict shouldBe sourceTree
        folderAtConflict shouldBe folderTree
        return source to folder
    }

    @Test
    fun `folder onto an existing folder - skip leaves both trees intact`(@TempDir tempDir: File) = runTest {
        val (source, folder) = moveFolderOntoFolder(tempDir, PathActionIssue.PathAlreadyExists.Resolution.Skip())

        source.tree() shouldBe sourceTree
        folder.tree() shouldBe folderTree
    }

    @Test
    fun `folder onto an existing folder - merge keeps the folder's own children`(@TempDir tempDir: File) = runTest {
        val (source, folder) = moveFolderOntoFolder(tempDir, PathActionIssue.PathAlreadyExists.Resolution.Merge())

        source.exists() shouldBe false
        folder.tree() shouldBe sourceTree + folderTree
    }

    @Test
    fun `folder onto an existing folder - overwrite drops the folder's own children`(@TempDir tempDir: File) =
        runTest {
            val (source, folder) = moveFolderOntoFolder(tempDir, PathActionIssue.PathAlreadyExists.Resolution.Overwrite())

            source.exists() shouldBe false
            folder.tree() shouldBe sourceTree
        }

    @Test
    fun `folder onto an existing folder - rename source puts the tree beside the folder`(@TempDir tempDir: File) =
        runTest {
            val (source, folder) = moveFolderOntoFolder(
                tempDir,
                PathActionIssue.PathAlreadyExists.Resolution.RenameSource("Photos (1)"),
            )

            source.exists() shouldBe false
            folder.tree() shouldBe folderTree
            File(folder.parentFile, "Photos (1)").tree() shouldBe sourceTree
        }

    @Test
    fun `exactTarget is ignored for multiple sources`(@TempDir tempDir: File) = runTest {
        suspend fun scenario(root: File, exactTarget: Boolean): Set<String> {
            val parent = File(root, "parent").apply { mkdirs() }
            File(parent, "a.txt").writeText("a")
            File(parent, "Albums").mkdirs()
            File(parent, "Albums/b.txt").writeText("b")
            val folder = File(parent, "Photos").apply { mkdirs() }
            File(folder, "keep.txt").writeText("kept")

            listOf(File(parent, "a.txt"), File(parent, "Albums")).moveTo(
                folder,
                exactTarget = exactTarget,
                resolution = PathActionIssue.PathAlreadyExists.Resolution.Skip(),
            )
            return parent.tree()
        }

        val plain = scenario(File(tempDir, "plain"), exactTarget = false)
        val exact = scenario(File(tempDir, "exact"), exactTarget = true)

        issues.shouldBeEmpty()
        exact shouldBe plain
        exact shouldBe setOf("Photos/", "Photos/keep.txt=kept", "Photos/a.txt=a", "Photos/Albums/", "Photos/Albums/b.txt=b")
    }

    @Test
    fun `exactTarget does not change a conflict with an existing file`(@TempDir tempDir: File) = runTest {
        val resolutions = listOf(
            PathActionIssue.PathAlreadyExists.Resolution.Skip(),
            PathActionIssue.PathAlreadyExists.Resolution.Overwrite(),
            PathActionIssue.PathAlreadyExists.Resolution.RenameSource("c.txt"),
        )

        resolutions.forEachIndexed { index, resolution ->
            val outcomes = listOf(false, true).map { exactTarget ->
                val parent = File(tempDir, "$index-$exactTarget").apply { mkdirs() }
                val source = File(parent, "a.txt").apply { writeText("source") }
                val existing = File(parent, "b.txt").apply { writeText("existing") }
                issues.clear()

                listOf(source).moveTo(existing, exactTarget = exactTarget, resolution = resolution)

                val issue = issues.single().shouldBeInstanceOf<PathActionIssue.PathAlreadyExists>()
                issue.destination.lookedUp.name to parent.tree()
            }

            outcomes shouldHaveSize 2
            outcomes[1] shouldBe outcomes[0]
            outcomes[1].first shouldBe "b.txt"
        }
    }
}
