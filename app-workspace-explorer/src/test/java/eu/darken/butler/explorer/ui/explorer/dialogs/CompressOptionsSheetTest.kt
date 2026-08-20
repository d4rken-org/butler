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
import eu.darken.butler.workspace.ui.bottomsheet.PaneScopedBottomSheetDefaults
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest
import testhelpers.TestApplication

/**
 * The sheet anchors to the bottom of its pane, and the pane here is the test root, so the root has
 * to be tall enough to hold the whole form — injected touches below the root land on nothing while
 * still reporting success.
 */
@Config(application = TestApplication::class, sdk = [34], qualifiers = "w400dp-h900dp")
class CompressOptionsSheetTest : ComposeTest() {

    private var confirmed: List<Any?>? = null

    // Segment labels sit inside the segmented button's own clickable node, not on a leaf Text, so
    // target the clickable ancestor and scroll it into view first.
    private fun clickSegment(label: String) =
        composeTestRule.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()

    private fun setSheetContent(
        defaultFormat: ArchiveFormat = ArchiveFormat.ZIP,
    ) {
        composeTestRule.setContent {
            PreviewWrapper {
                PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                    CompressOptionsSheet(
                        suggestedName = "archive",
                        defaultFormat = defaultFormat,
                        onDismiss = {},
                        onConfirm = { name, format, preset, password ->
                            confirmed = listOf(name, format, preset, password)
                        },
                    )
                }
            }
        }
    }

    @Test
    fun `create disabled when name is blank`() {
        setSheetContent()
        composeTestRule.onNodeWithText("archive").performTextReplacement(" ")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun `empty password confirms with null password`() {
        setSheetContent()
        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()
        confirmed shouldBe listOf("archive", ArchiveFormat.ZIP, CompressionPreset.NORMAL, null)
    }

    @Test
    fun `mismatched passwords disable create until they match`() {
        setSheetContent()
        composeTestRule.onNodeWithText("Password (optional)").performTextInput("hunter2")
        composeTestRule.onNodeWithText("Confirm password").performTextInput("hunter")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()

        composeTestRule.onNodeWithText("Confirm password").performTextReplacement("hunter2")
        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()
        confirmed shouldBe listOf("archive", ArchiveFormat.ZIP, CompressionPreset.NORMAL, "hunter2")
    }

    @Test
    fun `weak password shows hint but does not block`() {
        setSheetContent()
        composeTestRule.onNodeWithText("Password (optional)").performTextInput("abc")
        composeTestRule.onNodeWithText("Weak password").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm password").performTextInput("abc")
        composeTestRule.onNodeWithText("Create").assertIsEnabled()
    }

    @Test
    fun `switching to tar gz hides password fields and drops typed password`() {
        setSheetContent()
        composeTestRule.onNodeWithText("Password (optional)").performTextInput("hunter2")
        composeTestRule.onNodeWithText("Confirm password").performTextInput("hunter2")

        clickSegment("tar.gz")
        composeTestRule.onNodeWithText("Password (optional)").assertDoesNotExist()
        composeTestRule.onNodeWithText("Confirm password").assertDoesNotExist()

        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()
        confirmed shouldBe listOf("archive", ArchiveFormat.TAR_GZ, CompressionPreset.NORMAL, null)
    }

    /** Hiding the fields must not discard what was typed — the round trip has to bring it back. */
    @Test
    fun `switching back to zip restores the typed password`() {
        setSheetContent()
        composeTestRule.onNodeWithText("Password (optional)").performTextInput("hunter2")
        composeTestRule.onNodeWithText("Confirm password").performTextInput("hunter2")

        clickSegment("tar.gz")
        clickSegment("zip")

        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()
        confirmed shouldBe listOf("archive", ArchiveFormat.ZIP, CompressionPreset.NORMAL, "hunter2")
    }

    /**
     * A password left half-confirmed when the user moves to a format that takes none must not keep
     * Create disabled: the field explaining the block is no longer on screen.
     */
    @Test
    fun `a mismatched password stops blocking create once tar gz is selected`() {
        setSheetContent()
        composeTestRule.onNodeWithText("Password (optional)").performTextInput("hunter2")
        composeTestRule.onNodeWithText("Confirm password").performTextInput("hunter")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()

        clickSegment("tar.gz")
        composeTestRule.onNodeWithText("Create").assertIsEnabled().performClick()

        confirmed shouldBe listOf("archive", ArchiveFormat.TAR_GZ, CompressionPreset.NORMAL, null)
    }

    /** The format caption is what warns the user that tar.gz drops the password fields. */
    @Test
    fun `the format caption follows the selected format`() {
        setSheetContent()
        composeTestRule.onNodeWithText("Opens on any device. Can be password protected.").assertIsDisplayed()

        clickSegment("tar.gz")
        composeTestRule.onNodeWithText("Smaller for many files. No password protection.").assertIsDisplayed()
    }

    @Test
    fun `level selection is forwarded`() {
        setSheetContent()
        clickSegment("Best")
        composeTestRule.onNodeWithText("Create").performClick()
        confirmed shouldBe listOf("archive", ArchiveFormat.ZIP, CompressionPreset.BEST, null)
    }

    @Test
    fun `unoffered default format falls back to zip`() {
        setSheetContent(defaultFormat = ArchiveFormat.TAR_BZ2)
        composeTestRule.onNodeWithText("Create").performClick()
        confirmed shouldBe listOf("archive", ArchiveFormat.ZIP, CompressionPreset.NORMAL, null)
    }

    /**
     * This is the tallest form in the explorer, so it is the one that has to prove the sheet bounds
     * itself to a pane far shorter than its content instead of running off the end of it.
     */
    @Test
    @Config(qualifiers = "w320dp-h260dp")
    fun `it measures inside a pane far shorter than its content`() {
        composeTestRule.setContent {
            PreviewWrapper {
                Box(modifier = Modifier.size(width = 320.dp, height = SHORT_PANE_HEIGHT)) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        CompressOptionsSheet(
                            suggestedName = "archive",
                            onDismiss = {},
                            onConfirm = { _, _, _, _ -> },
                        )
                    }
                }
            }
        }

        val cardBounds = composeTestRule.onNodeWithTag(PaneScopedBottomSheetDefaults.CARD_TEST_TAG)
            .getUnclippedBoundsInRoot()

        (cardBounds.height <= SHORT_PANE_HEIGHT) shouldBe true

        // Reachable by scrolling the sheet's own content region rather than pushed off its end.
        composeTestRule.onNodeWithText("Create").performScrollTo().assertIsDisplayed()
    }

    companion object {
        /** Far shorter than the sheet's content, so the height cap is actually exercised. */
        private val SHORT_PANE_HEIGHT = 260.dp
    }
}
