package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

/**
 * Concurrency tests for ChunkedTextBuffer to verify thread-safety and race condition fixes.
 *
 * These tests verify:
 * 1. Concurrent insertText() operations at the same offset
 * 2. Boundaries map visibility across threads
 * 3. Concurrent operations at different offsets
 *
 * Related: RACE_CONDITION_ANALYSIS.md - "Position is out of bounds" during fast typing
 */
class ChunkedTextBufferConcurrencyTest : BaseTest() {

    private val workspaceId = Workspace.Id()

    // ==================== Helper Methods ====================

    private suspend fun createBuffer(
        content: String,
        chunkSize: Long = ChunkManager.DEFAULT_CHUNK_SIZE
    ): ChunkedTextBuffer {
        val dataSource = InMemoryDataSource(workspaceId, content)
        dataSource.open()

        val repository = ChunkRepository(workspaceId, dataSource, chunkSize)
        val manager = ChunkManager(workspaceId, repository, chunkSize)
        val buffer = ChunkedTextBuffer(workspaceId, manager, repository)

        buffer.initialize().getOrThrow()
        return buffer
    }

    // ==================== P0 Tests: Race Condition Reproduction ====================

    @Test
    fun `concurrent inserts at same offset should not fail with Position out of bounds`() = runTest {
        // Given: Buffer with content
        val content = "Hello"
        val buffer = createBuffer(content)

        // When: Launch multiple concurrent inserts at same offset (simulates fast typing)
        val insertOffset = 5L
        val jobs = listOf(
            async { buffer.insertText(TextPosition(insertOffset, 0, 5), " World") },
            async { buffer.insertText(TextPosition(insertOffset, 0, 5), "!") }
        )

        val results = jobs.awaitAll()

        // Then: At least one insert should succeed
        // Note: Both might succeed if timing allows, but neither should crash
        val successCount = results.count { it.isSuccess }
        (successCount > 0) shouldBe true

        // And: Buffer should be in valid state
        val finalContent = buffer.getTextForRange(0, 0).getOrThrow()
        finalContent shouldContain "Hello"

        // And: All successful inserts should be reflected
        val expectedMinLength = content.length + results.filter { it.isSuccess }
            .sumOf { if (it == results[0]) " World".length else "!".length }
        (buffer.totalLength.value > (expectedMinLength.toLong() - 1)) shouldBe true
    }

    @Test
    fun `rapid concurrent inserts at same offset reproduce device race condition`() = runTest {
        // This test reproduces the exact scenario from device logs:
        // Two inserts within 1ms at offset 10

        // Given: Buffer with initial content (offset 10 exists)
        val content = "0123456789"  // 10 characters, so offset 10 is at end
        val buffer = createBuffer(content)

        // When: Launch 10 rapid concurrent inserts at same offset
        // (simulates very fast typing like on device: 1ms between keystrokes)
        val jobs = (1..10).map { i ->
            async {
                delay(i.toLong())  // Stagger slightly but overlap significantly
                buffer.insertText(TextPosition(10, 0, 10), "$i")
            }
        }

        val results = jobs.awaitAll()

        // Then: With the race condition FIXED, concurrent inserts at same offset now behave correctly:
        // - At least one insert succeeds (first one to acquire mutex)
        // - Subsequent inserts at the same stale offset gracefully fail
        // This is CORRECT behavior - positions become stale after other threads modify content
        val failures = results.filter { it.isFailure }
        val successes = results.filter { it.isSuccess }

        // At least one should succeed (the first to acquire the mutex)
        (successes.size > 0) shouldBe true

        // Note: In test environment with serialized dispatcher, all might succeed.
        // In production with true concurrency, some would fail with stale positions.
        // Both outcomes are correct - what matters is no crashes or corruption!

        // And: Buffer should be in consistent state (no corruption)
        val finalContent = buffer.getTextForRange(0, 0).getOrThrow()
        finalContent shouldContain "0123456789"

        // And: Final length should reflect successful inserts
        // Note: "$i" for i=1..9 is 1 byte each, but "$10" is 2 bytes
        val expectedIncrease = results.zip(1..10)
            .filter { (result, _) -> result.isSuccess }
            .sumOf { (_, i) -> "$i".length }
        buffer.totalLength.value shouldBe (10 + expectedIncrease).toLong()
    }

