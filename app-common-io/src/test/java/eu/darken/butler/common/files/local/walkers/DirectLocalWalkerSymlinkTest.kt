package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.nio.file.Files

/**
 * Real-filesystem coverage for [DirectLocalWalker]'s symlink following. Uses a temp dir + real symlinks
 * because the in-memory mock can't reproduce symlink-followed listing or canonical-path resolution.
 */
class DirectLocalWalkerSymlinkTest : BaseTest() {

    private val fileSystemOps = LocalFileSystemOps(mockk<OwnershipResolver>(relaxed = true))

    private suspend fun walkRelative(start: File, followSymlinks: Boolean): List<String> {
        val prefix = start.path + "/"
        return DirectLocalWalker(
            fileSystemOps = fileSystemOps,
            start = LocalPath.build(start),
            lookupOptions = LookupOptions(),
            followSymlinks = followSymlinks,
        ).toList().map { it.lookedUp.path.removePrefix(prefix) }
    }

    @Test
    fun `followSymlinks=false emits a symlinked directory as a leaf and does not follow it`(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "realdir").apply { mkdirs() }
        File(realDir, "file.txt").writeText("x")
        Files.createSymbolicLink(File(tempDir, "link").toPath(), realDir.toPath())

        walkRelative(tempDir, followSymlinks = false) shouldContainExactlyInAnyOrder listOf(
            "realdir", "realdir/file.txt", "link",
        )
    }

    @Test
    fun `followSymlinks=true descends into a symlinked directory`(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "realdir").apply { mkdirs() }
        File(realDir, "file.txt").writeText("x")
        Files.createSymbolicLink(File(tempDir, "link").toPath(), realDir.toPath())

        walkRelative(tempDir, followSymlinks = true) shouldContainExactlyInAnyOrder listOf(
            "realdir", "realdir/file.txt", "link", "link/file.txt",
        )
    }

    @Test
    fun `followSymlinks=true does not descend a symlink to a file`(@TempDir tempDir: File) = runTest {
        File(tempDir, "real.txt").writeText("x")
        Files.createSymbolicLink(File(tempDir, "flink").toPath(), File(tempDir, "real.txt").toPath())

        walkRelative(tempDir, followSymlinks = true) shouldContainExactlyInAnyOrder listOf(
            "real.txt", "flink",
        )
    }

    @Test
    fun `followSymlinks=true does not descend a broken symlink`(@TempDir tempDir: File) = runTest {
        Files.createSymbolicLink(File(tempDir, "broken").toPath(), File(tempDir, "missing").toPath())
        File(tempDir, "real.txt").writeText("x")

        walkRelative(tempDir, followSymlinks = true) shouldContainExactlyInAnyOrder listOf(
            "broken", "real.txt",
        )
    }

    @Test
    fun `followSymlinks=true terminates on a symlink loop`(@TempDir tempDir: File) = runTest {
        val sub = File(tempDir, "sub").apply { mkdirs() }
        Files.createSymbolicLink(File(sub, "loop").toPath(), sub.toPath()) // points at its own parent

        walkRelative(tempDir, followSymlinks = true) shouldContainExactlyInAnyOrder listOf(
            "sub", "sub/loop", "sub/loop/loop",
        )
    }

    @Test
    fun `followSymlinks=true follows a start path that is a symlink to a directory`(@TempDir tempDir: File) = runTest {
        val realDir = File(tempDir, "realdir").apply { mkdirs() }
        File(realDir, "file.txt").writeText("x")
        val linkStart = File(tempDir, "linkstart")
        Files.createSymbolicLink(linkStart.toPath(), realDir.toPath())

        walkRelative(linkStart, followSymlinks = true) shouldContainExactlyInAnyOrder listOf(
            "file.txt",
        )
    }

    @Test
    fun `followSymlinks=true follows a symlink that escapes the start tree`(@TempDir tempDir: File) = runTest {
        val outside = File(tempDir, "outside").apply { mkdirs() }
        File(outside, "ext.txt").writeText("x")
        val inner = File(tempDir, "inner").apply { mkdirs() }
        Files.createSymbolicLink(File(inner, "out").toPath(), outside.toPath())

        walkRelative(inner, followSymlinks = true) shouldContainExactlyInAnyOrder listOf(
            "out", "out/ext.txt",
        )
    }
}
