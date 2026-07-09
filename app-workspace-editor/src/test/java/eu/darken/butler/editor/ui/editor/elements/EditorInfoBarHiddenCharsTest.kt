package eu.darken.butler.editor.ui.editor.elements

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.engine.LineEnding
import org.junit.Test
import testhelpers.ComposeTest

class EditorInfoBarHiddenCharsTest : ComposeTest() {

    private fun setBar(
        hiddenChars: Long = 0,
        selectedCharacterCount: Long = 0,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                EditorInfoBar(
                    fileSize = 1024L,
                    totalLines = 5,
                    cursorLine = 2,
                    cursorColumn = 4,
                    hiddenChars = hiddenChars,
                    selectedCharacterCount = selectedCharacterCount,
                    fileEncoding = "UTF-8",
                    lineEnding = LineEnding.LF,
                )
            }
        }
    }

    @Test
    fun `hidden-char chip shows when the cursor line has hidden content`() {
        setBar(hiddenChars = 1234)
        composeTestRule.onNodeWithText("1234", substring = true).assertExists()
    }

    @Test
    fun `no hidden-char chip when nothing is hidden`() {
        setBar(hiddenChars = 0)
        composeTestRule.onAllNodesWithText("hidden", substring = true).assertCountEquals(0)
    }

    @Test
    fun `hidden-char chip is hidden while a selection is active`() {
        setBar(hiddenChars = 1234, selectedCharacterCount = 20)
        composeTestRule.onAllNodesWithText("hidden", substring = true).assertCountEquals(0)
    }
}
