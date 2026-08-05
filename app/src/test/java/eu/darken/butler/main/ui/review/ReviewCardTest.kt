package eu.darken.butler.main.ui.review

import android.app.Activity
import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.compose.PreviewWrapper
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Test
import testhelpers.ComposeTest

class ReviewCardTest : ComposeTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val bodyText: String
        get() = context.getString(R.string.review_app_body)

    private val dismissAction: String
        get() = context.getString(R.string.review_app_dismiss_action)

    private val reviewAction: String
        get() = context.getString(R.string.review_app_review_action)

    /** Dismissing hides the card first and reports back after the exit animation. */
    private fun settleAnimations() {
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `the card renders its body and both actions`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ReviewCard(activity = null, onDismiss = {}, onReview = {})
            }
        }

        composeTestRule.onNodeWithText(bodyText).assertExists()
        composeTestRule.onNodeWithText(dismissAction).assertExists()
        composeTestRule.onNodeWithText(reviewAction).assertExists()
    }

    // One action per test: dismissing hides the card, so the other button is gone by then.

    @Test
    fun `the dismiss action reports back and takes the card with it`() {
        var dismissed = 0

        composeTestRule.setContent {
            PreviewWrapper {
                ReviewCard(
                    activity = mockk<Activity>(relaxed = true),
                    onDismiss = { dismissed++ },
                    onReview = {},
                )
            }
        }

        composeTestRule.onNodeWithText(dismissAction).performClick()
        settleAnimations()

        dismissed shouldBe 1
        // Dismissing always persists, so the card is gone for good regardless of the hosting state.
        composeTestRule.onNodeWithText(bodyText).assertDoesNotExist()
    }

    @Test
    fun `the review action reports back with the activity it was given`() {
        val activity = mockk<Activity>(relaxed = true)
        var reviewed: Activity? = null

        composeTestRule.setContent {
            PreviewWrapper {
                ReviewCard(
                    activity = activity,
                    onDismiss = {},
                    onReview = { reviewed = it },
                )
            }
        }

        composeTestRule.onNodeWithText(reviewAction).performClick()
        settleAnimations()

        reviewed shouldBe activity
    }

    @Test
    fun `the review action stays tappable while the card is still asked for`() {
        var reviewed = 0

        composeTestRule.setContent {
            PreviewWrapper {
                ReviewCard(
                    activity = mockk<Activity>(relaxed = true),
                    onDismiss = {},
                    onReview = { reviewed++ },
                )
            }
        }

        composeTestRule.onNodeWithText(reviewAction).performClick()
        settleAnimations()

        // A transient failure of the review flow persists nothing, so the host keeps the card up.
        // Hiding locally would strand it invisible-but-mounted and burn the retry.
        composeTestRule.onNodeWithText(bodyText).assertExists()

        composeTestRule.onNodeWithText(reviewAction).performClick()
        reviewed shouldBe 2
    }

    @Test
    fun `without an activity the review action is disabled`() {
        composeTestRule.setContent {
            PreviewWrapper {
                ReviewCard(activity = null, onDismiss = {}, onReview = {})
            }
        }

        // Play's flow needs a concrete activity; a tap that silently does nothing would read as
        // a broken button.
        composeTestRule.onNodeWithText(reviewAction).assertIsNotEnabled()
        composeTestRule.onNodeWithText(dismissAction).assertIsEnabled()
    }
}
