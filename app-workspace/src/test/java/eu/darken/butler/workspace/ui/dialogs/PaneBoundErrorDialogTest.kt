package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.ErrorDialog
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import eu.darken.butler.workspace.ui.modal.PaneLayerHost
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * [ErrorDialog] is the one shared dialog that has to work in both worlds, and the only one that
 * carries up to three actions. Rendered inside a pane it goes through the pane-bound renderer, so
 * its action row is measured by real layout here rather than by a platform window.
 */
class PaneBoundErrorDialogTest : ComposeTest() {

    private val surface = PaneBoundAlertDialogDefaults.SURFACE_TEST_TAG

    private class TestError(
        private val withInfo: Boolean,
        private val withFix: Boolean,
        val onInfo: () -> Unit = {},
        val onFix: () -> Unit = {},
    ) : RuntimeException("boom"), HasLocalizedError {
        override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
            throwable = this,
            label = LABEL.toCaString(),
            description = "Something went wrong".toCaString(),
            infoActionLabel = if (withInfo) INFO.toCaString() else null,
            infoAction = if (withInfo) onInfo else null,
            fixActionLabel = if (withFix) FIX.toCaString() else null,
            fixAction = if (withFix) onFix else null,
        )
    }

    @Composable
    private fun Case(
        error: Throwable,
        onDismiss: () -> Unit = {},
        paneWidth: Dp = WIDE_PANE,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            PreviewWrapper {
                Box(modifier = Modifier.size(width = paneWidth, height = 600.dp)) {
                    PaneLayerHost(modifier = Modifier.fillMaxSize(), paneFocused = true) {
                        ErrorDialog(throwable = error, onDismiss = onDismiss)
                    }
                }
            }
        }
    }

    @Test
    fun `a plain error offers a single acknowledging action`() {
        composeTestRule.setContent { Case(error = TestError(withInfo = false, withFix = false)) }

        composeTestRule.onNodeWithText(LABEL).assertExists()
        composeTestRule.onNodeWithText(INFO).assertDoesNotExist()
        composeTestRule.onNodeWithText(FIX).assertDoesNotExist()
        composeTestRule.onNodeWithText(DISMISS).assertDoesNotExist()
    }

    @Test
    fun `an error with a fix offers dismiss and fix`() {
        composeTestRule.setContent { Case(error = TestError(withInfo = false, withFix = true)) }

        composeTestRule.onNodeWithText(DISMISS).assertExists()
        composeTestRule.onNodeWithText(FIX).assertExists()
        composeTestRule.onNodeWithText(INFO).assertDoesNotExist()
    }

    @Test
    fun `an error with info and fix offers all three in reading order`() {
        composeTestRule.setContent { Case(error = TestError(withInfo = true, withFix = true)) }

        val info = composeTestRule.onNodeWithText(INFO).getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText(DISMISS).getUnclippedBoundsInRoot()
        val fix = composeTestRule.onNodeWithText(FIX).getUnclippedBoundsInRoot()

        (info.left < dismiss.left) shouldBe true
        (dismiss.left < fix.left) shouldBe true
    }

    @Test
    fun `the info action does not dismiss the dialog`() {
        var infoRuns = 0
        var dismissals = 0

        composeTestRule.setContent {
            Case(
                error = TestError(withInfo = true, withFix = true, onInfo = { infoRuns++ }),
                onDismiss = { dismissals++ },
            )
        }

        composeTestRule.onNodeWithText(INFO).performClick()

        composeTestRule.runOnIdle {
            infoRuns shouldBe 1
            dismissals shouldBe 0
        }
    }

    @Test
    fun `the dismiss action does not run the fix`() {
        var fixRuns = 0
        var dismissals = 0

        composeTestRule.setContent {
            Case(
                error = TestError(withInfo = true, withFix = true, onFix = { fixRuns++ }),
                onDismiss = { dismissals++ },
            )
        }

        composeTestRule.onNodeWithText(DISMISS).performClick()

        composeTestRule.runOnIdle {
            fixRuns shouldBe 0
            dismissals shouldBe 1
        }
    }

    @Test
    fun `the fix action runs and dismisses`() {
        var fixRuns = 0
        var dismissals = 0

        composeTestRule.setContent {
            Case(
                error = TestError(withInfo = true, withFix = true, onFix = { fixRuns++ }),
                onDismiss = { dismissals++ },
            )
        }

        composeTestRule.onNodeWithText(FIX).performClick()

        composeTestRule.runOnIdle {
            fixRuns shouldBe 1
            dismissals shouldBe 1
        }
    }

    /**
     * Asserting the nodes exist proves nothing about clipping — a `Row` handed to the action slot
     * as one placeable stays a single line and simply runs off the surface.
     */
    @Test
    fun `three actions stay inside a narrow surface`() {
        composeTestRule.setContent {
            Case(error = TestError(withInfo = true, withFix = true), paneWidth = NARROW_PANE)
        }

        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()
        listOf(INFO, DISMISS, FIX).forEach { label ->
            val action = composeTestRule.onNodeWithText(label).getUnclippedBoundsInRoot()
            (action.left >= surfaceBounds.left) shouldBe true
            (action.right <= surfaceBounds.right) shouldBe true
        }
    }

    @Test
    fun `three actions stay inside a narrow surface in a right-to-left layout`() {
        composeTestRule.setContent {
            Case(
                error = TestError(withInfo = true, withFix = true),
                paneWidth = NARROW_PANE,
                layoutDirection = LayoutDirection.Rtl,
            )
        }

        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()
        listOf(INFO, DISMISS, FIX).forEach { label ->
            val action = composeTestRule.onNodeWithText(label).getUnclippedBoundsInRoot()
            (action.left >= surfaceBounds.left) shouldBe true
            (action.right <= surfaceBounds.right) shouldBe true
        }

        // Mirrored: the first action in reading order now sits furthest right
        val info = composeTestRule.onNodeWithText(INFO).getUnclippedBoundsInRoot()
        val fix = composeTestRule.onNodeWithText(FIX).getUnclippedBoundsInRoot()
        (fix.left < info.left) shouldBe true
    }

    companion object {
        private const val LABEL = "Access denied"
        private const val INFO = "Details"
        private const val FIX = "Grant access"

        /** `general_dismiss_action`, resolved by the shared dialog itself. */
        private const val DISMISS = "Dismiss"

        private val WIDE_PANE = 600.dp

        /** Narrow enough that three minimum-width text buttons cannot share one line. */
        private val NARROW_PANE = 240.dp
    }
}
