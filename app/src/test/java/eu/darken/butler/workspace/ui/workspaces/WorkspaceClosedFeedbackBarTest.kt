package eu.darken.butler.workspace.ui.workspaces

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.workspace.core.undo.ClosedWorkspaceFeedback
import eu.darken.butler.workspace.ui.floatingbar.BarPosition
import eu.darken.butler.workspace.ui.floatingbar.FloatingBarStack
import io.kotest.matchers.shouldBe
import org.junit.Test
import testhelpers.ComposeTest

class WorkspaceClosedFeedbackBarTest : ComposeTest() {

    private fun feedbackOf(closeToken: Long) = ClosedWorkspaceFeedback(
        closeToken = closeToken,
        customTitle = null,
        automaticTitle = "Downloads".toCaString(),
    )

    @Test
    fun `swipe left dismisses and does not undo`() {
        var dismissCount = 0
        var undoCount = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceClosedFeedbackBar(
                    modifier = Modifier.testTag("bar"),
                    feedback = feedbackOf(1L),
                    onUndo = { undoCount++ },
                    onDismiss = { dismissCount++ },
                )
            }
        }

        composeTestRule.onNodeWithTag("bar").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        dismissCount shouldBe 1
        undoCount shouldBe 0
    }

    @Test
    fun `swipe right dismisses and does not undo`() {
        var dismissCount = 0
        var undoCount = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceClosedFeedbackBar(
                    modifier = Modifier.testTag("bar"),
                    feedback = feedbackOf(1L),
                    onUndo = { undoCount++ },
                    onDismiss = { dismissCount++ },
                )
            }
        }

        composeTestRule.onNodeWithTag("bar").performTouchInput { swipeRight() }
        composeTestRule.waitForIdle()

        dismissCount shouldBe 1
        undoCount shouldBe 0
    }

    @Test
    fun `tapping undo calls onUndo and does not dismiss`() {
        var dismissCount = 0
        var undoCount = 0

        composeTestRule.setContent {
            PreviewWrapper {
                WorkspaceClosedFeedbackBar(
                    modifier = Modifier.testTag("bar"),
                    feedback = feedbackOf(1L),
                    onUndo = { undoCount++ },
                    onDismiss = { dismissCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitForIdle()

        undoCount shouldBe 1
        dismissCount shouldBe 0
    }

    /**
     * Drives [WorkspaceClosedUndoBar] rather than the bar itself: the keying that makes a
     * superseding entry swipeable lives there, so a test that declared its own key would pass
     * against a production composable that had lost it.
     */
    @Test
    fun `a superseding entry is swipeable`() {
        val dismissedTokens = mutableListOf<Long>()
        var feedback by mutableStateOf(feedbackOf(1L))

        composeTestRule.setContent {
            PreviewWrapper {
                val entry = feedback
                FloatingBarStack(position = BarPosition.BOTTOM) {
                    WorkspaceClosedUndoBar(
                        feedback = entry,
                        onUndo = {},
                        onDismiss = { dismissedTokens += entry.closeToken },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText(MESSAGE).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        dismissedTokens shouldBe listOf(1L)

        // The stash's flow conflates, so a newer entry can arrive without an intervening null
        composeTestRule.runOnIdle { feedback = feedbackOf(2L) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(MESSAGE).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        dismissedTokens shouldBe listOf(1L, 2L)
    }

    @Test
    fun `the bar is capped in a wide window and starts at the stack's start edge`() {
        composeTestRule.setContent {
            PreviewWrapper {
                // Required: the test window is narrower than the cap, and a plain width would be
                // clamped to it.
                Box(modifier = Modifier.requiredWidth(1000.dp).testTag("window")) {
                    FloatingBarStack(position = BarPosition.BOTTOM, horizontalPadding = 0.dp) {
                        WorkspaceClosedUndoBar(
                            feedback = feedbackOf(1L),
                            onUndo = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        val window = composeTestRule.onNodeWithTag("window").getUnclippedBoundsInRoot()
        val bounds = composeTestRule.onNodeWithText(MESSAGE).onParent().getUnclippedBoundsInRoot()
        bounds.left shouldBe window.left
        bounds.width shouldBe 600.dp
    }

    companion object {
        private const val MESSAGE = "Closed \"Downloads\""
    }
}
