package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest
import eu.darken.butler.common.compose.PreviewWrapper

class EditorSearchBarTest : ComposeTest() {

    private fun results(count: Int) = List(count) {
        SearchResult(position = TextPosition(it * 5L, it.toLong(), 0), matchText = "hit")
    }

    private fun setBar(
        query: String = "",
        searchResults: List<SearchResult> = emptyList(),
        onQueryChange: (TextFieldValue) -> Unit = {},
        onNext: () -> Unit = {},
        onPrevious: () -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                EditorSearchBar(
                    searchQuery = TextFieldValue(query),
                    searchResults = searchResults,
                    currentIndex = 0,
                    caseSensitive = false,
                    regexEnabled = false,
                    wholeWord = false,
                    onSearchQueryChange = onQueryChange,
                    onCaseSensitiveToggle = {},
                    onRegexToggle = {},
                    onWholeWordToggle = {},
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onClose = onClose,
                )
            }
        }
    }

    @Test
    fun `typing forwards query changes`() {
        var lastQuery: TextFieldValue? = null
        setBar(onQueryChange = { lastQuery = it })

        composeTestRule.onNodeWithContentDescription("Search options").assertExists()
        // The query field is the only text input in the bar
        composeTestRule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("needle")
        composeTestRule.waitForIdle()

        lastQuery?.text shouldBe "needle"
    }

    @Test
    fun `navigation buttons are disabled without results`() {
        setBar(query = "needle", searchResults = emptyList())

        composeTestRule.onNodeWithContentDescription("Previous result").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Next result").assertIsNotEnabled()
    }

    @Test
    fun `navigation buttons fire with results`() {
        var next = false
        var previous = false
        setBar(query = "needle", searchResults = results(3), onNext = { next = true }, onPrevious = { previous = true })

        composeTestRule.onNodeWithContentDescription("Next result").performClick()
        composeTestRule.onNodeWithContentDescription("Previous result").performClick()

        next.shouldBeTrue()
        previous.shouldBeTrue()
    }

    @Test
    fun `close button fires`() {
        var closed = false
        setBar(onClose = { closed = true })

        composeTestRule.onNodeWithContentDescription("Close").performClick()

        closed.shouldBeTrue()
    }
}
