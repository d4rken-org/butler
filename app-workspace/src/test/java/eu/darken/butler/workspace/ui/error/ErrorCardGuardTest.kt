package eu.darken.butler.workspace.ui.error

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.common.error.HasLocalizedError
import eu.darken.butler.common.error.LocalizedError
import eu.darken.butler.common.error.LocalizedErrorContext
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

/**
 * [ErrorCard] dispatches the same arbitrary fix actions the shared dialog does, but it is inline and
 * has no exit of its own: a throwing action must leave the card standing instead of taking the pane
 * down with it.
 */
class ErrorCardGuardTest : ComposeTest() {

    private class TestError(
        private val onFix: () -> Unit,
    ) : RuntimeException("boom"), HasLocalizedError {
        override fun getLocalizedError(context: LocalizedErrorContext) = LocalizedError(
            throwable = this,
            label = LABEL.toCaString(),
            description = BODY.toCaString(),
            fixActionLabel = FIX.toCaString(),
            fixAction = onFix,
        )
    }

    @Test
    fun `a throwing fix action leaves the card standing`() {
        var invocations = 0

        composeTestRule.setContent {
            PreviewWrapper {
                ErrorCard(
                    title = TITLE,
                    error = TestError(
                        onFix = {
                            // Counted first: the assertion below has to tell "action ran and threw"
                            // apart from "action was never dispatched".
                            invocations++
                            throw IllegalStateException("fix action exploded")
                        },
                    ),
                    onShareError = {},
                )
            }
        }

        composeTestRule.onNodeWithText(FIX).performClick()
        composeTestRule.waitForIdle()

        invocations shouldBe 1
        composeTestRule.onNodeWithText(TITLE).assertExists()
        composeTestRule.onNodeWithText(BODY).assertExists()
        composeTestRule.onNodeWithText(FIX).assertExists()
    }

    companion object {
        private const val TITLE = "Navigation failed"
        private const val LABEL = "Access denied"
        private const val BODY = "Something went wrong"
        private const val FIX = "Grant access"
    }
}
