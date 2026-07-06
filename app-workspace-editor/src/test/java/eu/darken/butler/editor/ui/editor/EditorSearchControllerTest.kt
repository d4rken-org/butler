package eu.darken.butler.editor.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.editor.core.EditorWorkspace
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.SearchOptions
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import testhelpers.BaseTest

class EditorSearchControllerTest : BaseTest() {

    private fun results(count: Int): List<SearchResult> = List(count) { i ->
        SearchResult(position = TextPosition(offset = i * 10L, line = i.toLong(), column = 0), matchText = "match")
    }

    private fun mockWorkspace(
        searchResults: List<SearchResult> = emptyList(),
        cursorOffset: Long = 0L,
    ): EditorWorkspace {
        val wsState = MutableStateFlow<EditorWorkspace.State>(
            EditorWorkspace.State.Ready(
                EditorWorkspace.EditorState(
                    searchResults = searchResults,
                    cursorPosition = TextPosition(cursorOffset, 0, cursorOffset.toInt()),
                ),
            ),
        )
        return mockk<EditorWorkspace>().apply {
            every { state } returns wsState
            coEvery { search(any(), any()) } returns Result.success(searchResults)
            coEvery { setCursorPosition(any()) } returns Unit
        }
    }

    private fun CoroutineScope.controller(workspace: EditorWorkspace) = EditorSearchController(
        scope = this,
        doLaunch = { block -> launch { block() } },
        workspace = { workspace },
        tag = "test",
    )

    @Test
    fun `typed queries debounce before searching`() = runTest {
        val workspace = mockWorkspace(results(1))
        val controller = controller(workspace)

        controller.updateQuery(TextFieldValue("needle"))
        runCurrent()
        coVerify(exactly = 0) { workspace.search(any(), any()) }

        advanceTimeBy(EditorSearchController.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        coVerify(exactly = 1) { workspace.search("needle", any()) }
    }

    @Test
    fun `rapid typing runs only the final query`() = runTest {
        val workspace = mockWorkspace(results(1))
        val controller = controller(workspace)

        controller.updateQuery(TextFieldValue("n"))
        advanceTimeBy(100)
        controller.updateQuery(TextFieldValue("ne"))
        advanceTimeBy(100)
        controller.updateQuery(TextFieldValue("needle"))
        advanceTimeBy(EditorSearchController.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        coVerify(exactly = 1) { workspace.search(any(), any()) }
        coVerify(exactly = 1) { workspace.search("needle", any()) }
    }

    @Test
    fun `option toggles re-search immediately with the new options`() = runTest {
        val workspace = mockWorkspace(results(1))
        val controller = controller(workspace)
        controller.updateQuery(TextFieldValue("needle"))
        advanceTimeBy(EditorSearchController.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        controller.toggleCaseSensitivity()
        runCurrent()

        coVerify(exactly = 1) {
            workspace.search("needle", SearchOptions(caseSensitive = true, useRegex = false, wholeWord = false))
        }
    }

    @Test
    fun `successful search resets the index and jumps to the first match`() = runTest {
        val matches = results(3)
        val workspace = mockWorkspace(matches)
        val controller = controller(workspace)

        controller.updateQuery(TextFieldValue("match"))
        advanceTimeBy(EditorSearchController.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        controller.state.first().currentResultIndex shouldBe 0
        coVerify { workspace.setCursorPosition(matches[0].position) }
    }

    @Test
    fun `next and previous wrap around the result list`() = runTest {
        val matches = results(3)
        val workspace = mockWorkspace(matches)
        val controller = controller(workspace)

        controller.nextResult()
        runCurrent()
        controller.state.first().currentResultIndex shouldBe 1

        controller.nextResult()
        controller.nextResult()
        runCurrent()
        controller.state.first().currentResultIndex shouldBe 0

        controller.previousResult()
        runCurrent()
        controller.state.first().currentResultIndex shouldBe 2
        coVerify { workspace.setCursorPosition(matches[2].position) }
    }

    @Test
    fun `navigation is a no-op without results`() = runTest {
        val workspace = mockWorkspace(emptyList())
        val controller = controller(workspace)

        controller.nextResult()
        controller.previousResult()
        runCurrent()

        controller.state.first().currentResultIndex shouldBe 0
        coVerify(exactly = 0) { workspace.setCursorPosition(any()) }
    }

    @Test
    fun `a new search starts at the first match at or after the cursor`() = runTest {
        val matches = results(3) // offsets 0, 10, 20
        val workspace = mockWorkspace(matches, cursorOffset = 12L)
        val controller = controller(workspace)

        controller.updateQuery(TextFieldValue("match"))
        advanceTimeBy(EditorSearchController.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        controller.state.first().currentResultIndex shouldBe 2
        coVerify { workspace.setCursorPosition(matches[2].position) }
    }

    @Test
    fun `replace-current adopts the engine outcome and advances`() = runTest {
        val matches = results(3)
        val workspace = mockWorkspace(matches)
        coEvery { workspace.replaceCurrent(any(), any(), any(), any()) } returns Result.success(
            EditorEngine.ReplaceOutcome(results = matches.drop(1), nextIndex = 1),
        )
        val controller = controller(workspace)
        controller.updateQuery(TextFieldValue("match"))
        advanceTimeBy(EditorSearchController.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        controller.updateReplaceQuery(TextFieldValue("swap"))

        controller.replaceCurrent()
        runCurrent()

        coVerify { workspace.replaceCurrent("match", any(), matches[0], "swap") }
        controller.state.first().currentResultIndex shouldBe 1
    }

    @Test
    fun `replace-all reports the outcome as a notice`() = runTest {
        val workspace = mockWorkspace(results(2))
        coEvery { workspace.replaceAll(any(), any(), any()) } returns Result.success(
            EditorEngine.ReplaceAllOutcome(count = 2, undoable = true),
        )
        val controller = controller(workspace)
        controller.updateQuery(TextFieldValue("match"))
        advanceTimeBy(EditorSearchController.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        controller.updateReplaceQuery(TextFieldValue("swap"))

        controller.replaceAll()
        runCurrent()

        val state = controller.state.first()
        state.replaceNotice shouldBe EditorSearchController.ReplaceNotice(count = 2, undoable = true)
        state.currentResultIndex shouldBe 0
        coVerify { workspace.replaceAll("match", any(), "swap") }
    }

    @Test
    fun `replace-all with an empty query is a no-op`() = runTest {
        val workspace = mockWorkspace(results(2))
        val controller = controller(workspace)

        controller.replaceAll()
        runCurrent()

        coVerify(exactly = 0) { workspace.replaceAll(any(), any(), any()) }
    }

    @Test
    fun `closing the search clears the query and hides the bar`() = runTest {
        val workspace = mockWorkspace(results(1))
        val controller = controller(workspace)
        controller.showSearchBar()
        controller.updateQuery(TextFieldValue("needle"))
        advanceTimeBy(EditorSearchController.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        controller.closeSearch()
        runCurrent()

        val state = controller.state.first()
        state.showSearchBar shouldBe false
        state.queryInput.text shouldBe ""
        state.showReplaceRow shouldBe false
        state.replaceNotice shouldBe null
        // The clear goes through the tracked job as an empty-query search
        coVerify { workspace.search("", any()) }
    }
}
