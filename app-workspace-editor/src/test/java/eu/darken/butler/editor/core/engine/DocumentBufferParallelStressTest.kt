package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.local.LocalFileSystemOps
import eu.darken.butler.common.files.metadata.OwnershipResolver
import eu.darken.butler.editor.core.sources.FileDataSource
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * TRUE parallelism over the buffer mutex: unlike the runTest-based concurrency suite (whose
 * single-threaded scheduler serializes everything), these run real threads via
 * Dispatchers.Default. Tests are written block-bodied because JUnit5 silently skips
 * expression-bodied runBlocking tests (non-void return).
 */
class DocumentBufferParallelStressTest : DocumentBufferTestBase() {

    @Test
    fun `parallel inserts and deletes from real threads keep the buffer consistent`() {
        runBlocking(Dispatchers.Default) {
            val buffer = createBuffer((0 until 200).joinToString("\n") { "Line $it" }, blockSize = 256)
            val initialLength = buffer.totalLength.value
            val inserted = AtomicLong()
            val deleted = AtomicLong()

            val workers = (0 until 4).map { worker ->
                launch {
                    val random = Random(worker)
                    repeat(150) { i ->
                        if (random.nextBoolean()) {
                            // Offset sampled outside the lock may be stale by execution time;
                            // a bounds failure is then legitimate and simply not counted
                            val offset = random.nextLong(buffer.totalLength.value + 1)
                            val text = "W$worker-$i;"
                            buffer.insertText(TextPosition(offset, 0, 0), text).getOrNull()?.let {
                                inserted.addAndGet(text.length.toLong())
                            }
                        } else {
                            val length = buffer.totalLength.value
                            if (length > 1) {
                                val start = random.nextLong(length - 1)
                                buffer.deleteText(
                                    TextPosition(start, 0, 0),
                                    TextPosition(start + 1, 0, 0),
                                ).getOrNull()?.let { deleted.addAndGet(it.length.toLong()) }
                            }
                        }
                    }
                }
            }
            workers.joinAll()

            // Length accounting must be exact across all interleavings, and the piece table's
            // own invariant assertions (enabled) ran after every single mutation
            buffer.totalLength.value shouldBe initialLength + inserted.get() - deleted.get()
            buffer.getFullText().getOrThrow().length.toLong() shouldBe buffer.totalLength.value
        }
    }

    // ==================== save vs edit ====================

    private val mockOwnershipResolver = mockk<OwnershipResolver>(relaxed = true)
    private val fileSystemOps = LocalFileSystemOps(ownershipResolver = mockOwnershipResolver)

    private fun gatedGateway(
        saveStarted: CompletableDeferred<Unit>,
        releaseSave: CompletableDeferred<Unit>,
    ): GatewaySwitch = mockk<GatewaySwitch>().apply {
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
            if (readWrite && path.name.contains("butler-save-tmp")) {
                // Hold the save mid-commit (inside the buffer mutex) until the test releases it
                saveStarted.complete(Unit)
                releaseSave.await()
            }
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

    @Test
    fun `an edit racing an in-flight save queues behind it and lands afterwards`(@TempDir tempDir: File) {
        runBlocking(Dispatchers.Default) {
            val original = "0123456789".repeat(100)
            val file = File(tempDir, "race.txt").apply { writeText(original) }
            val saveStarted = CompletableDeferred<Unit>()
            val releaseSave = CompletableDeferred<Unit>()
            val dataSource = FileDataSource(
                workspaceId, LocalPath.build(file), gatedGateway(saveStarted, releaseSave),
            ).apply { open() }
            val buffer = DocumentBuffer(
                workspaceId = workspaceId,
                dataSource = dataSource,
                maxUndoStackSize = 100,
                maxUndoMemoryBytes = 10_485_760,
                blockSize = 64,
                assertions = true,
            )
            buffer.initialize().getOrThrow()
            buffer.insertText(TextPosition(0, 0, 0), "A").getOrThrow()

            val save = async { buffer.saveFile() }
            saveStarted.await()
            // UNDISPATCHED runs the edit on this thread until its first suspension - which must
            // be the buffer mutex held by the blocked save. The isCompleted check is therefore
            // deterministic: true would mean edits can interleave with a running save.
            val edit = async(start = CoroutineStart.UNDISPATCHED) {
                buffer.insertText(TextPosition(0, 0, 0), "B")
            }
            edit.isCompleted.shouldBeFalse()

            releaseSave.complete(Unit)
            save.await().isSuccess.shouldBeTrue()
            edit.await().isSuccess.shouldBeTrue()

            // The save wrote the pre-edit content; the queued edit landed on the rebased buffer
            file.readText() shouldBe "A$original"
            buffer.getFullText().getOrThrow() shouldBe "BA$original"
            buffer.isModified.value.shouldBeTrue()
        }
    }
}
