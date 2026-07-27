package eu.darken.butler.workspace.ui.dialogs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
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

    /**
     * "Show details" is the neutral action, so on a row that fits it hugs the start and the two
     * actions that resolve the error keep the end — the same shape every other three-action dialog
     * has, and the same reading order the error dialog's own row used to produce.
     */
    @Test
    fun `a wide surface holds all three actions in one row`() {
        composeTestRule.setContent { Case(error = TestError(withInfo = true, withFix = true)) }

        val info = composeTestRule.onNodeWithText(INFO).getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText(DISMISS).getUnclippedBoundsInRoot()
        val fix = composeTestRule.onNodeWithText(FIX).getUnclippedBoundsInRoot()

        info.top shouldBe dismiss.top
        dismiss.top shouldBe fix.top
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
     * The wrapped order is the one thing the shared row does differently from the hand-rolled row it
     * replaced: that one kept `info → dismiss → fix` and pushed the *fix* onto the second line, this
     * one keeps dismiss and fix together and drops the neutral "Show details" below them, so the
     * actions that resolve the error stay most prominent.
     *
     * Asserting the nodes merely exist would prove nothing about clipping — a `Row` handed to the
     * action slot as one placeable stays a single line and simply runs off the surface — so the
     * tier, the order and the surface bounds are all pinned.
     */
    @Test
    fun `a narrow surface drops the details action below dismiss and fix`() {
        composeTestRule.setContent {
            Case(error = TestError(withInfo = true, withFix = true), paneWidth = NARROW_PANE)
        }

        val info = composeTestRule.onNodeWithText(INFO).getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText(DISMISS).getUnclippedBoundsInRoot()
        val fix = composeTestRule.onNodeWithText(FIX).getUnclippedBoundsInRoot()

        dismiss.top shouldBe fix.top
        (dismiss.left < fix.left) shouldBe true
        (info.top >= dismiss.bottom) shouldBe true

        // Details hugs the logical start, which is the physical left here
        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()
        (info.left - surfaceBounds.left < surfaceBounds.right - info.right) shouldBe true

        assertActionsWrapWithinSurface()
    }

    @Test
    fun `the wrapped actions mirror in a right-to-left layout`() {
        composeTestRule.setContent {
            Case(
                error = TestError(withInfo = true, withFix = true),
                paneWidth = NARROW_PANE,
                layoutDirection = LayoutDirection.Rtl,
            )
        }

        val info = composeTestRule.onNodeWithText(INFO).getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText(DISMISS).getUnclippedBoundsInRoot()
        val fix = composeTestRule.onNodeWithText(FIX).getUnclippedBoundsInRoot()

        // The exact mirror of the assertion above, so neither can pass without the row mirroring
        dismiss.top shouldBe fix.top
        (fix.left < dismiss.left) shouldBe true
        (info.top >= dismiss.bottom) shouldBe true

        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()
        (surfaceBounds.right - info.right < info.left - surfaceBounds.left) shouldBe true

        assertActionsWrapWithinSurface()
    }

    /**
     * Placement order is focus order, so the wrapped tier moves "Show details" to the end of the
     * keyboard traversal as well as to the bottom of the row. Pinning it here keeps the two from
     * drifting apart.
     */
    @Test
    fun `keyboard focus reaches the details action last when the row wraps`() {
        var focusManager: FocusManager? = null

        composeTestRule.setContent {
            focusManager = LocalFocusManager.current
            Case(error = TestError(withInfo = true, withFix = true), paneWidth = NARROW_PANE)
        }

        // The tier has to have held, or this would be the single-row traversal instead
        val info = composeTestRule.onNodeWithText(INFO).getUnclippedBoundsInRoot()
        val dismiss = composeTestRule.onNodeWithText(DISMISS).getUnclippedBoundsInRoot()
        (info.top >= dismiss.bottom) shouldBe true

        composeTestRule.onNodeWithText(DISMISS).requestFocus()
        composeTestRule.onNodeWithText(DISMISS).assertIsFocused()

        composeTestRule.runOnIdle { focusManager!!.moveFocus(FocusDirection.Next) }
        composeTestRule.onNodeWithText(FIX).assertIsFocused()

        composeTestRule.runOnIdle { focusManager!!.moveFocus(FocusDirection.Next) }
        composeTestRule.onNodeWithText(INFO).assertIsFocused()
    }

    /**
     * [NARROW_PANE] is sized so three minimum-width text buttons cannot share a row. Asserting only
     * that they stay inside the surface would pass vacuously if that sizing were wrong and they all
     * fit on one line after all, so the wrap itself is pinned first — a bad threshold then shows up
     * as a failure here instead of as a green test that checks nothing.
     */
    private fun assertActionsWrapWithinSurface() {
        val labels = listOf(INFO, DISMISS, FIX)
        val bounds = labels.map { composeTestRule.onNodeWithText(it).getUnclippedBoundsInRoot() }

        bounds.map { it.top }.distinct().size shouldBe 2

        val surfaceBounds = composeTestRule.onNodeWithTag(surface).getUnclippedBoundsInRoot()
        bounds.forEach { action ->
            (action.left >= surfaceBounds.left) shouldBe true
            (action.right <= surfaceBounds.right) shouldBe true
        }
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
