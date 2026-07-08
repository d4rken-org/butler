package eu.darken.butler.editor.ui.editor.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.SearchResult
import eu.darken.butler.editor.core.engine.TextPosition
import org.junit.Test
import testhelpers.ComposeTest

/**
 * Truncated-line rendering (Robolectric: existence/no-crash coverage, not pixels): the "⋯ +N"
 * marker, and the clamps that keep engine columns far past the display cap (search results,
 * selectAll on truncated lines) from crashing layout - notably the selection fallback that
 * used to build a Box wider than Compose Constraints can represent.
 */
class TextLineItemTruncationTest : ComposeTest() {

    private val cappedLine = "0123456789".repeat(5)

    @Test
    fun `marker is shown exactly for lines with hidden chars`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Column {
                    TextLineItem(
                        lineIndex = 0,
                        lineContent = cappedLine,
                        hiddenChars = 1234L,
                        cursorPosition = TextPosition(0, 0, 0),
                        selection = null,
                        isCurrentLine = false,
                        isFocused = false,
                        wordWrap = false,
                        fontSize = 14,
                        tabSize = 4,
                        charWidthPx = 8.4f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextLineItem(
                        lineIndex = 1,
                        lineContent = "normal line",
                        hiddenChars = 0L,
                        cursorPosition = TextPosition(0, 0, 0),
                        selection = null,
                        isCurrentLine = false,
                        isFocused = false,
                        wordWrap = false,
                        fontSize = 14,
                        tabSize = 4,
                        charWidthPx = 8.4f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        composeTestRule.onAllNodesWithTag(EDITOR_TRUNCATION_MARKER_TEST_TAG).assertCountEquals(1)
    }

    @Test
    fun `cursor column far past the cap does not crash`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Column {
                    for ((index, wordWrap) in listOf(false, true).withIndex()) {
                        TextLineItem(
                            lineIndex = index.toLong(),
                            lineContent = cappedLine,
                            hiddenChars = 5_000_000L,
                            cursorPosition = TextPosition(0, index.toLong(), 5_000_000),
                            selection = null,
                            isCurrentLine = true,
                            isFocused = true,
                            wordWrap = wordWrap,
                            fontSize = 14,
                            tabSize = 4,
                            charWidthPx = 8.4f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `selection spanning far past the cap does not crash`() {
        // Regression: unclamped selection columns reached the estimation fallback, whose
        // (end - start) * charWidth Box width overflowed Compose Constraints
        composeTestRule.setContent {
            PreviewWrapper {
                TextLineItem(
                    lineIndex = 0,
                    lineContent = cappedLine,
                    hiddenChars = 99_999_950L,
                    cursorPosition = TextPosition(0, 0, 2),
                    selection = TextPosition(0, 0, 2) to TextPosition(0, 0, 100_000_000),
                    isCurrentLine = true,
                    isFocused = true,
                    wordWrap = false,
                    fontSize = 14,
                    tabSize = 4,
                    charWidthPx = 8.4f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `search highlight far past the cap does not crash`() {
        composeTestRule.setContent {
            PreviewWrapper {
                TextLineItem(
                    lineIndex = 0,
                    lineContent = cappedLine,
                    hiddenChars = 5_000_000L,
                    cursorPosition = TextPosition(0, 0, 0),
                    selection = null,
                    isCurrentLine = false,
                    isFocused = false,
                    wordWrap = false,
                    fontSize = 14,
                    tabSize = 4,
                    charWidthPx = 8.4f,
                    searchHighlights = listOf(
                        0 to SearchResult(
                            position = TextPosition(offset = 4_000_000L, line = 0, column = 4_000_000),
                            matchText = "needle",
                        ),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `selection handle endpoint far beyond the cap does not crash`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.fillMaxSize()) {
                    val listState = rememberLazyListState()
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(count = 1) {
                            Text(text = cappedLine, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    SelectionHandle(
                        position = TextPosition(offset = 0L, line = 0, column = 5_000_000),
                        contentListState = listState,
                        lineNumberWidth = 0.dp,
                        horizontalScrollState = rememberScrollState(),
                        actualCharWidth = 8f,
                        onDrag = {},
                        visibleLineContent = mapOf(0L to cappedLine),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }
}
