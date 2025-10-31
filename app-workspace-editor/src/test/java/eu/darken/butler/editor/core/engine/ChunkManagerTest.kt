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
        coEvery { mockRepo.saveFile(any()) } returns Unit
        return mockRepo
    }

    private fun createChunkManager(repository: ChunkRepository = createMockRepository()): ChunkManager {
        return ChunkManager(workspaceId, repository)
    }

    // ==================== mergeChunks() Algorithm Tests ====================

    @Test
    fun `mergeChunks with empty dirty list returns original content`() {
        // Given: Original content
        val original = "Hello World".toByteArray()
        val dirtyChunks = emptyList<TextChunk>()

        // When: Merge with no dirty chunks
        val result = ChunkManager.mergeChunks(original, dirtyChunks)

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
            startOffset = 0L,
            endOffset = 5L,
            content = "HELLO",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(original, listOf(dirtyChunk))

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
            startOffset = 6L,
            endOffset = 11L,
            content = "There",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(original, listOf(dirtyChunk))

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
            startOffset = 6L,
            endOffset = 11L,
            content = "WORLD",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(original, listOf(dirtyChunk))

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
            startOffset = 6L,
            endOffset = 11L,  // Original end
            content = "Beautiful World",  // Longer content
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(original, listOf(dirtyChunk))

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
            startOffset = 6L,
            endOffset = 21L,  // Original end
            content = "There",  // Shorter content
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(original, listOf(dirtyChunk))

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
            startOffset = 0L,
            endOffset = 4L,
            content = "1111",
            lineCount = 1,
            isDirty = true
        )
        val chunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            startOffset = 10L,
            endOffset = 14L,
            content = "3333",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge (provide in wrong order to test sorting)
        val result = ChunkManager.mergeChunks(original, listOf(chunk2, chunk1))

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
            startOffset = 0L,
            endOffset = 4L,
            content = "1111",
            lineCount = 1,
            isDirty = true
        )
        val chunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            startOffset = 5L,
            endOffset = 9L,
            content = "2222",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(original, listOf(chunk1, chunk2))

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
            startOffset = 0L,
            endOffset = 5L,
            content = "Goodbye",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(original, listOf(dirtyChunk))

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
            startOffset = 0L,
            endOffset = 4L,
            content = "1111",
            lineCount = 1,
            isDirty = true
        )
        val chunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            startOffset = 15L,
            endOffset = 19L,
            content = "4444",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(original, listOf(chunk1, chunk2))

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
            startOffset = 7L,
            endOffset = 13L,  // "Line 2"
            content = "Modified Line",
            lineCount = 1,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(original, listOf(dirtyChunk))

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
            startOffset = 5L,
            endOffset = 11L,  // " World"
            content = "",  // Empty
            lineCount = 0,
            isDirty = true
        )

        // When: Merge
        val result = ChunkManager.mergeChunks(original, listOf(dirtyChunk))

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
            startOffset = 0L,
            endOffset = 100L,
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
            startOffset = 0L,
            endOffset = 100L,
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
            startOffset = 0L,
            endOffset = 100L,
            content = "Clean 1",
            lineCount = 1,
            isDirty = false
        )
        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            startOffset = 100L,
            endOffset = 200L,
            content = "Dirty",
            lineCount = 1,
            isDirty = true
        )
        val cleanChunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            startOffset = 200L,
            endOffset = 300L,
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
            startOffset = 200L,
            endOffset = 300L,
            content = "Chunk 3",
            lineCount = 1,
            isDirty = true
        )
        val chunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            startOffset = 0L,
            endOffset = 100L,
            content = "Chunk 1",
            lineCount = 1,
            isDirty = true
        )
        val chunk3 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            startOffset = 100L,
            endOffset = 200L,
            content = "Chunk 2",
            lineCount = 1,
            isDirty = true
        )

        // Add in wrong order
        manager.addChunk(chunk1)
        manager.addChunk(chunk2)
        manager.addChunk(chunk3)

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
            startOffset = 0L,
            endOffset = 100L,
            content = "Chunk 1",
            lineCount = 1,
            isDirty = true
        )
        val chunk2 = TextChunk(
            id = TextChunk.ChunkId.generate(),
            startOffset = 100L,
            endOffset = 200L,
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
            startOffset = 0L,
            endOffset = 100L,
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
            startOffset = 0L,
            endOffset = 100L,
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
        coVerify(exactly = 1) { mockRepo.saveFile(any()) }

        // And: Chunks are now clean
        val remainingDirtyChunks = manager.getDirtyChunks()
        remainingDirtyChunks.shouldBeEmpty()
    }

    @Test
    fun `saveAllDirtyChunks failure preserves dirty state`() = runTest {
        // Given: Manager with dirty chunks, repository that fails
        val mockRepo = createMockRepository()
        coEvery { mockRepo.saveFile(any()) } throws RuntimeException("Disk full")

        val manager = createChunkManager(mockRepo)

        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            startOffset = 0L,
            endOffset = 100L,
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
            startOffset = 0L,
            endOffset = 100L,
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
            startOffset = 0L,
            endOffset = 100L,
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
}
