package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.LocalPath
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

@Config(qualifiers = "w400dp-h800dp")
class DeleteConfirmationDialogTouchModeTest : ComposeTest() {

    // Robolectric reads the flag when the activity window is added, so it has to be set before the
    // compose rule launches its activity.
    @get:Rule(order = -100)
    val touchModeRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
                base.evaluate()
            }
        }
    }

    private val localFile = LocalPath.build("/storage/emulated/0/Download/local.txt")

    private val toggleLabel = "Delete permanently instead"
    private val cancelAction = "Cancel"
    private val moveAction = "Move"

    private var inputModeManager: InputModeManager? = null

    private fun setDialog() {
        composeTestRule.setContent {
            inputModeManager = LocalInputModeManager.current
            PreviewWrapper {
                DeleteConfirmationDialog(
                    items = setOf(localFile),
                    trashEnabled = true,
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun `in touch mode nothing is focused`() {
        setDialog()

        composeTestRule.runOnIdle { inputModeManager!!.inputMode shouldBe InputMode.Touch }
        composeTestRule.onNodeWithText(moveAction).assertIsNotFocused()
        composeTestRule.onNodeWithText(cancelAction).assertIsNotFocused()
        composeTestRule.onNodeWithText(toggleLabel).assertIsNotFocused()
    }

    @Test
    fun `switching from touch to keyboard mode focuses the confirm button`() {
        setDialog()

        composeTestRule.runOnIdle { inputModeManager!!.inputMode shouldBe InputMode.Touch }
        composeTestRule.onNodeWithText(moveAction).assertIsNotFocused()

        composeTestRule.runOnIdle { inputModeManager!!.requestInputMode(InputMode.Keyboard) }

        composeTestRule.runOnIdle { inputModeManager!!.inputMode shouldBe InputMode.Keyboard }
        composeTestRule.onNodeWithText(moveAction).assertIsFocused()
    }
}
