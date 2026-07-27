package eu.darken.butler.explorer.ui.explorer.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.files.archive.ArchiveFormat
import eu.darken.butler.common.files.archive.CompressionPreset
import eu.darken.butler.workspace.ui.dialogs.PaneBoundAlertDialogDefaults
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
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

    /**
     * The pane-bound dialog scrolls its own title/text block, so it measures the text slot with an
     * unbounded height. A nested scroller in there rejects an infinite vertical constraint and the
     * whole dialog fails to measure — which is why this is the tallest migrated dialog and gets its
     * own measurement case in a pane far too short for its content.
     */
    @Test
    fun `it measures inside a pane far shorter than its content`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.size(width = 320.dp, height = SHORT_PANE_HEIGHT)) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        CompressOptionsDialog(
                            suggestedName = "archive",
                            onDismiss = {},
                            onConfirm = { _, _, _, _ -> },
                        )
                    }
                }
            }
        }

        val surfaceBounds = composeTestRule.onNodeWithTag(PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG)
            .getUnclippedBoundsInRoot()

        // The nested scroller either threw during measurement or, where it did not, measured the
        // surface straight past the pane it has to fit inside. Both show up here.
        (surfaceBounds.height <= SHORT_PANE_HEIGHT) shouldBe true

        // The action row sits outside the dialog's own scroll container, so it must have been
        // measured too rather than pushed off the end of an unbounded column.
        composeTestRule.onNodeWithText("Create").assertExists()
    }

    companion object {
        /** Far shorter than the dialog's content, so the height cap is actually exercised. */
        private val SHORT_PANE_HEIGHT = 260.dp
    }
}
