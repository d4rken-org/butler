package eu.darken.butler.common.error

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    ) : Exception("boom"), HasLocalizedError {
        override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
            throwable = this,
            label = LABEL.toCaString(),
            description = "Something went wrong".toCaString(),
            fixActionLabel = onFix?.let { FIX.toCaString() },
            fixAction = onFix,
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

    companion object {
        private const val LABEL = "Access denied"
        private const val FIX = "Grant access"
        private const val INFO = "Details"
    }
}
