package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.workspace.ui.LocalWorkspaceFocused
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
        visible: Boolean = true,
        searchResults: List<SearchResult> = emptyList(),
        currentIndex: Int = 0,
        searchTruncated: Boolean = false,
        onQueryChange: (TextFieldValue) -> Unit = {},
        onNext: () -> Unit = {},
        onPrevious: () -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                EditorSearchBar(
                    visible = visible,
                    searchQuery = TextFieldValue(query),
                    searchResults = searchResults,
                    currentIndex = currentIndex,
                    searchTruncated = searchTruncated,
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
    fun `replace row fires replace callbacks`() {
        var replacedOne = false
        var replacedAll = false
        composeTestRule.setContent {
            PreviewWrapper {
                EditorSearchBar(
                    visible = true,
                    searchQuery = TextFieldValue("needle"),
                    searchResults = results(2),
                    currentIndex = 0,
                    caseSensitive = false,
                    regexEnabled = false,
                    wholeWord = false,
                    showReplaceRow = true,
                    onSearchQueryChange = {},
                    onCaseSensitiveToggle = {},
                    onRegexToggle = {},
                    onWholeWordToggle = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                    onReplaceCurrent = { replacedOne = true },
                    onReplaceAll = { replacedAll = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Replace", useUnmergedTree = true).onParent()
        composeTestRule.onAllNodesWithText("Replace")[1].performClick()
        composeTestRule.onNodeWithText("All").performClick()

        replacedOne.shouldBeTrue()
        replacedAll.shouldBeTrue()
    }

    @Test
    fun `read-only editors get no replace affordance`() {
        composeTestRule.setContent {
            PreviewWrapper {
                EditorSearchBar(
                    visible = true,
                    searchQuery = TextFieldValue("needle"),
                    searchResults = results(2),
                    currentIndex = 0,
                    caseSensitive = false,
                    regexEnabled = false,
                    wholeWord = false,
                    showReplaceRow = true,
                    replaceAllowed = false,
                    onSearchQueryChange = {},
                    onCaseSensitiveToggle = {},
                    onRegexToggle = {},
                    onWholeWordToggle = {},
                    onPrevious = {},
                    onNext = {},
                    onClose = {},
                )
            }
        }

        composeTestRule.onAllNodesWithText("Replace").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("All").assertCountEquals(0)
    }

    @Test
    fun `close button fires`() {
        var closed = false
        setBar(onClose = { closed = true })

        composeTestRule.onNodeWithContentDescription("Close").performClick()

        closed.shouldBeTrue()
    }

    @Test
    fun `truncated results show the capped counter`() {
        setBar(query = "e", searchResults = results(10), searchTruncated = true)

        composeTestRule.onNodeWithText("1 of 10+ results").assertExists()
    }

    @Test
    fun `untruncated results keep the plural counter`() {
        setBar(query = "e", searchResults = results(3))

        composeTestRule.onNodeWithText("1 of 3 results").assertExists()
    }

    @Test
    fun `capped counter clamps an out-of-range index`() {
        // Async state skew can briefly pair a stale index with a fresh capped list
        setBar(query = "e", searchResults = results(10), currentIndex = 25, searchTruncated = true)

        composeTestRule.onNodeWithText("10 of 10+ results").assertExists()
    }

    /**
     * FloatingBarStack keeps hidden bars composed, so these drive [EditorSearchBar.visible] from the
     * test while the workspace stays focused. The sibling field stands in for the editor's own input:
     * a hidden bar must neither take focus nor clear focus held outside it.
     */
    private fun setBarWithSibling(
        visible: MutableState<Boolean>,
        siblingRequester: FocusRequester,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                CompositionLocalProvider(LocalWorkspaceFocused provides true) {
                    Column {
                        BasicTextField(
                            value = "",
                            onValueChange = {},
                            modifier = Modifier
                                .testTag(SIBLING_TAG)
                                .focusRequester(siblingRequester),
                        )
                        EditorSearchBar(
                            visible = visible.value,
                            searchQuery = TextFieldValue(""),
                            searchResults = emptyList(),
                            currentIndex = 0,
                            caseSensitive = false,
                            regexEnabled = false,
                            wholeWord = false,
                            onSearchQueryChange = {},
                            onCaseSensitiveToggle = {},
                            onRegexToggle = {},
                            onWholeWordToggle = {},
                            onPrevious = {},
                            onNext = {},
                            onClose = {},
                        )
                    }
                }
            }
        }
    }

    private val queryField
        get() = composeTestRule.onNode(hasSetTextAction() and hasTestTag(SIBLING_TAG).not())

    private val siblingField
        get() = composeTestRule.onNodeWithTag(SIBLING_TAG)

    @Test
    fun `a hidden bar does not take focus`() {
        val visible = mutableStateOf(false)
        val siblingRequester = FocusRequester()
        setBarWithSibling(visible, siblingRequester)

        composeTestRule.runOnIdle { siblingRequester.requestFocus() }

        queryField.assertIsNotFocused()
        siblingField.assertIsFocused()
    }

    @Test
    fun `showing the bar focuses the query field`() {
        val visible = mutableStateOf(false)
        val siblingRequester = FocusRequester()
        setBarWithSibling(visible, siblingRequester)

        composeTestRule.runOnIdle { siblingRequester.requestFocus() }
        composeTestRule.runOnIdle { visible.value = true }
        composeTestRule.waitForIdle()

        queryField.assertIsFocused()
    }

    @Test
    fun `hiding the bar releases the query field`() {
        val visible = mutableStateOf(true)
        val siblingRequester = FocusRequester()
        setBarWithSibling(visible, siblingRequester)

        queryField.assertIsFocused()

        composeTestRule.runOnIdle { visible.value = false }
        composeTestRule.waitForIdle()

        queryField.assertIsNotFocused()
    }

    @Test
    fun `hiding the bar leaves focus outside it alone`() {
        val visible = mutableStateOf(true)
        val siblingRequester = FocusRequester()
        setBarWithSibling(visible, siblingRequester)

        composeTestRule.runOnIdle { siblingRequester.requestFocus() }
        siblingField.assertIsFocused()

        composeTestRule.runOnIdle { visible.value = false }
        composeTestRule.waitForIdle()

        siblingField.assertIsFocused()
    }

    companion object {
        private const val SIBLING_TAG = "sibling-field"
    }
}
