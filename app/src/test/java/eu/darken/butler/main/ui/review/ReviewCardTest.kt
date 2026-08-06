package eu.darken.butler.main.ui.review

import android.app.Activity
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
        var dismissed = 0

        composeTestRule.setContent {
            PreviewWrapper {
                ReviewCard(activity = null, onDismiss = { dismissed++ }, onReview = {})
            }
        }

        // Play's flow needs a concrete activity; a tap that silently does nothing would read as
        // a broken button.
        composeTestRule.onNodeWithText(reviewAction).assertIsNotEnabled()
        composeTestRule.onNodeWithText(reviewAction).performClick()

        // Nothing was handed to the caller, so the card must still be dismissable.
        composeTestRule.onNodeWithText(dismissAction).assertIsEnabled()
        composeTestRule.onNodeWithText(dismissAction).performClick()
        settleAnimations()

        dismissed shouldBe 1
    }

    // The card only disappears once the host stops asking for it (or, for a dismiss, once the local
    // hide is through), so the tap targets are latched against the harmful orderings below.

    @Test
    fun `a dismissed card ignores a review tap during the hide animation`() {
        var dismissed = 0
        var reviewed = 0

        composeTestRule.setContent {
            PreviewWrapper {
                ReviewCard(
                    activity = mockk<Activity>(relaxed = true),
                    onDismiss = { dismissed++ },
                    onReview = { reviewed++ },
                )
            }
        }

        // Frozen clock: the review button has to be assessed while the card is still composed,
        // i.e. inside the 350ms window between the dismiss tap and the callback.
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithText(dismissAction).performClick()

        // A single frame, so the latch reaches the semantics tree; the 350ms window is still open.
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithText(reviewAction).assertIsNotEnabled()
        composeTestRule.onNodeWithText(reviewAction).performClick()

        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        // A review after a dismiss would re-open what the user just closed.
        reviewed shouldBe 0
        dismissed shouldBe 1
    }

    @Test
    fun `a reviewed card ignores a later dismiss tap`() {
        var dismissed = 0
        var reviewed = 0

        composeTestRule.setContent {
            PreviewWrapper {
                ReviewCard(
                    activity = mockk<Activity>(relaxed = true),
                    onDismiss = { dismissed++ },
                    onReview = { reviewed++ },
                )
            }
        }

        composeTestRule.onNodeWithText(reviewAction).performClick()
        settleAnimations()
        reviewed shouldBe 1

        // A dismiss after a review would overwrite the completed-review bookkeeping with a snooze.
        composeTestRule.onNodeWithText(dismissAction).assertIsNotEnabled()
        composeTestRule.onNodeWithText(dismissAction).performClick()
        settleAnimations()

        reviewed shouldBe 1
        dismissed shouldBe 0
    }

    @Test
    fun `a card recreated during the hide animation is usable again`() {
        var dismissed = 0
        var reviewed = 0
        var instance by mutableStateOf(0)

        composeTestRule.setContent {
            PreviewWrapper {
                key(instance) {
                    ReviewCard(
                        activity = mockk<Activity>(relaxed = true),
                        onDismiss = { dismissed++ },
                        onReview = { reviewed++ },
                    )
                }
            }
        }

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithText(dismissAction).performClick()

        // Recreated before the hide is through: the dismissal never reached the host, so the
        // latches must not outlive the instance either - saveable ones would restore a card that
        // is visible but has both actions dead.
        composeTestRule.runOnUiThread { instance++ }
        composeTestRule.mainClock.autoAdvance = true
        settleAnimations()

        dismissed shouldBe 0
        composeTestRule.onNodeWithText(reviewAction).assertIsEnabled()
        composeTestRule.onNodeWithText(dismissAction).assertIsEnabled()

        // The interrupted dismiss can simply be repeated.
        composeTestRule.onNodeWithText(dismissAction).performClick()
        settleAnimations()

        dismissed shouldBe 1
        reviewed shouldBe 0
    }
}
