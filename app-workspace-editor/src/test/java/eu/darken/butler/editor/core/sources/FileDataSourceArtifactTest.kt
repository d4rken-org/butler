package eu.darken.butler.editor.core.sources

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.errors.PathNotFoundException
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.editor.core.engine.ContentSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid

/**
 * Recovery of leftover save artifacts at open time: temp artifacts are junk and get cleaned,
 * backup artifacts may hold the only good copy after a crashed in-place save and are surfaced
 * but NEVER deleted. Only exact token-shaped names of THIS file are touched.
 */
class FileDataSourceArtifactTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { canWrite(any()) } returns true
        coEvery { exists(any()) } coAnswers { fileSystemOps.exists(firstArg<APath<*>>() as LocalPath) }
        coEvery { existsStrict(any()) } coAnswers { fileSystemOps.existsStrict(firstArg<APath<*>>() as LocalPath) }
        @Suppress("UNCHECKED_CAST")
        coEvery { lookup(any(), any()) } coAnswers {
            fileSystemOps.lookup(firstArg<APath<*>>() as LocalPath, secondArg<LookupOptions>()) as APathLookup<APath<*>>
        }
        coEvery { file(any(), any()) } coAnswers {
            fileSystemOps.file(firstArg<APath<*>>() as LocalPath, secondArg<Boolean>())
        }
        coEvery { delete(any<APath<*>>()) } coAnswers { (firstArg<APath<*>>() as LocalPath).file.delete() }
        @Suppress("UNCHECKED_CAST")
        coEvery { listFiles(any()) } coAnswers {
            fileSystemOps.listFiles(firstArg<APath<*>>() as LocalPath) as List<APath<*>>
        }
    }

    private suspend fun openSource(tempDir: File, gateway: GatewaySwitch = createMockGateway()): FileDataSource {
        val filePath = LocalPath.build(File(tempDir, "doc.txt").apply { writeText("content") })
        return FileDataSource(workspaceId, filePath, gateway).apply { open() }
    }

    private fun File.names(): Set<String> = listFiles()!!.map { it.name }.toSet()

    @Test
    fun `temp artifacts of this file are cleaned on open`(@TempDir tempDir: File) = runTest {
        File(tempDir, "doc.txt.butler-save-tmp-1a2b3c4d").writeText("junk")

        val source = openSource(tempDir)

        tempDir.names() shouldBe setOf("doc.txt")
        (source.contentSource.value as ContentSource.File).staleBackups shouldBe emptyList()
    }

    @Test
    fun `backup artifacts of this file are surfaced but never deleted`(@TempDir tempDir: File) = runTest {
        val bak = File(tempDir, "doc.txt.butler-save-bak-1a2b3c4d").apply { writeText("the only good copy") }

        val source = openSource(tempDir)

        bak.readText() shouldBe "the only good copy"
        (source.contentSource.value as ContentSource.File).staleBackups.map { it.name } shouldBe
            listOf("doc.txt.butler-save-bak-1a2b3c4d")
    }

    @Test
    fun `near-miss and foreign artifact names are untouched`(@TempDir tempDir: File) = runTest {
        // Wrong token shape (not 8 hex chars) - could be a user's own file
        File(tempDir, "doc.txt.butler-save-bak-old").writeText("user file")
        File(tempDir, "doc.txt.butler-save-tmp-backup").writeText("user file")
        // Artifacts of a DIFFERENT document
        File(tempDir, "other.txt.butler-save-tmp-1a2b3c4d").writeText("other doc artifact")
        File(tempDir, "other.txt.butler-save-bak-1a2b3c4d").writeText("other doc artifact")

        val source = openSource(tempDir)

        tempDir.names() shouldBe setOf(
            "doc.txt",
            "doc.txt.butler-save-bak-old",
            "doc.txt.butler-save-tmp-backup",
            "other.txt.butler-save-tmp-1a2b3c4d",
            "other.txt.butler-save-bak-1a2b3c4d",
        )
        (source.contentSource.value as ContentSource.File).staleBackups shouldBe emptyList()
    }

    @Test
    fun `listing failure degrades silently and open succeeds`(@TempDir tempDir: File) = runTest {
        val gateway = createMockGateway().apply {
            coEvery { listFiles(any()) } throws IOException("listing denied")
        }

        val source = openSource(tempDir, gateway)

        (source.contentSource.value as ContentSource.File).staleBackups shouldBe emptyList()
    }

    @Test
    fun `a gone file whose backup survives reports the artifacts as a field`(@TempDir tempDir: File) = runTest {
        // The document itself is never created - a crash between backup-move and restore
        File(tempDir, "doc.txt.butler-save-bak-1a2b3c4d").writeText("the only good copy")
        val filePath = LocalPath.build(File(tempDir, "doc.txt"))
        val source = FileDataSource(workspaceId, filePath, createMockGateway())

        val error = runCatching { source.open() }.exceptionOrNull()

        error.shouldBeInstanceOf<SaveArtifactsRemainException>()
        // Structural, so a recovery action can consume it without re-scanning or parsing a message
        error.artifacts.map { it.name } shouldBe listOf("doc.txt.butler-save-bak-1a2b3c4d")
        error.path!!.name shouldBe "doc.txt"
    }

    @Test
    fun `a gone file with no surviving backup is a plain not-found`(@TempDir tempDir: File) = runTest {
        val filePath = LocalPath.build(File(tempDir, "doc.txt"))
        val source = FileDataSource(workspaceId, filePath, createMockGateway())

        val error = runCatching { source.open() }.exceptionOrNull()

        error.shouldBeInstanceOf<PathNotFoundException>()
        error.path!!.name shouldBe "doc.txt"
    }
}