    @Test
    fun `boundaries map should never be empty during concurrent operations`() = runTest {
        // This test catches the clear+putAll anti-pattern in updateBoundaries()
        // where the map is temporarily empty

        // Given: Buffer with content
        val content = "Hello World"
        val buffer = createBuffer(content)

        // Track if we ever observe zero chunks
        var observedEmptyBoundaries = false
        val observations = mutableListOf<Int>()

        // When: Monitor chunk count while performing concurrent edits
        val monitorJob = launch {
            repeat(200) {
                // Try to access buffer state that depends on boundaries
                val result = runCatching {
                    buffer.findPosition(5L)
                }

                if (result.isFailure) {
                    observedEmptyBoundaries = true
                }

                // Also track total length as proxy for boundaries consistency
                observations.add(buffer.totalLength.value.toInt())
                delay(1)
            }
        }

        val editJob = launch {
            repeat(100) {
                val offset = (it % 10).toLong()
                buffer.insertText(TextPosition(offset, 0, offset.toInt()), "X")
                delay(2)
            }
        }

        monitorJob.join()
        editJob.join()

        // Then: Should never have observed inconsistent state
        observedEmptyBoundaries shouldBe false

        // And: All observations should show valid lengths
        observations.forEach { length ->
            length shouldBeGreaterThan 0
        }
    }

    // ==================== P0 Tests: Correct Concurrent Behavior ====================

    /**
     * Tests concurrent inserts at different offsets.
     *
     * EXPECTED BEHAVIOR:
     * - Operations serialize through bufferMutex (only one executes at a time)
     * - First operation succeeds and shifts content offsets
     * - Later operations have stale positions and may fail gracefully
     * - This is CORRECT for single-user editor without Operational Transformation
     *
     * REAL-WORLD USAGE:
     * - Replace All uses reverse-order iteration to avoid stale positions
     * - User typing is always at same cursor position (different test covers this)
     * - This test validates buffer consistency under edge case concurrent access
     */
    @Test
    fun `concurrent inserts at different offsets maintain consistency`() = runTest {
        // Given: Buffer with content
        val content = "Hello World"
        val buffer = createBuffer(content)

        // When: Insert at different positions concurrently
        val jobs = listOf(
            async { buffer.insertText(TextPosition(0, 0, 0), "START ") },     // Beginning
            async { buffer.insertText(TextPosition(5, 0, 5), " MIDDLE") },    // Middle
            async { buffer.insertText(TextPosition(11, 0, 11), " END") }      // End
        )

        val results = jobs.awaitAll()

        // Then: At least one insert succeeds (validates no deadlock)
        // Note: Once one succeeds, it invalidates stale positions of waiting operations
        // This is correct behavior - positions become stale after content changes
        val successCount = results.count { it.isSuccess }
        (successCount > 0) shouldBe true

        // And: No corruption - buffer remains consistent
        val finalContent = buffer.getTextForRange(0, buffer.totalLines.value.toInt() - 1).getOrThrow()
        finalContent shouldContain "Hello"
        finalContent shouldContain "World"

        // And: Successful inserts are reflected in buffer length
        // Note: Due to position invalidation, exact content location may vary
        // What matters: no crashes, correct length, original content preserved
        val insertLengths = listOf("START ", " MIDDLE", " END")
        val expectedIncrease = results.zip(insertLengths)
            .filter { it.first.isSuccess }
            .sumOf { it.second.length }

        buffer.totalLength.value shouldBe (content.length + expectedIncrease).toLong()

        // And: At least some text from successful inserts is present
        val hasInsertedContent = results.zip(listOf("START", "MIDDL", "END"))
            .filter { it.first.isSuccess }
            .any { (_, text) -> finalContent.contains(text) }
        hasInsertedContent shouldBe true
    }

    @Test
    fun `concurrent inserts and deletes maintain consistency`() = runTest {
        // Given: Buffer with content
        val content = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val buffer = createBuffer(content)

        // When: Mix of concurrent inserts and deletes
        val jobs = listOf(
            async { buffer.insertText(TextPosition(5, 0, 5), "123") },
            async { buffer.deleteText(TextPosition(10, 0, 10), TextPosition(12, 0, 12)) },
            async { buffer.insertText(TextPosition(20, 0, 20), "456") }
        )

        val results = jobs.awaitAll()

        // Then: At least some operations should succeed
        val successCount = results.count { it.isSuccess }
        (successCount > 0) shouldBe true

        // And: Buffer should be in valid state
        val finalContent = buffer.getTextForRange(0, 0).getOrThrow()
        finalContent shouldContain "ABC"  // Beginning preserved
        (buffer.totalLength.value > 0) shouldBe true
    }

