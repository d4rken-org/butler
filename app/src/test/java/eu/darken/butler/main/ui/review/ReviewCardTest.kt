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

    /** The card hides itself first and reports back after the exit animation. */
    private fun settleDismissAnimation() {
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

    // One action per test: either tap hides the card, so the second button is gone by then.

    @Test
    fun `the dismiss action reports back`() {
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
        settleDismissAnimation()

        dismissed shouldBe 1
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
        settleDismissAnimation()

        reviewed shouldBe activity
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
