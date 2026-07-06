package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.io.IOException

class AtomicFileWriterTest : BaseTest() {

    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { exists(any()) } coAnswers { fileSystemOps.exists(firstArg<APath<*>>() as LocalPath) }
        @Suppress("UNCHECKED_CAST")
        coEvery { lookup(any(), any()) } coAnswers {
            fileSystemOps.lookup(firstArg<APath<*>>() as LocalPath, secondArg<LookupOptions>()) as APathLookup<APath<*>>
        }
        coEvery { file(any(), any()) } coAnswers {
            val path = firstArg<APath<*>>() as LocalPath
            val readWrite = secondArg<Boolean>()
            if (readWrite && !fileSystemOps.exists(path)) path.file.createNewFile()
            fileSystemOps.file(path, readWrite)
        }
        coEvery { createFile(any(), any()) } coAnswers {
            (firstArg<APath<*>>() as LocalPath).file.createNewFile()
        }
        coEvery { delete(any<APath<*>>()) } coAnswers { fileSystemOps.delete(firstArg<APath<*>>() as LocalPath) }
        coEvery { move(any<APath<*>>(), any<APath<*>>()) } coAnswers {
            (firstArg<APath<*>>() as LocalPath).file.renameTo((secondArg<APath<*>>() as LocalPath).file)
        }
    }

    private fun File.artifacts(): List<String> = listFiles()!!.map { it.name }.filter { it.contains(".butler-save-") }

    // ==================== replace (public API, mode selection) ====================

    @Test
    fun `replace writes a fresh local target atomically`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "fresh.txt")
        val writer = AtomicFileWriter(createMockGateway(), "test")

        writer.replace(LocalPath.build(target), AtomicFileWriter.OriginalAccess.None) { context ->
            context.sink.writeUtf8("new content")
        }

        target.readText() shouldBe "new content"
        tempDir.artifacts() shouldBe emptyList()
    }

    @Test
    fun `replace over an existing local target swaps and cleans up`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "existing.txt").apply { writeText("OLD") }
        val writer = AtomicFileWriter(createMockGateway(), "test")

        writer.replace(LocalPath.build(target), AtomicFileWriter.OriginalAccess.None) { context ->
            context.sink.writeUtf8("NEW")
        }

        target.readText() shouldBe "NEW"
        tempDir.artifacts() shouldBe emptyList()
    }

    @Test
    fun `writer failure leaves a fresh target absent`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "fresh.txt")
        val writer = AtomicFileWriter(createMockGateway(), "test")

        shouldThrow<IOException> {
            writer.replace(LocalPath.build(target), AtomicFileWriter.OriginalAccess.None) { context ->
                context.sink.writeUtf8("partial")
                throw IOException("boom")
            }
        }

        target.exists() shouldBe false
        tempDir.artifacts() shouldBe emptyList()
    }

    @Test
    fun `writer failure leaves an existing target untouched`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "existing.txt").apply { writeText("OLD") }
        val writer = AtomicFileWriter(createMockGateway(), "test")

        shouldThrow<IOException> {
            writer.replace(LocalPath.build(target), AtomicFileWriter.OriginalAccess.None) { context ->
                context.sink.writeUtf8("partial")
                throw IOException("boom")
            }
        }

        target.readText() shouldBe "OLD"
        tempDir.artifacts() shouldBe emptyList()
    }

    @Test
    fun `OriginalAccess None refuses original reads`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "fresh.txt")
        val writer = AtomicFileWriter(createMockGateway(), "test")

        shouldThrow<IllegalStateException> {
            writer.replace(LocalPath.build(target), AtomicFileWriter.OriginalAccess.None) { context ->
                context.openOriginalSource(0L)
            }
        }
    }

    // ==================== temp-swap failure/restore paths ====================

    @Test
    fun `failed commit move restores the original from backup`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "doc.txt").apply { writeText("ORIGINAL") }
        val gateway = createMockGateway()
        // The tmp -> target move fails; the backup (already moved aside) must be restored
        coEvery {
            gateway.move(
                match<APath<*>> { it.name.contains(".butler-save-tmp-") },
                match<APath<*>> { it.name == "doc.txt" },
            )
        } returns false
        val writer = AtomicFileWriter(gateway, "test")

        shouldThrow<IllegalStateException> {
            writer.replace(LocalPath.build(target), AtomicFileWriter.OriginalAccess.None) { context ->
                context.sink.writeUtf8("NEW")
            }
        }

        target.readText() shouldBe "ORIGINAL"
        tempDir.artifacts() shouldBe emptyList()
    }

    @Test
    fun `failed restore preserves the backup and reports integrity loss`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "doc.txt").apply { writeText("ORIGINAL") }
        val gateway = createMockGateway()
        // Commit move fails AND the restore move fails: original only survives as the bak artifact
        coEvery {
            gateway.move(match<APath<*>> { it.name.contains(".butler-save-tmp-") }, match<APath<*>> { it.name == "doc.txt" })
        } returns false
        coEvery {
            gateway.move(match<APath<*>> { it.name.contains(".butler-save-bak-") }, match<APath<*>> { it.name == "doc.txt" })
        } returns false
        val writer = AtomicFileWriter(gateway, "test")

        shouldThrow<CommitIntegrityException> {
            writer.replace(LocalPath.build(target), AtomicFileWriter.OriginalAccess.None) { context ->
                context.sink.writeUtf8("NEW")
            }
        }

        target.exists() shouldBe false
        tempDir.artifacts().single().startsWith("doc.txt.butler-save-bak-") shouldBe true
        File(tempDir, tempDir.artifacts().single()).readText() shouldBe "ORIGINAL"
    }

    // ==================== in-place strategy (SAF-analog, exercised directly) ====================

    @Test
    fun `in-place replace of an existing target restores from backup on failure`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "doc.txt").apply { writeText("ORIGINAL") }
        val backup = File(tempDir, "doc.txt.butler-save-bak-test1234")
        val writer = AtomicFileWriter(createMockGateway(), "test")

        shouldThrow<IOException> {
            writer.replaceInPlace(
                target = LocalPath.build(target),
                backupPath = LocalPath.build(backup),
                originalAccess = AtomicFileWriter.OriginalAccess.None,
            ) { context ->
                context.sink.writeUtf8("PARTIAL")
                throw IOException("boom")
            }
        }

        target.readText() shouldBe "ORIGINAL"
        // Parity with the pre-extraction behavior: the backup is conservatively RETAINED after
        // an in-place failure even when the restore succeeded (in-place writes can't prove the
        // restore wasn't itself interrupted)
        backup.readText() shouldBe "ORIGINAL"
    }

    @Test
    fun `in-place replace of a fresh target deletes the partial file on failure`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "fresh.txt")
        val backup = File(tempDir, "fresh.txt.butler-save-bak-test1234")
        val writer = AtomicFileWriter(createMockGateway(), "test")

        shouldThrow<IOException> {
            writer.replaceInPlace(
                target = LocalPath.build(target),
                backupPath = LocalPath.build(backup),
                originalAccess = AtomicFileWriter.OriginalAccess.None,
            ) { context ->
                context.sink.writeUtf8("PARTIAL")
                throw IOException("boom")
            }
        }

        target.exists() shouldBe false
        tempDir.artifacts() shouldBe emptyList()
    }

    @Test
    fun `in-place FromTarget serves original bytes from the backup`(@TempDir tempDir: File) = runTest {
        val target = File(tempDir, "doc.txt").apply { writeText("ORIGINAL") }
        val backup = File(tempDir, "doc.txt.butler-save-bak-test1234")
        val writer = AtomicFileWriter(createMockGateway(), "test")

        writer.replaceInPlace(
            target = LocalPath.build(target),
            backupPath = LocalPath.build(backup),
            originalAccess = AtomicFileWriter.OriginalAccess.FromTarget,
        ) { context ->
            // Read the pre-commit content while the target is being overwritten
            val original = context.openOriginalSource(0L).use { source ->
                okio.Buffer().also { source.read(it, 8L) }.readUtf8()
            }
            original shouldBe "ORIGINAL"
            context.sink.writeUtf8("NEW($original)")
        }

        target.readText() shouldBe "NEW(ORIGINAL)"
        tempDir.artifacts() shouldBe emptyList()
    }
}
