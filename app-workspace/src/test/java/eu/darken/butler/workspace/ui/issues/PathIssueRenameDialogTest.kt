package eu.darken.butler.workspace.ui.issues

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class PathIssueRenameDialogTest : ComposeTest() {

    private fun setDialog(
        currentName: String = "file.txt",
        initialSelection: TextRange? = null,
        onConfirm: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    PathIssueRenameDialog(
                        currentName = currentName,
                        initialSelection = initialSelection,
                        onConfirm = onConfirm,
                        onDismiss = {},
                    )
                }
            }
        }
    }

    @Test
    fun `confirms a new name from the keyboard action`() {
        var result: String? = null
        setDialog(onConfirm = { result = it })

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("  renamed.txt  ")
        composeTestRule.onNode(hasSetTextAction()).performImeAction()

        composeTestRule.runOnIdle { result shouldBe "renamed.txt" }
    }

    @Test
    fun `the keyboard action does nothing while the name is unchanged`() {
        var result: String? = null
        setDialog(onConfirm = { result = it })

        composeTestRule.onNode(hasSetTextAction()).performImeAction()

        composeTestRule.runOnIdle { result shouldBe null }
    }

    private fun selection(): TextRange = composeTestRule.onNode(hasSetTextAction())
        .fetchSemanticsNode()
        .config[SemanticsProperties.TextSelectionRange]

    @Test
    fun `the initial selection covers the stem of a name with an extension`() {
        setDialog(currentName = "photo.jpg", initialSelection = initialRenameSelection("photo.jpg"))

        selection() shouldBe TextRange(0, 5)
    }

    @Test
    fun `the initial selection covers a name without an extension`() {
        setDialog(currentName = "README", initialSelection = initialRenameSelection("README"))

        selection() shouldBe TextRange(0, 6)
    }

    @Test
    fun `the initial selection covers a whole dotfile name`() {
        setDialog(currentName = ".gitignore", initialSelection = initialRenameSelection(".gitignore"))

        selection() shouldBe TextRange(0, 10)
    }

    @Test
    fun `without an initial selection the cursor starts at the beginning`() {
        setDialog(currentName = "photo.jpg")

        selection() shouldBe TextRange.Zero
    }
}
