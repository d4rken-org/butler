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
        // Tests flexible chunk size: when chunk1 shrinks, chunk2's START adjusts but END stays fixed
        // This makes chunk2 grow to absorb the gap (no full cascade needed)
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

        // And: Second chunk boundary start adjusted (flexible chunk size - grows to absorb gap)
        val boundary2 = manager.getBoundary(chunk2Id)
        boundary2?.startOffset shouldBe 97L  // Adjusted to connect with chunk1's new end
        boundary2?.endOffset shouldBe 200L   // End stays fixed (chunk grows from 100 to 103 bytes)
    }

    @Test
    fun `multiple chunk adjustments maintain gap-free boundaries`() = runTest {
        // Given: Three chunks, first two get truncated
        // Tests flexible chunk size: when chunk1 shrinks, chunk2's START adjusts (no full cascade)
        // When chunk2 is loaded and shrinks, chunk3's START adjusts
        // Final result: gap-free boundaries with flexible chunk sizes
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
        // Chunk1 load: [0,100) → [0,98), adjusts chunk2 start: [100,200) → [98,200)
        manager.loadChunk(chunk1Id)
        // Chunk2 load: [98,200) → [98,197), adjusts chunk3 start: [200,300) → [197,300)
        manager.loadChunk(chunk2Id)
        // Chunk3 load: [197,300) → [197,297)
        manager.loadChunk(chunk3Id)

        // Then: Verify gap-free boundaries with flexible chunk sizes
        val boundary1 = manager.getBoundary(chunk1Id)
        val boundary2 = manager.getBoundary(chunk2Id)
        val boundary3 = manager.getBoundary(chunk3Id)

        // Chunk 1: [0-98) - shrunk by 2 bytes
        boundary1?.startOffset shouldBe 0L
        boundary1?.endOffset shouldBe 98L

        // Chunk 2: [98-197) - start adjusted from chunk1, content is 99 bytes
        boundary2?.startOffset shouldBe 98L  // Adjusted to connect with chunk1
        boundary2?.endOffset shouldBe 197L   // 98 + 99

        // Chunk 3: [197-297) - start adjusted from chunk2, content is 100 bytes
        boundary3?.startOffset shouldBe 197L // Adjusted to connect with chunk2
        boundary3?.endOffset shouldBe 297L   // 197 + 100

        // Verify no gaps: each chunk's end = next chunk's start
        boundary1?.endOffset shouldBe boundary2?.startOffset
        boundary2?.endOffset shouldBe boundary3?.startOffset
    }

    @Test
    fun `boundary adjustment invalidates lineCount for recalculation`() = runTest {
        // Tests that when chunk boundaries are adjusted (flexible chunk size),
        // the lineCount is invalidated (set to 0) to force recalculation.
        // This prevents stale line counts from causing +1 line errors.
        val mockRepo = mockk<ChunkRepository>(relaxed = true)

        val chunk1Id = TextChunk.ChunkId.generate()
        val chunk2Id = TextChunk.ChunkId.generate()

        val chunk1 = TextChunk(
            id = chunk1Id,
            content = "line1\nline2\nlin",  // 2 lines + partial, truncated by 1 char
            lineCount = 2,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        val chunk2 = TextChunk(
            id = chunk2Id,
            content = "e3\nline4\n",  // Starts with "e" from previous chunk
            lineCount = 2,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        coEvery { mockRepo.loadChunk(chunk1Id, any()) } returns chunk1
        coEvery { mockRepo.loadChunk(chunk2Id, any()) } returns chunk2

        val manager = createChunkManager(mockRepo)

        // Setup boundaries with lineCount already set
        manager.addBoundaryOnly(chunk1Id, 0L, 20L, lineCount = 2)
        manager.addBoundaryOnly(chunk2Id, 20L, 40L, lineCount = 2)

        // When: Load chunk1 (which shrinks due to truncation)
        manager.loadChunk(chunk1Id)

        // Then: Chunk1 boundary adjusted
        val boundary1 = manager.getBoundary(chunk1Id)
        boundary1?.endOffset shouldBe 15L  // Actual content size (6+6+3 = 15 bytes)

        // And: Chunk2 boundary start adjusted (flexible chunk)
        val boundary2 = manager.getBoundary(chunk2Id)
        boundary2?.startOffset shouldBe 15L  // Adjusted to connect with chunk1

        // And: CRITICAL - Chunk2's lineCount INVALIDATED (set to -1)
        // This ensures recalculation during metadata rebuild
        boundary2?.lineCount shouldBe -1  // Must be -1 (sentinel = needs recalc), not the old value of 2

        // And: Chunk1's lineCount ALSO INVALIDATED (ChunkRepository uses isLastChunk=true by default)
        // This prevents +1 errors from chunks loaded with wrong isLastChunk value
        boundary1?.lineCount shouldBe -1  // Must be -1 (sentinel = needs recalc), not 2
    }

    @Test
    fun `boundary adjustment prevents total line count error from stale lineCount values`() = runTest {
        // Regression test for the +1 line counting bug.
        // This test demonstrates the complete scenario that caused the editor to show
        // 10071 lines instead of 10070.
        //
        // THE BUG SCENARIO:
        // 1. ChunkRepository.loadChunk() always uses isLastChunk=true (default)
        // 2. For non-last chunks ending without '\n', this adds wrong +1 to lineCount
        // 3. If we store this wrong lineCount in boundary without invalidation,
        //    buildChunkMetadata() will use it, causing +1 error in total
        //
        // THE FIX:
        // Set lineCount=0 when boundary changes, forcing recalculation with correct isLastChunk value

        val mockRepo = mockk<ChunkRepository>(relaxed = true)

        val chunk1Id = TextChunk.ChunkId.generate()
        val chunk2Id = TextChunk.ChunkId.generate()
        val chunk3Id = TextChunk.ChunkId.generate()

        // Chunk 1: 3 lines, ends without newline (gets truncated by 1 byte)
        val chunk1 = TextChunk(
            id = chunk1Id,
            content = "line1\nline2\nline3",  // 3 lines, NO trailing \n (17 chars)
            lineCount = 4,  // ← WRONG! ChunkRepository counted with isLastChunk=true → 3+1=4
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        // Chunk 2: 3 lines, ends with newline
        val chunk2 = TextChunk(
            id = chunk2Id,
            content = "line4\nline5\nline6\n",  // 3 lines, HAS trailing \n
            lineCount = 3,  // Correct (ends with \n, so no +1)
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        // Chunk 3: 4 lines (last chunk, no trailing newline)
        val chunk3 = TextChunk(
            id = chunk3Id,
            content = "line7\nline8\nline9\nline10",  // 4 lines, NO trailing \n
            lineCount = 4,  // Correct (isLastChunk=true is correct for this one)
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        coEvery { mockRepo.loadChunk(chunk1Id, any()) } returns chunk1
        coEvery { mockRepo.loadChunk(chunk2Id, any()) } returns chunk2
        coEvery { mockRepo.loadChunk(chunk3Id, any()) } returns chunk3

        val manager = createChunkManager(mockRepo)

        // Setup boundaries with initial lineCounts (simulating first load)
        manager.addBoundaryOnly(chunk1Id, 0L, 18L, lineCount = 3)    // Expected 18 bytes
        manager.addBoundaryOnly(chunk2Id, 18L, 36L, lineCount = 3)   // Expected 18 bytes
        manager.addBoundaryOnly(chunk3Id, 36L, 60L, lineCount = 4)   // Last chunk

        // When: Load chunk1 (shrinks from 18 to 17 bytes due to truncation)
        manager.loadChunk(chunk1Id)

        // Then: Chunk1's lineCount MUST be invalidated
        val boundary1 = manager.getBoundary(chunk1Id)
        boundary1?.lineCount shouldBe -1  // Must be -1 (sentinel), NOT 4! (would cause +1 error)

        // And: Chunk2's lineCount MUST be invalidated (its boundary changed)
        val boundary2 = manager.getBoundary(chunk2Id)
        boundary2?.lineCount shouldBe -1  // Must be -1 (sentinel), NOT 3! (needs recalculation)

        // And: Chunk3 unchanged
        val boundary3 = manager.getBoundary(chunk3Id)
        boundary3?.lineCount shouldBe 4  // Still correct (not affected by adjustment)

        // WHAT THIS PREVENTS:
        // If we had used chunk.lineCount (4) instead of 0:
        //   Total = 4 (chunk1, WRONG) + 3 (chunk2) + 4 (chunk3) = 11 lines ❌
        // With lineCount=0 forcing recalculation:
        //   Total = 3 (chunk1, correct) + 3 (chunk2) + 4 (chunk3) = 10 lines ✅
        //
        // This is why the editor was showing 10071 instead of 10070!
    }

    @Test
    fun `last chunk always reaches EOF even after cascade adjustments`() = runTest {
        // This test reproduces the bug where boundary cascade causes the last chunk
        // to shrink and not reach the actual file EOF, orphaning final bytes.
        //
        // Real scenario: File is 2,687,473 bytes with 42 chunks.
        // Last chunk (chunk_41) should be [2,686,976, 2,687,473) = 497 bytes
        // When chunk_40 gets surrogate pair adjustment and shrinks by 2 bytes,
        // the cascade should NOT cause chunk_41 to also shrink - it must stay at EOF.

        val mockRepo = mockk<ChunkRepository>(relaxed = true)
        val fileSize = 2687473L  // Actual file size

        // Simulate last two chunks
        val chunk40Id = TextChunk.ChunkId.generate()
        val chunk41Id = TextChunk.ChunkId.generate()

        // Chunk 40: Gets truncated by surrogate pair adjustment (65534 -> 65532)
        val chunk40 = TextChunk(
            id = chunk40Id,
            content = "a".repeat(65532),  // Truncated from 65534
            lineCount = 300,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        // Chunk 41: Last chunk, should always reach EOF
        val chunk41 = TextChunk(
            id = chunk41Id,
            content = "b".repeat(497),  // Last 497 bytes
            lineCount = 5,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        coEvery { mockRepo.loadChunk(chunk40Id, any()) } returns chunk40
        coEvery { mockRepo.loadChunk(chunk41Id, any()) } returns chunk41

        val manager = createChunkManager(mockRepo)

        // Initialize fileSize so EOF preservation logic knows the true file size
        manager.generateChunkIds(fileSize)
        // Clear the auto-generated chunks and use our custom boundaries instead
        manager.clear()

        // Initial boundaries matching real scenario
        // Chunk 40: [2621358, 2686892) = 65534 bytes
        // Chunk 41: [2686892, 2687473) = 581 bytes (but will be 497 after reading)
        // NOTE: chunk41's boundary will NOT be cascaded when chunk40 shrinks, so size will be 581, not 497
        manager.addBoundaryOnly(chunk40Id, startOffset = 2621358L, endOffset = 2686892L, lineCount = 300)
        manager.addBoundaryOnly(chunk41Id, startOffset = 2686892L, endOffset = fileSize, lineCount = 5)

        // When: Load chunks (chunk40 shrinks, causing cascade)
        manager.loadChunk(chunk40Id)
        manager.loadChunk(chunk41Id)

        // Then: Chunk 40 boundary adjusted
        val boundary40 = manager.getBoundary(chunk40Id)
        boundary40?.startOffset shouldBe 2621358L
        boundary40?.endOffset shouldBe 2686890L  // Shrunk by 2 bytes

        // And: Chunk 41 START adjusted to connect with chunk40's new end (prevents gap!)
        // But END stays at EOF to preserve file size
        val boundary41 = manager.getBoundary(chunk41Id)
        boundary41?.startOffset shouldBe 2686890L  // Adjusted to connect (prevents 2-byte gap!)
        boundary41?.endOffset shouldBe fileSize    // MUST stay at EOF!

        // Verify last chunk grew to absorb the gap (flexible chunk size)
        val lastChunkBoundarySize = (boundary41?.endOffset ?: 0) - (boundary41?.startOffset ?: 0)
        lastChunkBoundarySize shouldBe 583L  // Grew from 581 to 583 (absorbed 2-byte gap)
    }

    @Test
    fun `boundary cascade with multiple adjustments preserves EOF`() = runTest {
        // Tests flexible chunk size with multiple adjustments + EOF preservation
        // Chunk1 shrinks → Chunk2 start adjusts
        // Chunk2 shrinks → Chunk3 start adjusts
        // Chunk3 (last chunk) still reaches EOF despite adjustments
        val mockRepo = mockk<ChunkRepository>(relaxed = true)
        val fileSize = 1000L

        val chunk1Id = TextChunk.ChunkId.generate()
        val chunk2Id = TextChunk.ChunkId.generate()
        val chunk3Id = TextChunk.ChunkId.generate()  // Last chunk

        val chunk1 = TextChunk(
            id = chunk1Id,
            content = "a".repeat(98),  // Truncated by 2
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        val chunk2 = TextChunk(
            id = chunk2Id,
            content = "b".repeat(297),  // Truncated by 3
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        val chunk3 = TextChunk(
            id = chunk3Id,
            content = "c".repeat(605),  // Last chunk - should reach EOF
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )

        coEvery { mockRepo.loadChunk(chunk1Id, any()) } returns chunk1
        coEvery { mockRepo.loadChunk(chunk2Id, any()) } returns chunk2
        coEvery { mockRepo.loadChunk(chunk3Id, any()) } returns chunk3

        val manager = createChunkManager(mockRepo)

        // Initial boundaries: [0-100), [100-400), [400-1000)
        manager.addBoundaryOnly(chunk1Id, 0L, 100L, lineCount = 1)
        manager.addBoundaryOnly(chunk2Id, 100L, 400L, lineCount = 1)
        manager.addBoundaryOnly(chunk3Id, 400L, fileSize, lineCount = 1)

        // When: Load all chunks sequentially
        // Chunk1: [0,100) → [0,98), adjust chunk2: [100,400) → [98,400)
        manager.loadChunk(chunk1Id)
        // Chunk2: [98,400) → [98,395), adjust chunk3: [400,1000) → [395,1000)
        manager.loadChunk(chunk2Id)
        // Chunk3: [395,1000) → [395,1000) (EOF preserved!)
        manager.loadChunk(chunk3Id)

        // Then: Flexible chunk adjustments applied
        val boundary1 = manager.getBoundary(chunk1Id)
        boundary1?.endOffset shouldBe 98L  // Truncated

        val boundary2 = manager.getBoundary(chunk2Id)
        boundary2?.startOffset shouldBe 98L   // Adjusted from chunk1
        boundary2?.endOffset shouldBe 395L     // 98 + 297

        // And: Last chunk reaches EOF despite flexible adjustments
        val boundary3 = manager.getBoundary(chunk3Id)
        boundary3?.startOffset shouldBe 395L  // Adjusted from chunk2
        boundary3?.endOffset shouldBe fileSize  // MUST be EOF (preserved)!

        // Verify chunk3 covers exactly from its adjusted start to EOF
        val actualLastChunkSize = (boundary3?.endOffset ?: 0) - (boundary3?.startOffset ?: 0)
        actualLastChunkSize shouldBe chunk3.content.length.toLong()  // 605 bytes
    }
}
