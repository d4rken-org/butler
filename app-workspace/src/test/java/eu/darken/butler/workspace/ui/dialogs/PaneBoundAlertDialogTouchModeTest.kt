package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.test.platform.app.InstrumentationRegistry
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import testhelpers.ComposeTest

class PaneBoundAlertDialogTouchModeTest : ComposeTest() {

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

    @Test
    fun `without a focus target an input mode change does not re-run the dismiss effect`() {
        var hideCount = 0
        val keyboardController = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() {
                hideCount++
            }
        }
        var inputModeManager: InputModeManager? = null

        composeTestRule.setContent {
            inputModeManager = LocalInputModeManager.current
            PreviewWrapper {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboardController) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        PaneBoundAlertDialog(
                            onDismissRequest = {},
                            confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                            dismissButton = { TextButton(onClick = {}) { Text("Cancel") } },
                        )
                    }
                }
            }
        }

        composeTestRule.runOnIdle {
            inputModeManager!!.inputMode shouldBe InputMode.Touch
            hideCount shouldBe 1
        }

        composeTestRule.runOnIdle { inputModeManager!!.requestInputMode(InputMode.Keyboard) }

        composeTestRule.runOnIdle {
            inputModeManager!!.inputMode shouldBe InputMode.Keyboard
            hideCount shouldBe 1
        }
    }
}
