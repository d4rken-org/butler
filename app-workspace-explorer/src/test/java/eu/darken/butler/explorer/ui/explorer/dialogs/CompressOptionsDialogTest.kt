package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.CompressionPreset
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class CompressOptionsDialogTest : ComposeTest() {

    private var confirmed: List<Any?>? = null

    // Radio labels sit inside a selectable Row; target the clickable ancestor, not the leaf Text,
    // and scroll it into view first so touch coordinates line up under the scrollable dialog body.
    private fun clickSelectable(label: String) =
        composeTestRule.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()

    private fun setDialogContent(
        defaultFormat: ArchiveFormat = ArchiveFormat.ZIP,
    ) {
        composeTestRule.setContent {
            CompressOptionsDialog(
                suggestedName = "archive",
                defaultFormat = defaultFormat,
                onDismiss = {},
                onConfirm = { name, format, preset, password ->
                    confirmed = listOf(name, format, preset, password)
                },
            )
        }
    }

    @Test
    fun `create disabled when name is blank`() {
        setDialogContent()
        composeTestRule.onNodeWithText("archive").performTextReplacement(" ")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun `empty password confirms with null password`() {
        setDialogContent()
        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()
        confirmed shouldBe listOf("archive", ArchiveFormat.ZIP, CompressionPreset.NORMAL, null)
    }

    @Test
    fun `mismatched passwords disable create until they match`() {
        setDialogContent()
        composeTestRule.onNodeWithText("Password (optional)").performTextInput("hunter2")
        composeTestRule.onNodeWithText("Confirm password").performTextInput("hunter")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()

        composeTestRule.onNodeWithText("Confirm password").performTextReplacement("hunter2")
        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()
        confirmed shouldBe listOf("archive", ArchiveFormat.ZIP, CompressionPreset.NORMAL, "hunter2")
    }

    @Test
    fun `weak password shows hint but does not block`() {
        setDialogContent()
        composeTestRule.onNodeWithText("Password (optional)").performTextInput("abc")
        composeTestRule.onNodeWithText("Weak password").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm password").performTextInput("abc")
        composeTestRule.onNodeWithText("Create").assertIsEnabled()
    }

    @Test
    fun `switching to tar gz hides password fields and drops typed password`() {
        setDialogContent()
        composeTestRule.onNodeWithText("Password (optional)").performTextInput("hunter2")
        composeTestRule.onNodeWithText("Confirm password").performTextInput("hunter2")

        clickSelectable("tar.gz")
        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()

        confirmed shouldBe listOf("archive", ArchiveFormat.TAR_GZ, CompressionPreset.NORMAL, null)
    }

    @Test
    fun `level selection is forwarded`() {
        setDialogContent()
        clickSelectable("Best")
        composeTestRule.onNodeWithText("Create").performClick()
        confirmed shouldBe listOf("archive", ArchiveFormat.ZIP, CompressionPreset.BEST, null)
    }

    @Test
    fun `unoffered default format falls back to zip`() {
        setDialogContent(defaultFormat = ArchiveFormat.TAR_BZ2)
        composeTestRule.onNodeWithText("Create").performClick()
        confirmed shouldBe listOf("archive", ArchiveFormat.ZIP, CompressionPreset.NORMAL, null)
    }
}
