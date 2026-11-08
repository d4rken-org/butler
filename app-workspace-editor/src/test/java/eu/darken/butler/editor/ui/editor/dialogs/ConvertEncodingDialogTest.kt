package eu.darken.butler.editor.ui.editor.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.editor.core.EditorCharsets
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class ConvertEncodingDialogTest : ComposeTest() {

    @Test
    fun `lists every allowed charset except the current one and reports the pick`() {
        var selected: String? = null
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    ConvertEncodingDialog(
                        currentEncoding = "GB18030",
                        onSelect = { selected = it },
                        onDismiss = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("GB18030").assertDoesNotExist()
        EditorCharsets.allowlist.map { it.name() }.filter { it != "GB18030" }.forEach { name ->
            composeTestRule.onNodeWithText(name).assertExists()
        }

        composeTestRule.onNodeWithText("UTF-8").performClick()
        selected shouldBe "UTF-8"
    }

    @Test
    fun `the current charset matches by alias`() {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    ConvertEncodingDialog(
                        currentEncoding = "utf8",
                        onSelect = {},
                        onDismiss = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("UTF-8").assertDoesNotExist()
        composeTestRule.onNodeWithText("GB18030").assertExists()
    }
}
