package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.common.files.text.TextCharsets
import eu.darken.butler.common.files.write.FileCommitContext
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.FileDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import testhelpers.BaseTest
import java.io.File

class DocumentBufferConvertCancellationTest : BaseTest() {

    private val workspaceId = Workspace.Id()
    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)
    private val gb18030 = TextCharsets.GB18030

    private fun createMockGateway(): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { canWrite(any()) } returns true
        coEvery { exists(any()) } coAnswers { fileSystemOps.exists(firstArg<APath<*>>() as LocalPath) }
        coEvery { existsStrict(any()) } coAnswers { fileSystemOps.existsStrict(firstArg<APath<*>>() as LocalPath) }
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
            val renamed = (firstArg<APath<*>>() as LocalPath).file.renameTo((secondArg<APath<*>>() as LocalPath).file)
            if (renamed) MoveOutcome.Moved else MoveOutcome.NotSupported("rename failed")
        }
    }

    /**
     * Lets the commit land, then cancels [cancelAfterCommit] and checks for cancellation on the way
     * out, like `FileDataSource.commit` resuming from its IO context into a cancelled caller.
     */
    private class CancelAfterCommitDataSource(
        private val delegate: EditorDataSource,
    ) : EditorDataSource by delegate {
        var cancelAfterCommit: Job? = null

        override suspend fun commit(writer: suspend (FileCommitContext) -> Unit) {
            delegate.commit(writer)
            cancelAfterCommit!!.cancel()
            currentCoroutineContext().ensureActive()
        }
    }

    private val sample = "第一行你好ʘ😀世界\n第二行😀ʘ结束\n"

    @Test
    fun `retires the buffer when cancellation arrives after the commit landed`(@TempDir tempDir: File) = runTest {
        val file = File(tempDir, "doc.txt").apply { writeBytes(sample.toByteArray(gb18030)) }
        val fileSource = FileDataSource(workspaceId, LocalPath.build(file), createMockGateway(), gb18030)
            .apply { open() }
        val dataSource = CancelAfterCommitDataSource(fileSource)
        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = 7,
            assertions = true,
        ).apply { initialize().getOrThrow() }

        val conversion = launch { buffer.convertEncoding(Charsets.UTF_8) }
        dataSource.cancelAfterCommit = conversion
        conversion.join()

        file.readBytes() shouldBe sample.toByteArray(Charsets.UTF_8)
        withClue("The file is already UTF-8, so the GB18030 buffer must be retired") {
            buffer.isRetired.value.shouldBeTrue()
        }
    }
}
