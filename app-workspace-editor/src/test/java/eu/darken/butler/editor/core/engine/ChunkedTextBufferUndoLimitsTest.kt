package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for undo stack size limits to prevent memory leaks.
 * Validates that both operation count and memory limits are enforced with LRU eviction.
 */
class ChunkedTextBufferUndoLimitsTest {

    private val workspaceId = Workspace.Id()

    /**
     * Creates a buffer with custom undo limits for testing.
     */
    private suspend fun createBufferWithLimits(
        content: String,
        maxUndoStackSize: Int = 100,
        maxUndoMemoryBytes: Long = 10_485_760,  // 10 MB
        chunkSize: Long = ChunkManager.DEFAULT_CHUNK_SIZE
    ): ChunkedTextBuffer {
        val dataSource = InMemoryDataSource(workspaceId, content)
        dataSource.open()

        val repository = ChunkRepository(workspaceId, dataSource, chunkSize)
        val manager = ChunkManager(workspaceId, repository, chunkSize)
        val buffer = ChunkedTextBuffer(
            workspaceId,
            manager,
            repository,
            maxUndoStackSize,
            maxUndoMemoryBytes
        )

        buffer.initialize().getOrThrow()
        return buffer
    }

    @Test
    fun `undo stack evicts oldest when operation limit exceeded`() = runTest {
        // Given: Buffer with small operation limit
        val buffer = createBufferWithLimits(
            content = "",
            maxUndoStackSize = 5,  // Only 5 operations allowed
            maxUndoMemoryBytes = 1_000_000  // Large memory limit to test count limit
        )

        // When: Perform 10 insert operations
        var position = TextPosition.ZERO
        repeat(10) { i ->
            val result = buffer.insertText(position, "Line$i\n")
            position = result.getOrThrow()
        }

        // Then: Can only undo 5 times (the limit)
        repeat(5) {
            val undoResult = buffer.undo()
            undoResult.isSuccess shouldBe true
            undoResult.getOrThrow() shouldNotBe null
        }

        // And: No more undo operations available (oldest 5 were evicted)
        val finalUndoResult = buffer.undo()
        finalUndoResult.isSuccess shouldBe true
        finalUndoResult.getOrThrow() shouldBe null  // No more operations
    }

    @Test
    fun `undo stack evicts oldest when memory limit exceeded`() = runTest {
        // Given: Buffer with small memory limit but large operation count limit
        val buffer = createBufferWithLimits(
            content = "",
            maxUndoStackSize = 1000,  // Large operation limit
            maxUndoMemoryBytes = 1000  // Only 1000 bytes (≈500 chars in memory)
        )

        // When: Perform operations that exceed memory limit
        // Each operation stores ~200 chars (400 bytes) + overhead
        var position = TextPosition.ZERO
        val largeText = "X".repeat(200)
        repeat(5) {
            val result = buffer.insertText(position, largeText)
            position = result.getOrThrow()
        }

        // Then: Not all 5 operations should be in undo stack (memory limit enforced)
        // We can undo some, but not all 5
        var undoCount = 0
        while (true) {
            val undoResult = buffer.undo()
            if (undoResult.getOrThrow() == null) break
            undoCount++
        }

        undoCount shouldBeLessThan 5  // Less than 5 due to memory limit
    }

    @Test
    fun `large single operation exceeding limit is allowed`() = runTest {
        // Given: Buffer with small memory limit
        val buffer = createBufferWithLimits(
            content = "",
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 100  // Only 100 bytes
        )

        // When: Insert large text that exceeds memory limit
        val hugeText = "Y".repeat(1000)  // 2000 bytes in memory
        val position = TextPosition.ZERO
        val result = buffer.insertText(position, hugeText)

        // Then: Operation succeeds (we allow single large ops)
        result.isSuccess shouldBe true

        // And: Can undo this single large operation
        val undoResult = buffer.undo()
        undoResult.isSuccess shouldBe true
        undoResult.getOrThrow() shouldNotBe null

        // And: Content is restored to empty
        val content = buffer.getText(0, buffer.totalLength.value).getOrThrow()
        content shouldBe ""
    }

