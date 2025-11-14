package eu.darken.butler.editor.core.engine

import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.uuid.Uuid

class ChunkManagerTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())

    private fun createMockRepository(): ChunkRepository {
        val mockRepo = mockk<ChunkRepository>(relaxed = true)
        // saveFile() should succeed by default
        coEvery { mockRepo.saveFile(any(), any()) } returns Unit
        return mockRepo
    }

    private fun createChunkManager(repository: ChunkRepository = createMockRepository()): ChunkManager {
        return ChunkManager(workspaceId, repository)
    }

    private fun boundaries(vararg entries: Pair<TextChunk, Pair<Long, Long>>): Map<TextChunk.ChunkId, ChunkBoundary> {
        return entries.associate { (chunk, offsets) ->
            // Calculate line count from chunk content
            val lineCount = chunk.content.count { it == '\n' } + if (chunk.content.isNotEmpty() && !chunk.content.endsWith('\n')) 1 else 0
            chunk.id to ChunkBoundary(offsets.first, offsets.second, lineCount)
        }
    }

    private suspend fun ChunkManager.addChunkWithBoundary(chunk: TextChunk, startOffset: Long, endOffset: Long) {
        addChunk(chunk)
        // Access boundaries via reflection for test purposes
        val boundariesField = ChunkManager::class.java.getDeclaredField("boundaries")
        boundariesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val boundariesMap = boundariesField.get(this) as MutableMap<TextChunk.ChunkId, ChunkBoundary>
        // Calculate line count from chunk content
        val lineCount = chunk.content.count { it == '\n' } + if (chunk.content.isNotEmpty() && !chunk.content.endsWith('\n')) 1 else 0
        boundariesMap[chunk.id] = ChunkBoundary(startOffset, endOffset, lineCount)
    }

    private suspend fun ChunkManager.addBoundaryOnly(chunkId: TextChunk.ChunkId, startOffset: Long, endOffset: Long, lineCount: Int = 1) {
        // Access boundaries via reflection to set boundary without adding chunk to cache
        val boundariesField = ChunkManager::class.java.getDeclaredField("boundaries")
        boundariesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val boundariesMap = boundariesField.get(this) as MutableMap<TextChunk.ChunkId, ChunkBoundary>
        boundariesMap[chunkId] = ChunkBoundary(startOffset, endOffset, lineCount)
    }

    // ==================== mergeChunks() Algorithm Tests ====================

    @Test
    fun `mergeChunks with empty dirty list returns original content`() {
        // Given: Original content
        val original = "Hello World".toByteArray()
        val dirtyChunks = emptyList<TextChunk>()

        // When: Merge with no dirty chunks
        val result = ChunkManager.mergeChunks(original, dirtyChunks, emptyMap())

        // Then: Returns unchanged content
        String(result) shouldBe "Hello World"
    }

    @Test
    fun `mergeChunks with single chunk modification at start`() {
        // Given: Original content "Hello World"
        val original = "Hello World".toByteArray()

        // Modified first 5 bytes to "HELLO"
        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "HELLO",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(
            original,
            listOf(dirtyChunk),
            boundaries(dirtyChunk to (0L to 5L))
        )

        // Then: First 5 chars changed, rest unchanged
        String(result) shouldBe "HELLO World"
    }

    @Test
    fun `mergeChunks with single chunk modification in middle`() {
        // Given: Original content "Hello World"
        val original = "Hello World".toByteArray()

        // Modified middle part (offset 6-11) "World" -> "There"
        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "There",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(
            original,
            listOf(dirtyChunk),
            boundaries(dirtyChunk to (6L to 11L))
        )

        // Then: Middle part changed
        String(result) shouldBe "Hello There"
    }

    @Test
    fun `mergeChunks with single chunk modification at end`() {
        // Given: Original content "Hello World"
        val original = "Hello World".toByteArray()

        // Modified last 5 bytes (offset 6-11) "World" -> "WORLD"
        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "WORLD",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(
            original,
            listOf(dirtyChunk),
            boundaries(dirtyChunk to (6L to 11L))
        )

        // Then: Last part changed
        String(result) shouldBe "Hello WORLD"
    }

    @Test
    fun `mergeChunks with chunk expansion - content grows`() {
        // Given: Original content "Hello World" (11 bytes)
        val original = "Hello World".toByteArray()

        // Expand middle part: "World" -> "Beautiful World" (longer)
        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Beautiful World",  // Longer content
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(
            original,
            listOf(dirtyChunk),
            boundaries(dirtyChunk to (6L to 11L))
        )

        // Then: Content expanded
        String(result) shouldBe "Hello Beautiful World"
    }

    @Test
    fun `mergeChunks with chunk shrinking - content shrinks`() {
        // Given: Original content "Hello Beautiful World"
        val original = "Hello Beautiful World".toByteArray()

        // Shrink middle part: "Beautiful World" (offset 6-21) -> "There"
        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "There",  // Shorter content
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(
            original,
            listOf(dirtyChunk),
            boundaries(dirtyChunk to (6L to 21L))
        )

        // Then: Content shrunk
        String(result) shouldBe "Hello There"
    }

    @Test
    fun `mergeChunks with multiple non-overlapping chunks`() {
        // Given: Original "AAAA BBBB CCCC"
        val original = "AAAA BBBB CCCC".toByteArray()

        // Modify first and last parts
        val chunk1 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "1111",
            lineCount = 1,
            isDirty = true
        )
        val chunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "3333",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge (provide in wrong order to test sorting)
        val result = ChunkManager.mergeChunks(
            original,
            listOf(chunk2, chunk1),
            boundaries(
                chunk1 to (0L to 4L),
                chunk2 to (10L to 14L)
            )
        )

        // Then: Both parts changed, middle unchanged
        String(result) shouldBe "1111 BBBB 3333"
    }

    @Test
    fun `mergeChunks with adjacent dirty chunks`() {
        // Given: Original "AAAA BBBB"
        val original = "AAAA BBBB".toByteArray()

        // Two adjacent chunks
        val chunk1 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "1111",
            lineCount = 1,
            isDirty = true
        )
        val chunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "2222",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(
            original,
            listOf(chunk1, chunk2),
            boundaries(
                chunk1 to (0L to 4L),
                chunk2 to (5L to 9L)
            )
        )

        // Then: Both parts changed
        String(result) shouldBe "1111 2222"
    }

    @Test
    fun `mergeChunks with all content modified`() {
        // Given: Original "Hello"
        val original = "Hello".toByteArray()

        // Replace entire content
        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Goodbye",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(
            original,
            listOf(dirtyChunk),
            boundaries(dirtyChunk to (0L to 5L))
        )

        // Then: Completely replaced
        String(result) shouldBe "Goodbye"
    }

    @Test
    fun `mergeChunks with gaps between dirty chunks preserves unchanged content`() {
        // Given: Original "AAAA BBBB CCCC DDDD"
        val original = "AAAA BBBB CCCC DDDD".toByteArray()

        // Modify first and last, leaving middle untouched
        val chunk1 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "1111",
            lineCount = 1,
            isDirty = true
        )
        val chunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "4444",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(
            original,
            listOf(chunk1, chunk2),
            boundaries(
                chunk1 to (0L to 4L),
                chunk2 to (15L to 19L)
            )
        )

        // Then: Gaps preserved
        String(result) shouldBe "1111 BBBB CCCC 4444"
    }

    @Test
    fun `mergeChunks with multiline content`() {
        // Given: Original file with multiple lines
        val original = "Line 1\nLine 2\nLine 3".toByteArray()

        // Replace second line
        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Modified Line",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(
            original,
            listOf(dirtyChunk),
            boundaries(dirtyChunk to (7L to 13L))
        )

        // Then: Middle line changed
        String(result) shouldBe "Line 1\nModified Line\nLine 3"
    }

    @Test
    fun `mergeChunks with empty chunk content`() {
        // Given: Original "Hello World"
        val original = "Hello World".toByteArray()

        // Delete content (empty replacement)
        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "",  // Empty
            lineCount = 0,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(
            original,
            listOf(dirtyChunk),
            boundaries(dirtyChunk to (5L to 11L))
        )

        // Then: Content deleted
        String(result) shouldBe "Hello"
    }

    // ==================== Dirty Chunk Tracking Tests ====================

    @Test
    fun `addChunk adds chunk to manager`() = runTest {
        // Given: Empty manager
        val manager = createChunkManager()

        // When: Add a chunk
        val chunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Test content",
            lineCount = 1,
            isDirty = false
        )
        manager.addChunk(chunk)

        // Then: Chunk is accessible
        val retrieved = manager.getChunk(chunk.id)
        retrieved shouldBe chunk
    }

    @Test
    fun `updateChunk marks chunk as dirty`() = runTest {
        // Given: Manager with clean chunk
        val manager = createChunkManager()
        val originalChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Original",
            lineCount = 1,
            isDirty = false
        )
        manager.addChunk(originalChunk)

        // When: Update chunk with modified content
        val updatedChunk = manager.updateChunk(originalChunk.id) { chunk ->
            chunk.copy(content = "Modified", isDirty = true)
        }

        // Then: Chunk is marked as dirty
        updatedChunk shouldNotBe null
        updatedChunk?.isDirty shouldBe true
        updatedChunk?.content shouldBe "Modified"
    }

    @Test
    fun `getDirtyChunks returns only dirty chunks`() = runTest {
        // Given: Manager with mix of clean and dirty chunks
        val manager = createChunkManager()

        val cleanChunk1 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Clean 1",
            lineCount = 1,
            isDirty = false
        )
        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Dirty",
            lineCount = 1,
            isDirty = true
        )
        val cleanChunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Clean 2",
            lineCount = 1,
            isDirty = false
        )

        manager.addChunk(cleanChunk1)
        manager.addChunk(dirtyChunk)
        manager.addChunk(cleanChunk2)

        // When: Get dirty chunks
        val dirtyChunks = manager.getDirtyChunks()

        // Then: Only dirty chunk returned
        dirtyChunks shouldHaveSize 1
        dirtyChunks shouldContain dirtyChunk
    }

    @Test
    fun `getDirtyChunks returns chunks sorted by offset`() = runTest {
        // Given: Manager with dirty chunks in wrong order
        val manager = createChunkManager()

        val chunk1 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Chunk 3",
            lineCount = 1,
            isDirty = true
        )
        val chunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Chunk 1",
            lineCount = 1,
            isDirty = true
        )
        val chunk3 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Chunk 2",
            lineCount = 1,
            isDirty = true
        )

        // Add in wrong order with boundaries
        manager.addChunkWithBoundary(chunk1, 200L, 207L)  // offset 200
        manager.addChunkWithBoundary(chunk2, 0L, 7L)      // offset 0
        manager.addChunkWithBoundary(chunk3, 100L, 107L)  // offset 100

        // When: Get dirty chunks
        val dirtyChunks = manager.getDirtyChunks()

        // Then: Sorted by startOffset
        dirtyChunks shouldHaveSize 3
        dirtyChunks[0] shouldBe chunk2  // offset 0
        dirtyChunks[1] shouldBe chunk3  // offset 100
        dirtyChunks[2] shouldBe chunk1  // offset 200
    }

    @Test
    fun `markChunksClean clears dirty flags`() = runTest {
        // Given: Manager with dirty chunks
        val manager = createChunkManager()

        val chunk1 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Chunk 1",
            lineCount = 1,
            isDirty = true
        )
        val chunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Chunk 2",
            lineCount = 1,
            isDirty = true
        )

        manager.addChunk(chunk1)
        manager.addChunk(chunk2)

        // When: Mark as clean
        manager.markChunksClean(listOf(chunk1.id, chunk2.id))

        // Then: Both chunks are clean
        val dirtyChunks = manager.getDirtyChunks()
        dirtyChunks.shouldBeEmpty()
    }

    @Test
    fun `saveAllDirtyChunks with no dirty chunks returns success`() = runTest {
        // Given: Manager with only clean chunks
        val manager = createChunkManager()

        val cleanChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Clean",
            lineCount = 1,
            isDirty = false
        )
        manager.addChunk(cleanChunk)

        // When: Save all dirty chunks
        val result = manager.saveAllDirtyChunks()

        // Then: Success with no operation
        result.isSuccess shouldBe true
    }

    @Test
    fun `saveAllDirtyChunks saves dirty chunks and marks them clean`() = runTest {
        // Given: Manager with dirty chunks
        val mockRepo = createMockRepository()
        val manager = createChunkManager(mockRepo)

        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Modified",
            lineCount = 1,
            isDirty = true
        )
        manager.addChunk(dirtyChunk)

        // When: Save all dirty chunks
        val result = manager.saveAllDirtyChunks()

        // Then: Save succeeded
        result.isSuccess shouldBe true

        // And: Repository saveFile was called
        coVerify(exactly = 1) { mockRepo.saveFile(any(), any()) }

        // And: Chunks are now clean
        val remainingDirtyChunks = manager.getDirtyChunks()
        remainingDirtyChunks.shouldBeEmpty()
    }

    @Test
    fun `saveAllDirtyChunks failure preserves dirty state`() = runTest {
        // Given: Manager with dirty chunks, repository that fails
        val mockRepo = createMockRepository()
        coEvery { mockRepo.saveFile(any(), any()) } throws RuntimeException("Disk full")

        val manager = createChunkManager(mockRepo)

        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Modified",
            lineCount = 1,
            isDirty = true
        )
        manager.addChunk(dirtyChunk)

        // When: Try to save (will fail)
        val result = manager.saveAllDirtyChunks()

        // Then: Failure
        result.isFailure shouldBe true

        // And: Chunks remain dirty
        val stillDirtyChunks = manager.getDirtyChunks()
        stillDirtyChunks shouldHaveSize 1
        stillDirtyChunks[0].isDirty shouldBe true
    }

    @Test
    fun `evictChunk prevents eviction of dirty chunks`() = runTest {
        // Given: Manager with dirty chunk
        val manager = createChunkManager()

        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Modified",
            lineCount = 1,
            isDirty = true
        )
        manager.addChunk(dirtyChunk)

        // When: Try to evict dirty chunk
        val evicted = manager.evictChunk(dirtyChunk.id)

        // Then: Eviction prevented
        evicted shouldBe false

        // And: Chunk still in manager
        val retrieved = manager.getChunk(dirtyChunk.id)
        retrieved shouldNotBe null
    }

    @Test
    fun `evictChunk allows eviction of clean chunks`() = runTest {
        // Given: Manager with clean chunk
        val manager = createChunkManager()

        val cleanChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Clean",
            lineCount = 1,
            isDirty = false
        )
        manager.addChunk(cleanChunk)

        // When: Evict clean chunk
        val evicted = manager.evictChunk(cleanChunk.id)

        // Then: Eviction succeeded
        evicted shouldBe true

        // And: Chunk no longer in manager
        val retrieved = manager.getChunk(cleanChunk.id)
        retrieved shouldBe null
    }

    // ==================== Boundary Adjustment Tests ====================

    @Test
    fun `loadChunk adjusts boundary when chunk smaller than expected`() = runTest {
        // Given: Repository returns chunk smaller than boundary size
        // This happens when surrogate pair protection truncates content
        val mockRepo = mockk<ChunkRepository>(relaxed = true)
        val smallerContent = "a".repeat(97)  // 97 chars instead of expected 100

        val chunkId = TextChunk.ChunkId.generate()
        val returnedChunk = TextChunk(
            id = chunkId,
            content = smallerContent,  // Smaller than expected
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        coEvery { mockRepo.loadChunk(chunkId, any()) } returns returnedChunk

        val manager = createChunkManager(mockRepo)

        // Setup initial boundary expecting 100 chars (DON'T cache the chunk)
        manager.addBoundaryOnly(chunkId, startOffset = 0L, endOffset = 100L, lineCount = 1)

        // When: Load chunk (which returns smaller content)
        manager.loadChunk(chunkId)

        // Then: Boundary should be adjusted to actual size (97)
        val boundary = manager.getBoundary(chunkId)
        boundary shouldNotBe null
        boundary?.endOffset shouldBe 97L  // Adjusted from 100 to 97
    }

    @Test
    fun `boundary adjustment cascades to next chunk`() = runTest {
        // Given: Two adjacent chunks, first one gets truncated
        val mockRepo = mockk<ChunkRepository>(relaxed = true)

        val chunk1Id = TextChunk.ChunkId.generate()
        val chunk2Id = TextChunk.ChunkId.generate()

        // First chunk returns 97 chars instead of expected 100
        val chunk1 = TextChunk(
            id = chunk1Id,
            content = "a".repeat(97),  // Truncated by 3
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        // Second chunk unchanged
        val chunk2 = TextChunk(
            id = chunk2Id,
            content = "b".repeat(100),
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        coEvery { mockRepo.loadChunk(chunk1Id, any()) } returns chunk1
        coEvery { mockRepo.loadChunk(chunk2Id, any()) } returns chunk2

        val manager = createChunkManager(mockRepo)

        // Setup boundaries: chunk1 [0-100), chunk2 [100-200) (DON'T cache chunks)
        manager.addBoundaryOnly(chunk1Id, startOffset = 0L, endOffset = 100L, lineCount = 1)
        manager.addBoundaryOnly(chunk2Id, startOffset = 100L, endOffset = 200L, lineCount = 1)

        // When: Load first chunk (gets truncated to 97)
        manager.loadChunk(chunk1Id)

        // Then: First chunk boundary adjusted
        val boundary1 = manager.getBoundary(chunk1Id)
        boundary1?.endOffset shouldBe 97L

        // And: Second chunk boundary CASCADE adjusted (start moved from 100 to 97)
        val boundary2 = manager.getBoundary(chunk2Id)
        boundary2?.startOffset shouldBe 97L  // Cascaded from chunk1's new end
        boundary2?.endOffset shouldBe 197L   // Shifted by 3
    }

    @Test
    fun `multiple chunk adjustments maintain gap-free boundaries`() = runTest {
        // Given: Three chunks, first two get truncated
        val mockRepo = mockk<ChunkRepository>(relaxed = true)

        val chunk1Id = TextChunk.ChunkId.generate()
        val chunk2Id = TextChunk.ChunkId.generate()
        val chunk3Id = TextChunk.ChunkId.generate()

        val chunk1 = TextChunk(
            id = chunk1Id,
            content = "a".repeat(98),  // Truncated by 2
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        val chunk2 = TextChunk(
            id = chunk2Id,
            content = "b".repeat(99),  // Truncated by 1
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        val chunk3 = TextChunk(
            id = chunk3Id,
            content = "c".repeat(100),  // No truncation
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        coEvery { mockRepo.loadChunk(chunk1Id, any()) } returns chunk1
        coEvery { mockRepo.loadChunk(chunk2Id, any()) } returns chunk2
        coEvery { mockRepo.loadChunk(chunk3Id, any()) } returns chunk3

        val manager = createChunkManager(mockRepo)

        // Setup boundaries: [0-100), [100-200), [200-300) (DON'T cache chunks)
        manager.addBoundaryOnly(chunk1Id, 0L, 100L, lineCount = 1)
        manager.addBoundaryOnly(chunk2Id, 100L, 200L, lineCount = 1)
        manager.addBoundaryOnly(chunk3Id, 200L, 300L, lineCount = 1)

        // When: Load all chunks sequentially
        manager.loadChunk(chunk1Id)
        manager.loadChunk(chunk2Id)
        manager.loadChunk(chunk3Id)

        // Then: Verify gap-free boundaries
        val boundary1 = manager.getBoundary(chunk1Id)
        val boundary2 = manager.getBoundary(chunk2Id)
        val boundary3 = manager.getBoundary(chunk3Id)

        // Chunk 1: [0-98)
        boundary1?.startOffset shouldBe 0L
        boundary1?.endOffset shouldBe 98L

        // Chunk 2: [98-197) - cascaded from chunk1 + own truncation
        boundary2?.startOffset shouldBe 98L  // Chunk1's end
        boundary2?.endOffset shouldBe 197L   // 98 + 99

        // Chunk 3: [197-297) - cascaded from chunk2
        boundary3?.startOffset shouldBe 197L // Chunk2's end
        boundary3?.endOffset shouldBe 297L   // 197 + 100

        // Verify no gaps: each chunk's end = next chunk's start
        boundary1?.endOffset shouldBe boundary2?.startOffset
        boundary2?.endOffset shouldBe boundary3?.startOffset
    }
}
