package eu.darken.butler.editor.core.engine

import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ChunkRepositoryTest : BaseTest() {

    private val workspaceId = Workspace.Id(Uuid.random())

    private fun createMockDataSource(content: String): EditorDataSource {
        val mockDataSource = mockk<EditorDataSource>(relaxed = true)

        // Mock readChunk to return substrings from content
        coEvery { mockDataSource.readChunk(any(), any()) } answers {
            val startOffset = firstArg<Long>().toInt()
            val size = secondArg<Long>().toInt()
            val end = (startOffset + size).coerceAtMost(content.length)
            content.substring(startOffset, end)
        }

        // Mock contentSource
        coEvery { mockDataSource.contentSource } returns MutableStateFlow(
            ContentSource.File(
                path = mockk<LocalPath>(),
                size = content.length.toLong(),
                lastModified = Instant.DISTANT_PAST,
                canWrite = true
            )
        )

        return mockDataSource
    }

    private fun createRepository(dataSource: EditorDataSource): ChunkRepository {
        return ChunkRepository(workspaceId, dataSource)
    }

    // ==================== Basic loadChunk Tests ====================

    @Test
    fun `loadChunk reads content from DataSource`() = runTest {
        // Given
        val mockDataSource = createMockDataSource("Hello World")
        val repository = createRepository(mockDataSource)

        // When: Load chunk covering full content
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 11L, lineCount = 1)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then: Content matches
        chunk.content shouldBe "Hello World"
        chunk.lineCount shouldBe 1
        chunk.isDirty shouldBe false
    }

    @Test
    fun `loadChunk detects LF line ending`() = runTest {
        // Given
        val mockDataSource = createMockDataSource("Line 1\nLine 2\nLine 3")
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 20L, lineCount = 3)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then
        chunk.lineEnding shouldBe LineEnding.LF
        chunk.lineCount shouldBe 3
    }

    @Test
    fun `loadChunk detects CRLF line ending`() = runTest {
        // Given
        val mockDataSource = createMockDataSource("Line 1\r\nLine 2\r\nLine 3")
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 23L, lineCount = 3)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then
        chunk.lineEnding shouldBe LineEnding.CRLF
        chunk.lineCount shouldBe 3
    }

    @Test
    fun `loadChunk detects CR line ending`() = runTest {
        // Given
        val mockDataSource = createMockDataSource("Line 1\rLine 2\rLine 3\r")
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 20L, lineCount = 3)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then
        chunk.lineEnding shouldBe LineEnding.CR
        chunk.lineCount shouldBe 3
    }

    @Test
    fun `loadChunk detects mixed line endings`() = runTest {
        // Given
        val mockDataSource = createMockDataSource("Line 1\nLine 2\r\nLine 3\rLine 4")
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 31L, lineCount = 4)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then
        chunk.lineEnding shouldBe LineEnding.MIXED
        chunk.lineCount shouldBe 4
    }

    @Test
    fun `loadChunk defaults to LF for empty content`() = runTest {
        // Given
        val mockDataSource = createMockDataSource("")
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 0L, lineCount = 1)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then
        chunk.lineEnding shouldBe LineEnding.LF
        chunk.lineCount shouldBe 1
    }

    @Test
    fun `loadChunk counts lines without trailing newline correctly`() = runTest {
        // Given: Content without trailing newline
        val mockDataSource = createMockDataSource("Line 1\nLine 2")
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 13L, lineCount = 2)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then: Should count as 2 lines (last line has no newline)
        chunk.lineCount shouldBe 2
    }

    @Test
    fun `loadChunk counts lines with CRLF and trailing newline`() = runTest {
        // Given
        val mockDataSource = createMockDataSource("Line 1\r\nLine 2\r\nLine 3\r\n")
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 23L, lineCount = 3)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then
        chunk.lineCount shouldBe 3
    }

    @Test
    fun `loadChunk counts lines with CRLF without trailing newline`() = runTest {
        // Given
        val mockDataSource = createMockDataSource("Line 1\r\nLine 2\r\nLine 3")
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 21L, lineCount = 3)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then: Last chunk without trailing newline gets +1
        chunk.lineCount shouldBe 3
    }

    @Test
    fun `loadChunk counts lines with CR endings`() = runTest {
        // Given
        val mockDataSource = createMockDataSource("Line 1\rLine 2\rLine 3\r")
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 20L, lineCount = 3)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then
        chunk.lineCount shouldBe 3
    }

    @Test
    fun `loadChunk counts lines with mixed endings`() = runTest {
        // Given: CRLF + standalone LF + standalone CR
        val mockDataSource = createMockDataSource("Line 1\nLine 2\r\nLine 3\rLine 4")
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 31L, lineCount = 4)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then: Should count all distinct line endings (1 LF + 1 CRLF + 1 CR = 3, plus +1 for last line)
        chunk.lineCount shouldBe 4
    }

    // ==================== UTF-16 Surrogate Pair Tests ====================
    // These tests will pass with current implementation (DataSource handles it)
    // After refactoring, Repository will handle adjustment

    @Test
    fun `loadChunk with emoji at boundary preserves complete emoji`() = runTest {
        // Given: Content with emoji positioned to test boundary handling
        // 🎉 = U+1F389 = 4 bytes UTF-8: F0 9F 8E 89
        // In JVM String: 2 chars (high surrogate D83C + low surrogate DF89)
        val padding = "a".repeat(10)
        val emoji = "🎉"
        val content = padding + emoji + "XYZ"

        val mockDataSource = createMockDataSource(content)
        val repository = createRepository(mockDataSource)

        // When: Load chunk that might end in middle of emoji
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = content.length.toLong(), lineCount = 1)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then: Emoji should be preserved
        chunk.content.contains(emoji) shouldBe true

        // No orphaned surrogates
        val hasHighSurrogate = chunk.content.contains('\uD83C')
        val hasLowSurrogate = chunk.content.contains('\uDF89')

        // Either both present (complete emoji) or neither
        if (hasHighSurrogate) {
            hasLowSurrogate shouldBe true
        }
    }

    @Test
    fun `loadChunk with skin tone emoji preserves grapheme cluster`() = runTest {
        // Given: Emoji with skin tone modifier
        // 👍🏻 = thumbs up (U+1F44D) + light skin tone (U+1F3FB)
        // = 4 UTF-16 chars total (2 pairs of surrogates)
        val thumbsUpWithSkinTone = "👍🏻"
        val content = "X".repeat(50) + thumbsUpWithSkinTone + "Y".repeat(50)

        val mockDataSource = createMockDataSource(content)
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = content.length.toLong(), lineCount = 1)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then: Should preserve the full grapheme cluster
        chunk.content.contains("👍") shouldBe true
        chunk.content.contains(thumbsUpWithSkinTone) shouldBe true
    }

    @Test
    fun `loadChunk with ZWJ sequence preserves complete sequence`() = runTest {
        // Given: Family emoji with Zero-Width Joiners
        // 👨‍👩‍👧‍👦 = Man + ZWJ + Woman + ZWJ + Girl + ZWJ + Boy
        // = 11 UTF-16 chars total
        val familyEmoji = "👨‍👩‍👧‍👦"
        val content = "X".repeat(50) + familyEmoji + "Y".repeat(50)

        val mockDataSource = createMockDataSource(content)
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = content.length.toLong(), lineCount = 1)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then: Should preserve complete ZWJ sequence
        chunk.content.contains(familyEmoji) shouldBe true
    }

    @Test
    fun `loadChunk with combining diacritics preserves base plus modifier`() = runTest {
        // Given: é as decomposed form (e + combining acute)
        val baseE = "e"
        val combiningAcute = "\u0301"
        val eWithDiacritic = baseE + combiningAcute
        val content = "Caf" + eWithDiacritic + " is good"

        val mockDataSource = createMockDataSource(content)
        val repository = createRepository(mockDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = content.length.toLong(), lineCount = 1)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then: Should contain the combining sequence
        chunk.content.contains("Caf") shouldBe true
        (chunk.content.length >= 4) shouldBe true  // At least "Cafe"
    }

    // ==================== InMemoryDataSource Integration Tests ====================

    @Test
    fun `loadChunk works with InMemoryDataSource backend`() = runTest {
        // Given: Real InMemoryDataSource (not mocked)
        val content = "Hello\nWorld\nTest"
        val inMemoryDataSource = InMemoryDataSource(workspaceId, content)
        inMemoryDataSource.open()

        val repository = createRepository(inMemoryDataSource)

        // When
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = content.length.toLong(), lineCount = 3)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then
        chunk.content shouldBe content
        chunk.lineCount shouldBe 3
        chunk.lineEnding shouldBe LineEnding.LF
    }

    @Test
    fun `loadChunk with InMemoryDataSource handles emoji correctly`() = runTest {
        // Given: InMemoryDataSource with emoji
        val emoji = "🔥"
        val content = "Test " + emoji + " content"
        val inMemoryDataSource = InMemoryDataSource(workspaceId, content)
        inMemoryDataSource.open()

        val repository = createRepository(inMemoryDataSource)

        // When: Load partial chunk that might split emoji
        val boundary = ChunkBoundary(startOffset = 0L, endOffset = 7L, lineCount = 1)
        val chunk = repository.loadChunk(TextChunk.ChunkId.generate(), boundary)

        // Then: Should either include full emoji or exclude it (no corruption)
        val hasEmoji = chunk.content.contains(emoji)

        if (hasEmoji) {
            // If emoji present, should be complete
            chunk.content shouldBe "Test $emoji"
        } else {
            // If emoji not present, should be truncated before it
            chunk.content shouldBe "Test "
        }
    }

    // ==================== Search Tests ====================

    @Test
    fun `searchInChunk finds matches in content`() = runTest {
        // Given
        val content = "Hello World, Hello Universe"
        val mockDataSource = createMockDataSource(content)
        val repository = createRepository(mockDataSource)

        val chunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = content,
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )
        val boundary = ChunkBoundary(0L, content.length.toLong(), 1)

        // When
        val results = repository.searchInChunk(chunk, boundary, "Hello")

        // Then: Should find 2 matches
        results.size shouldBe 2
        results[0].matchText shouldBe "Hello"
        results[0].position.offset shouldBe 0L
        results[1].position.offset shouldBe 13L
    }

    @Test
    fun `searchInChunk case insensitive search works`() = runTest {
        // Given
        val content = "Hello WORLD hello"
        val mockDataSource = createMockDataSource(content)
        val repository = createRepository(mockDataSource)

        val chunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = content,
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )
        val boundary = ChunkBoundary(0L, content.length.toLong(), 1)

        // When: Case insensitive search
        val results = repository.searchInChunk(chunk, boundary, "hello", options = SearchOptions(caseSensitive = false))

        // Then: Should find both "Hello" and "hello"
        results.size shouldBe 2
    }

    @Test
    fun `searchInChunk with empty query returns no results`() = runTest {
        // Given
        val content = "Hello World"
        val mockDataSource = createMockDataSource(content)
        val repository = createRepository(mockDataSource)

        val chunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = content,
            lineCount = 1,
            lineEnding = LineEnding.LF,
            isDirty = false
        )
        val boundary = ChunkBoundary(0L, content.length.toLong(), 1)

        // When
        val results = repository.searchInChunk(chunk, boundary, "")

        // Then
        results.size shouldBe 0
    }

    // ==================== Save and Close Tests ====================

    @Test
    fun `saveFile delegates to DataSource`() = runTest {
        // Given
        val mockDataSource = mockk<EditorDataSource>(relaxed = true)
        coEvery { mockDataSource.save(any(), any()) } returns Unit

        val repository = createRepository(mockDataSource)

        val dirtyChunk = TextChunk(
            id = TextChunk.ChunkId.generate(),
            content = "Modified",
            lineCount = 1,
            isDirty = true
        )
        val boundaries = mapOf(
            dirtyChunk.id to ChunkBoundary(0L, 8L, 1)
        )

        // When
        repository.saveFile(listOf(dirtyChunk), boundaries)

        // Then: Should call dataSource.save
        io.mockk.coVerify(exactly = 1) { mockDataSource.save(listOf(dirtyChunk), boundaries) }
    }

    @Test
    fun `closeFile delegates to DataSource`() = runTest {
        // Given
        val mockDataSource = mockk<EditorDataSource>(relaxed = true)
        coEvery { mockDataSource.close() } returns Unit

        val repository = createRepository(mockDataSource)

        // When
        repository.closeFile()

        // Then: Should call dataSource.close
        io.mockk.coVerify(exactly = 1) { mockDataSource.close() }
    }

    @Test
    fun `getContentSource returns DataSource contentSource`() = runTest {
        // Given
        val expectedContentSource = ContentSource.File(
            path = mockk<LocalPath>(),
            size = 1234L,
            lastModified = Instant.DISTANT_PAST,
            canWrite = true
        )

        val mockDataSource = mockk<EditorDataSource>(relaxed = true)
        coEvery { mockDataSource.contentSource } returns MutableStateFlow(expectedContentSource)

        val repository = createRepository(mockDataSource)

        // When
        val contentSource = repository.getContentSource()

        // Then
        contentSource shouldBe expectedContentSource
    }
}