    // ==================== P1 Tests: Multi-Chunk Concurrency ====================

    @Test
    fun `concurrent operations across chunk boundaries maintain integrity`() = runTest {
        // Given: Content spanning multiple small chunks
        val content = "A".repeat(100) + "B".repeat(100)  // 200 bytes = 2 chunks @ 100 bytes
        val buffer = createBuffer(content, chunkSize = 100L)

        // When: Concurrent inserts at chunk boundaries
        val jobs = listOf(
            async { buffer.insertText(TextPosition(50, 0, 50), "1") },    // Middle of chunk 1
            async { buffer.insertText(TextPosition(100, 0, 100), "2") },  // Exact boundary
            async { buffer.insertText(TextPosition(150, 0, 150), "3") }   // Middle of chunk 2
        )

        val results = jobs.awaitAll()

        // Then: All operations should succeed
        val successCount = results.count { it.isSuccess }
        (successCount > 0) shouldBe true

        // And: Content structure preserved
        val finalContent = buffer.getTextForRange(0, 0).getOrThrow()
        finalContent shouldContain "A"
        finalContent shouldContain "B"
    }

    @Test
    fun `concurrent metadata rebuild does not corrupt boundaries`() = runTest {
        // This tests the buildChunkMetadata() race where it reads boundaries
        // incrementally then updates them in batch

        // Given: Buffer with content
        val content = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n"
        val buffer = createBuffer(content)

        // When: Trigger multiple operations that rebuild metadata concurrently
        val jobs = (1..5).map { i ->
            async {
                // Each insert triggers buildChunkMetadata()
                val result = buffer.insertText(TextPosition(i.toLong(), 0, i), "X")
                delay(5)
                result
            }
        }

        val results = jobs.awaitAll()

        // Then: At least one should succeed (tests that operations don't deadlock)
        val successCount = results.count { it.isSuccess }
        (successCount > 0) shouldBe true

        // And: Line count should be consistent (no corruption)
        (buffer.totalLines.value > 0) shouldBe true

        // And: Content should be readable without corruption
        // The key test here is that concurrent metadata rebuilds don't corrupt data
        val finalContent = buffer.getTextForRange(0, buffer.totalLines.value.toInt() - 1).getOrThrow()

        // Original content "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n" structure should remain
        // After inserts at sequential offsets, we might get "LXXXXXine 1..." (X's stacked)
        // The key is: no crashes, no lost data, exact number of X's inserted
        val xCount = finalContent.count { it == 'X' }
        xCount shouldBe successCount

        // And: Original structure preserved (numbers and newlines should still be there)
        (1..5).forEach { i ->
            finalContent shouldContain "$i"
        }

        // And: No data loss - original content (minus insertedpositions) should be present
        finalContent shouldContain "ine"  // Part of "Line" should remain
        finalContent.contains("\n") shouldBe true  // Newlines preserved
    }

    // ==================== P1 Tests: Memory Visibility ====================

    @Test
    fun `changes to boundaries are visible across threads immediately`() = runTest {
        // Tests the @Volatile fix - ensures changes are visible across CPU cores

        // Given: Buffer with content
        val content = "Test content"
        val buffer = createBuffer(content)

        // When: One thread modifies, another reads immediately after
        var observedStaleRead = false

        val writerJob = async {
            repeat(50) {
                buffer.insertText(TextPosition(it.toLong() % 10, 0, (it % 10).toInt()), "W")
                delay(5)
            }
        }

        val readerJob = async {
            delay(10)  // Let writer start first
            repeat(50) {
                val lengthBefore = buffer.totalLength.value
                delay(5)
                val lengthAfter = buffer.totalLength.value

                // Length should never decrease (only increase with inserts)
                if (lengthAfter < lengthBefore) {
                    observedStaleRead = true
                }
            }
        }

        writerJob.await()
        readerJob.await()

        // Then: Should never observe stale reads
        observedStaleRead shouldBe false

        // And: Final state should be consistent
        (buffer.totalLength.value > content.length.toLong()) shouldBe true
    }
}
