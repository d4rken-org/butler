package eu.darken.butler.editor.core.engine

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for DocumentBuffer search functionality including single-chunk and
 * multi-chunk searches, case sensitivity, and result sorting.
 */
class DocumentBufferSearchTest : DocumentBufferTestBase() {

    @Test
    fun `search in single chunk returns correct line numbers`() = runTest {
        // Given: Content in single chunk with multiple lines
        val content = "Line 0: Hello\nLine 1: World\nLine 2: Hello again\nLine 3: Test"
        val buffer = createBuffer(content)

        // When: Search for "Hello"
        val matches = buffer.search(query = "Hello", options = SearchOptions(caseSensitive = true))

        // Then: Found 2 matches
        matches.size shouldBe 2

        // And: First match is on line 0
        matches[0].position.line shouldBe 0L
        matches[0].matchText shouldBe "Hello"

        // And: Second match is on line 2
        matches[1].position.line shouldBe 2L
        matches[1].matchText shouldBe "Hello"
    }

    @Test
    fun `search spanning multiple chunks returns correct line numbers`() = runTest {
        // Given: Content spanning multiple chunks with search term in each
        val line0 = "SEARCH in chunk 1\n" + "X".repeat(82)  // 100 bytes total
        val line1 = "Y".repeat(82) + "\nSEARCH in chunk 2\n"  // 100 bytes
        val line2 = "Z".repeat(82) + "\nSEARCH in chunk 3"  // 100 bytes
        val content = line0 + line1 + line2
        val buffer = createBuffer(content, blockSize = 100)

        // When: Search for "SEARCH"
        val matches = buffer.search(query = "SEARCH", options = SearchOptions(caseSensitive = true))

        // Then: Found 3 matches
        matches.size shouldBe 3

        // And: Matches have correct file-relative line numbers (not chunk-relative!)
        matches[0].position.line shouldBe 0L  // First line of file
        matches[1].position.line shouldBe 2L  // Third line of file (after line 0 and line 1)
        matches[2].position.line shouldBe 4L  // Fifth line of file
    }

    @Test
    fun `search case-sensitive distinguishes matches`() = runTest {
        // Given: Content with mixed case
        val content = "hello HELLO Hello HeLLo"
        val buffer = createBuffer(content)

        // When: Search case-sensitive for "Hello"
        val matches = buffer.search(query = "Hello", options = SearchOptions(caseSensitive = true))

        // Then: Found only exact match
        matches.size shouldBe 1
        matches[0].matchText shouldBe "Hello"
    }

    @Test
    fun `search returns results sorted by offset`() = runTest {
        // Given: Content with multiple occurrences
        val content = "apple banana apple cherry apple date"
        val buffer = createBuffer(content)

        // When: Search for "apple"
        val matches = buffer.search(query = "apple", options = SearchOptions(caseSensitive = true))

        // Then: Results are sorted by offset
        matches.size shouldBe 3
        matches[0].position.offset shouldBe 0L
        matches[1].position.offset shouldBe 13L
        matches[2].position.offset shouldBe 26L

        // And: Each subsequent offset is greater than previous
        for (i in 1 until matches.size) {
            matches[i].position.offset shouldBeGreaterThan matches[i - 1].position.offset
        }
    }

    @Test
    fun `search with no matches returns empty list`() = runTest {
        // Given: Content without search term
        val content = "This is a test document"
        val buffer = createBuffer(content)

        // When: Search for non-existent term
        val matches = buffer.search(query = "NOTFOUND", options = SearchOptions(caseSensitive = true))

        // Then: Returns empty list (not failure)
        matches.shouldBeEmpty()
    }

    @Test
    fun `search with empty query returns empty list`() = runTest {
        // Given: Any content
        val content = "Some content here"
        val buffer = createBuffer(content)

        // When: Search for empty string
        val matches = buffer.search(query = "", options = SearchOptions(caseSensitive = true))

        // Then: Returns empty list
        matches.shouldBeEmpty()
    }

    @Test
    fun `search result positions match actual text locations`() = runTest {
        // Given: Multi-line content
        val content = "Line one\nLine two with TARGET\nLine three\nTARGET at start"
        val buffer = createBuffer(content)

        // When: Search for "TARGET"
        val matches = buffer.search(query = "TARGET", options = SearchOptions(caseSensitive = true))

        // Then: Found 2 matches
        matches.size shouldBe 2

        // And: Can retrieve actual text at reported offsets
        val text1 = buffer.getText(matches[0].position.offset, matches[0].position.offset + 6).getOrThrow()
        text1 shouldBe "TARGET"

        val text2 = buffer.getText(matches[1].position.offset, matches[1].position.offset + 6).getOrThrow()
        text2 shouldBe "TARGET"
    }
}
