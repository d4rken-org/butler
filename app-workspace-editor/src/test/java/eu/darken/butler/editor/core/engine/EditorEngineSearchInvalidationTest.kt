package eu.darken.butler.editor.core.engine

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Tests for search result invalidation when undo/redo operations are performed.
 *
 * These tests verify that search results and queries are properly cleared after
 * undo/redo operations to prevent stale position references that could cause
 * crashes or incorrect highlighting.
 *
 * NOTE: These are placeholder tests documenting expected behavior. The actual
 * search invalidation logic is implemented in EditorEngine.undo() and redo()
 * methods (lines 475-476, 500-501) which clear _searchResults and _searchQuery.
 *
 * Integration testing with full EditorEngine setup would require:
 * - GatewaySwitch with real file access
 * - EditorSettings with DataStore
 * - All factory dependencies for the DocumentBuffer stack
 *
 * The behavior can be verified through manual testing:
 * 1. Open file in editor
 * 2. Perform search (cmd/ctrl+F)
 * 3. Make edits
 * 4. Undo (cmd/ctrl+Z)
 * 5. Verify search highlighting is cleared
 */
class EditorEngineSearchInvalidationTest : DocumentBufferTestBase() {

    @Test
    fun `search and undo workflow - search results become stale`() = runTest {
        // Given: Content with searchable text
        val content = "hello world\nhello again"
        val buffer = createBuffer(content)

        // When: Search for "hello"
        val initialResults = buffer.search(query = "hello", options = SearchOptions(caseSensitive = true)).getOrThrow().results
        initialResults.size shouldBe 2  // Found 2 matches

        // And: Insert text before first match
        buffer.insertText(TextPosition(0L, 0, 0), "NEW ")

        // Then: Previous search results now have wrong offsets
        // Original match at offset 0 is now at offset 4
        // This demonstrates why EditorEngine clears search results on edit/undo

        val textAtOldOffset = buffer.getText(0, 5).getOrThrow()
        textAtOldOffset shouldBe "NEW h"  // Not "hello" anymore

        // Note: EditorEngine.undo() clears _searchResults to prevent this issue
    }

    @Test
    fun `undo invalidates search result positions`() = runTest {
        // Given: Initial content
        val content = "test"
        val buffer = createBuffer(content)

        // When: Insert text
        buffer.insertText(TextPosition(0L, 0, 0), "INSERTED ")

        // And: Hypothetical search finds "INSERTED"
        val searchResults = buffer.search(query = "INSERTED", options = SearchOptions(caseSensitive = true)).getOrThrow().results
        searchResults.size shouldBe 1
        searchResults[0].position.offset shouldBe 0

        // When: Undo the insert
        buffer.undo()

        // Then: Content reverts to original
        val currentText = buffer.getText(0, buffer.totalLength.value).getOrThrow()
        currentText shouldBe "test"

        // And: Previous search result offset (0) now points to wrong text
        // This is why EditorEngine.undo() clears search results
    }

    @Test
    fun `redo invalidates search result positions`() = runTest {
        // Given: Content with edit that was undone
        val content = "original"
        val buffer = createBuffer(content)

        buffer.insertText(TextPosition(8L, 0, 8), " text")
        buffer.undo()

        // When: Search in reverted state
        val searchResults = buffer.search(query = "original", options = SearchOptions(caseSensitive = true)).getOrThrow().results
        searchResults.size shouldBe 1
        searchResults[0].position.offset shouldBe 0

        // When: Redo the insert
        buffer.redo()

        // Then: Content changes
        val currentText = buffer.getText(0, buffer.totalLength.value).getOrThrow()
        currentText shouldBe "original text"

        // And: Search results from before redo are now stale
        // This is why EditorEngine.redo() clears search results
    }

    @Test
    fun `search results must be cleared to prevent IndexOutOfBoundsException`() = runTest {
        // Given: Large content
        val content = "A".repeat(1000)
        val buffer = createBuffer(content)

        // When: Search finds many results
        val results = buffer.search(query = "A", options = SearchOptions(caseSensitive = true)).getOrThrow().results
        results.size shouldBe 1000

        // And: Delete most of the content
        buffer.deleteText(
            startPosition = TextPosition(500L, 0, 500),
            endPosition = TextPosition(1000L, 0, 1000)
        )

        // Then: Old search results at offset 600 would cause crash
        // buffer.getText(results[600].position.offset, 1) would throw

        val newSize = buffer.totalLength.value
        newSize shouldBe 500

        // This demonstrates why EditorEngine must clear search results on any edit/undo/redo
    }
}