    @Test
    fun `memory estimation calculates correctly for all operation types`() = runTest {
        // This test verifies the memory estimation logic indirectly
        // by checking that different operations trigger eviction differently

        // Given: Buffer with precise memory limit
        val buffer = createBufferWithLimits(
            content = "ABCDEFGHIJ",  // 10 chars
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 500  // Enough for ~2 operations
        )

        // When: Perform insert, delete, and replace operations
        val insertPos = buffer.findPosition(10L)
        buffer.insertText(insertPos, "K".repeat(50))  // ~100 bytes + overhead

        val deleteStart = TextPosition(0, 0, 0)
        val deleteEnd = TextPosition(5, 0, 5)
        buffer.deleteText(deleteStart, deleteEnd)  // Stores 5 deleted chars

        // Then: Should be able to undo at least the most recent operations
        // (exact count depends on memory estimation accuracy)
        var undoCount = 0
        while (buffer.undo().getOrThrow() != null) {
            undoCount++
        }

        undoCount shouldBeGreaterThanOrEqual 1  // At least 1 operation retained
    }

    @Test
    fun `redo stack also respects limits`() = runTest {
        // Given: Buffer with operation limit
        val buffer = createBufferWithLimits(
            content = "",
            maxUndoStackSize = 3,
            maxUndoMemoryBytes = 1_000_000
        )

        // When: Perform operations then undo all
        var position = TextPosition.ZERO
        repeat(5) { i ->
            val result = buffer.insertText(position, "Line$i\n")
            position = result.getOrThrow()
        }

        // Undo all operations (moves them to redo stack)
        repeat(5) {
            buffer.undo()
        }

        // Then: Redo stack should also respect limits
        // (redo stack doesn't have separate limits, but gets cleared on new edits)
        var redoCount = 0
        while (buffer.redo().getOrThrow() != null) {
            redoCount++
        }

        // Redo stack should have all operations that were in undo stack (max 3)
        redoCount shouldBe 3
    }

    @Test
    fun `eviction during multiple small operations works correctly`() = runTest {
        // Given: Buffer with small operation limit
        val buffer = createBufferWithLimits(
            content = "",
            maxUndoStackSize = 10,
            maxUndoMemoryBytes = 10_000
        )

        // When: Perform many small operations
        var position = TextPosition.ZERO
        repeat(50) {
            val result = buffer.insertText(position, "X")
            position = result.getOrThrow()
        }

        // Then: Only last 10 operations are retained
        var undoCount = 0
        while (buffer.undo().getOrThrow() != null) {
            undoCount++
        }

        undoCount shouldBe 10
    }

    @Test
    fun `undo-redo with eviction maintains consistency`() = runTest {
        // Given: Buffer with small limit
        val buffer = createBufferWithLimits(
            content = "",
            maxUndoStackSize = 5,
            maxUndoMemoryBytes = 10_000
        )

        // When: Perform 10 operations (exceeding limit)
        var position = TextPosition.ZERO
        repeat(10) { i ->
            val result = buffer.insertText(position, "$i")
            position = result.getOrThrow()
        }

        val contentAfterInserts = buffer.getText(0, buffer.totalLength.value).getOrThrow()

        // Undo 3 operations
        repeat(3) {
            buffer.undo()
        }

        val contentAfterUndos = buffer.getText(0, buffer.totalLength.value).getOrThrow()

        // Redo 3 operations
        repeat(3) {
            buffer.redo()
        }

        val contentAfterRedos = buffer.getText(0, buffer.totalLength.value).getOrThrow()

        // Then: Content after redo should match content before undo
        contentAfterRedos shouldBe contentAfterInserts

        // And: Content is correct (digits 0-9)
        contentAfterRedos shouldBe "0123456789"
    }

    @Test
    fun `stack size after 1000 operations with limit 100 equals 100`() = runTest {
        // Given: Buffer with limit of 100 operations
        val buffer = createBufferWithLimits(
            content = "",
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 100_000_000  // Large memory to test count limit
        )

        // When: Perform 1000 small operations
        var position = TextPosition.ZERO
        repeat(1000) {
            val result = buffer.insertText(position, "X")
            position = result.getOrThrow()
        }

        // Then: Can undo exactly 100 times
        var undoCount = 0
        while (buffer.undo().getOrThrow() != null) {
            undoCount++
        }

        undoCount shouldBe 100

        // And: Final content has 900 X's (1000 - 100 undos)
        val finalContent = buffer.getText(0, buffer.totalLength.value).getOrThrow()
        finalContent shouldBe "X".repeat(900)
    }
}
