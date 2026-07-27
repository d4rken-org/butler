package eu.darken.butler.common.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * The window host around [ButlerAlertDialogContent]. Everything about how the content is laid out is
 * covered once against the bare shell in [ButlerAlertDialogContentTest] — what is host-specific is
 * that the platform window renders the shell at all, that it carries the neutral slot Material's own
 * `AlertDialog` lacks, and that the action row survives a body taller than the window.
 */
class ButlerAlertDialogTest : ComposeTest() {

    private val surface = ButlerAlertDialogDefaults.SURFACE_TEST_TAG

    @Composable
    private fun Case(
        neutralButton: (@Composable () -> Unit)? = null,
        text: (@Composable () -> Unit)? = { Text(TEXT) },
    ) {
        PreviewWrapper {
            ButlerAlertDialog(
                onDismissRequest = {},
                title = { Text(TITLE) },
                text = text,
                confirmButton = { TextButton(onClick = {}) { Text(CONFIRM) } },
                dismissButton = { TextButton(onClick = {}) { Text(DISMISS) } },
                neutralButton = neutralButton,
            )
        }
    }

    @Test
    fun `the window dialog renders title, text and actions`() {
        composeTestRule.setContent { Case() }

        composeTestRule.onNodeWithTag(surface).assertExists()
        composeTestRule.onNodeWithText(TITLE).assertExists()
        composeTestRule.onNodeWithText(TEXT).assertExists()
        composeTestRule.onNodeWithText(CONFIRM).assertExists()
        composeTestRule.onNodeWithText(DISMISS).assertExists()
    }

    @Test
    fun `a neutral action renders and receives clicks in a window dialog`() {
        var cleared = false

        composeTestRule.setContent {
            Case(neutralButton = { TextButton(onClick = { cleared = true }) { Text(NEUTRAL) } })
        }

        composeTestRule.onNodeWithText(NEUTRAL).performClick()

        composeTestRule.runOnIdle { cleared shouldBe true }
    }

    /**
     * `BasicAlertDialog` bounds the width but not the height, so the shell's height comes from the
     * host binding it to the window. Without that the body would push the action row — which sits
     * outside the scroll region — off the surface, and the dialog would have no way out.
     */
    @Test
    fun `the action row stays inside the surface when the body overflows the window`() {
        composeTestRule.setContent {
            Case(text = { Box(modifier = Modifier.size(TALL_BODY).testTag(BODY_TAG)) })
        }

        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()
        val confirm = composeTestRule.onNodeWithText(CONFIRM).getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText(DISMISS).getUnclippedBoundsInRoot()

        listOf(confirm, dismiss).forEach { action ->
            (action.top >= surfaceBounds.top) shouldBe true
            (action.bottom <= surfaceBounds.bottom) shouldBe true
        }
    }

    companion object {
        private const val TITLE = "Rename"
        private const val TEXT = "Enter a new name for this tab."
        private const val CONFIRM = "Rename it"
        private const val DISMISS = "Cancel"
        private const val NEUTRAL = "Clear"
        private const val BODY_TAG = "dialog.body"

        /** Taller than any window Robolectric hands the dialog, so the scroll region has to give. */
        private val TALL_BODY = DpSize(width = 200.dp, height = 4000.dp)
    }
}
