package eu.darken.butler.workspace.ui.workspaces

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.main.core.motd.MotdApi
import eu.darken.butler.main.core.motd.MotdState
import eu.darken.butler.workspace.core.WorkspaceRemote
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.TestApplication
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * The ViewModel decides *whether* to ask; this covers that the screen actually renders the card in
 * its overlay slot and hands the review action a usable activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WorkspaceScreenReviewCardTest : BaseTest() {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val reviewBody: String
        get() = context.getString(R.string.review_app_body)

    private val reviewAction: String
        get() = context.getString(R.string.review_app_review_action)

    private val motdMessage = "Something the user has to read first"

    private fun motd() = MotdState(
        motd = MotdApi.Motd(
            id = Uuid.random(),
            message = motdMessage,
            primaryLink = null,
            minimumVersion = null,
            maximumVersion = null,
        ),
        locale = Locale.ENGLISH,
    )

    private fun state(
        showReviewCard: Boolean,
        motd: MotdState? = null,
    ) = WorkspacesViewModel.State(
        state = WorkspaceRemote.State(),
        focusedWorkspace = null,
        selectedWorkspaces = emptyMap(),
        isUpgraded = true,
        motd = motd,
        showReviewCard = showReviewCard,
    )

    @Composable
    private fun Screen(
        state: WorkspacesViewModel.State,
        reviewActivity: Activity? = null,
        onReviewNow: (Activity) -> Unit = {},
    ) {
        PreviewWrapper {
            WorkspaceScreen(
                state = state,
                managerDialogStates = emptyMap(),
                reviewActivity = reviewActivity,
                onScreenAction = {},
                onReviewNow = onReviewNow,
            )
        }
    }

    @Test
    fun `the review card renders when the state asks for it`() {
        composeRule.setContent { Screen(state(showReviewCard = true)) }

        composeRule.onNodeWithText(reviewBody).assertExists()
    }

    @Test
    fun `a MOTD takes the overlay slot instead`() {
        // What the ViewModel's quiet gate produces while a MOTD is up.
        composeRule.setContent { Screen(state(showReviewCard = false, motd = motd())) }

        composeRule.onNodeWithText(motdMessage).assertExists()
        composeRule.onNodeWithText(reviewBody).assertDoesNotExist()
    }

    @Test
    fun `the review action is live and reports the hosting activity`() {
        var reviewed: Activity? = null
        val hostActivity = composeRule.activity

        composeRule.setContent {
            Screen(
                state = state(showReviewCard = true),
                reviewActivity = hostActivity,
                onReviewNow = { reviewed = it },
            )
        }

        composeRule.onNodeWithText(reviewAction).assertIsEnabled()
        composeRule.onNodeWithText(reviewAction).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        // Play's flow launches against a concrete activity; anything but the hosting one is wrong.
        reviewed shouldBe hostActivity
    }
}
