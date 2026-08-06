package eu.darken.butler.common.error

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.common.R
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * [ErrorDialog] runs whatever action an error hands it: a throwing action must neither take the UI
 * down with it nor leave the dialog latched on the error it was supposed to resolve. The shapes the
 * dialog renders and its success paths are covered by `PaneBoundErrorDialogTest`.
 */
class ErrorDialogGuardTest : ComposeTest() {

    private var dismissals = 0

    private class TestError(
        private val onFix: (() -> Unit)? = null,
        private val onInfo: (() -> Unit)? = null,
        private val fixErrorMessage: String? = null,
    ) : Exception("boom"), HasLocalizedError {
        override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
            throwable = this,
            label = LABEL.toCaString(),
            description = "Something went wrong".toCaString(),
            fixActionLabel = onFix?.let { FIX.toCaString() },
            fixAction = onFix,
            fixActionErrorMessage = fixErrorMessage?.toCaString(),
            infoActionLabel = onInfo?.let { INFO.toCaString() },
            infoAction = onInfo,
        )
    }

    /**
     * The dialog never removes itself, it only calls back through onDismiss, so the host owns the
     * visibility here — a dialog that is gone afterwards proves the dismiss actually ran.
     */
    private fun show(error: Throwable) {
        composeTestRule.setContent {
            var visible by remember { mutableStateOf(true) }
            PreviewWrapper {
                if (visible) {
                    ErrorDialog(
                        throwable = error,
                        onDismiss = {
                            dismissals++
                            visible = false
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a throwing fix action still dismisses the dialog`() {
        var invocations = 0
        show(
            TestError(
                onFix = {
                    // Counted first: the assertion below has to tell "action ran and threw" apart
                    // from "action was never dispatched".
                    invocations++
                    throw IllegalStateException("fix action exploded")
                },
            )
        )

        composeTestRule.onNodeWithText(FIX).performClick()
        composeTestRule.waitForIdle()

        invocations shouldBe 1
        // Exactly one dismissal: the throw must neither swallow it nor double it
        dismissals shouldBe 1
        composeTestRule.onAllNodesWithText(FIX).assertCountEquals(0)
    }

    @Test
    fun `a throwing info action leaves the dialog open`() {
        var invocations = 0
        show(
            TestError(
                onInfo = {
                    invocations++
                    throw IllegalStateException("info action exploded")
                },
            )
        )

        composeTestRule.onNodeWithText(INFO).performClick()
        composeTestRule.waitForIdle()

        invocations shouldBe 1
        // The details action deliberately does not dismiss, and the guard must not change that
        dismissals shouldBe 0
        composeTestRule.onAllNodesWithText(INFO).assertCountEquals(1)
    }

    @Test
    fun `a throwing fix action with its own message keeps the dialog open and shows it inline`() {
        // A Toast caps at 2 lines and clipped this kind of message; the dialog body has no cap.
        show(
            TestError(
                onFix = { throw IllegalStateException("fix action exploded") },
                fixErrorMessage = FIX_ERROR,
            )
        )

        composeTestRule.onNodeWithText(FIX).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(FIX_ERROR).assertExists()
        dismissals shouldBe 0
        // Not latched: the way out stays available while the message is shown.
        val dismissLabel = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.general_dismiss_action)
        composeTestRule.onNodeWithText(dismissLabel).performClick()
        composeTestRule.waitForIdle()
        dismissals shouldBe 1
    }

    @Test
    fun `a throwing info action never borrows the fix action's failure message`() {
        // The failure copy belongs to the fix action's dispatch, not to the error: the info button
        // dispatches without one and must keep the plain log-then-stay behaviour.
        show(
            TestError(
                onFix = {},
                fixErrorMessage = FIX_ERROR,
                onInfo = { throw IllegalStateException("info action exploded") },
            )
        )

        composeTestRule.onNodeWithText(INFO).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText(FIX_ERROR).assertCountEquals(0)
        // The details action deliberately does not dismiss, and the message plumbing must not
        // start making it dismiss either.
        dismissals shouldBe 0
        composeTestRule.onAllNodesWithText(INFO).assertCountEquals(1)
    }

    companion object {
        private const val LABEL = "Access denied"
        private const val FIX = "Grant access"
        private const val INFO = "Details"
        private const val FIX_ERROR = "Fixing it did not work"
    }
}
