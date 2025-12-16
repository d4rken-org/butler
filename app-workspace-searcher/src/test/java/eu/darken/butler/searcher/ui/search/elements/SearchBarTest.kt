package eu.darken.butler.searcher.ui.search.elements

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class SearchBarTest : ComposeTest() {

    @Test
    fun `displays query text`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SearchBar(
                    query = TextFieldValue("my search query"),
                    onQueryChange = {},
                    onSearch = {},
                    isSearching = false,
                )
            }
        }

        composeTestRule.onNodeWithText("my search query").assertIsDisplayed()
    }

    @Test
    fun `displays placeholder when empty`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SearchBar(
                    query = TextFieldValue(""),
                    onQueryChange = {},
                    onSearch = {},
                    isSearching = false,
                )
            }
        }

        // Placeholder is rendered inside decorationBox, use hasText with substring matching
        composeTestRule.onNode(hasText("Search", substring = true)).assertIsDisplayed()
    }

    @Test
    fun `clear button appears when text is present`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SearchBar(
                    query = TextFieldValue("some text"),
                    onQueryChange = {},
                    onSearch = {},
                    isSearching = false,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Clear").assertIsDisplayed()
    }

    @Test
    fun `clear button clears text when clicked`() {
        var lastValue: TextFieldValue? = null

        composeTestRule.setContent {
            PreviewWrapper {
                SearchBar(
                    query = TextFieldValue("some text"),
                    onQueryChange = { lastValue = it },
                    onSearch = {},
                    isSearching = false,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Clear").performClick()

        lastValue?.text shouldBe ""
    }

    @Test
    fun `cancel button appears during search`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SearchBar(
                    query = TextFieldValue("searching…"),
                    onQueryChange = {},
                    onSearch = {},
                    isSearching = true,
                    onCancel = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Cancel").assertIsDisplayed()
    }

    @Test
    fun `cancel button invokes onCancel callback`() {
        var cancelled = false

        composeTestRule.setContent {
            PreviewWrapper {
                SearchBar(
                    query = TextFieldValue("searching…"),
                    onQueryChange = {},
                    onSearch = {},
                    isSearching = true,
                    onCancel = { cancelled = true },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Cancel").performClick()

        cancelled shouldBe true
    }

    @Test
    fun `cancel button not shown when not searching`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SearchBar(
                    query = TextFieldValue("some text"),
                    onQueryChange = {},
                    onSearch = {},
                    isSearching = false,
                    onCancel = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Cancel").assertDoesNotExist()
    }

    @Test
    fun `search icon is always displayed`() {
        composeTestRule.setContent {
            PreviewWrapper {
                SearchBar(
                    query = TextFieldValue(""),
                    onQueryChange = {},
                    onSearch = {},
                    isSearching = false,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test
    fun `onQueryChange is invoked when text changes`() {
        var changedValue: TextFieldValue? = null

        composeTestRule.setContent {
            PreviewWrapper {
                SearchBar(
                    query = TextFieldValue(""),
                    onQueryChange = { changedValue = it },
                    onSearch = {},
                    isSearching = false,
                )
            }
        }

        // Find the text field by its action capability, not placeholder text
        composeTestRule.onNode(hasSetTextAction()).performTextInput("new text")

        changedValue?.text shouldBe "new text"
    }
}
