package eu.darken.butler.upgrade.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import eu.darken.butler.R
import eu.darken.butler.common.compose.PreviewWrapper
import org.junit.Test
import org.robolectric.annotation.Config
import testhelpers.ComposeTest

class UpgradeScreenTest : ComposeTest() {

    private fun loadedState(
        wasPreviouslyPro: Boolean = false,
        restoreInProgress: Boolean = false,
        trialAvailable: Boolean = true,
    ) = UpgradeViewModel.State(
        isLoadingPrices = false,
        iapState = UpgradeViewModel.State.Iap(available = true, formattedPrice = "$4.99"),
        subState = UpgradeViewModel.State.Sub(available = true, formattedPrice = "$2.99"),
        trialState = UpgradeViewModel.State.Trial(available = trialAvailable, formattedPrice = "$2.99"),
        wasPreviouslyPro = wasPreviouslyPro,
        restoreInProgress = restoreInProgress,
    )

    private fun setScreen(
        state: UpgradeViewModel.State,
        onRestorePurchase: () -> Unit = {},
    ) = composeTestRule.setContent {
        PreviewWrapper {
            UpgradeScreen(
                state = state,
                onNavigateBack = {},
                onGoIap = {},
                onGoSubscription = {},
                onGoSubscriptionTrial = {},
                onRestorePurchase = onRestorePurchase,
            )
        }
    }

    @Test
    fun `returning buyer sees the restore banner and can trigger restore`() {
        var restoreClicks = 0
        setScreen(
            state = loadedState(wasPreviouslyPro = true),
            onRestorePurchase = { restoreClicks++ },
        )

        composeTestRule.onAllNodesWithTag(UpgradeScreenTestTags.RESTORE_BANNER).assertCountEquals(1)
        composeTestRule.onNodeWithTag(UpgradeScreenTestTags.RESTORE_BANNER_ACTION).performClick()
        composeTestRule.runOnIdle { check(restoreClicks == 1) { "expected 1 restore click, got $restoreClicks" } }
    }

    @Test
    fun `banner is hidden without a prior purchase on this device`() {
        setScreen(state = loadedState(wasPreviouslyPro = false))

        composeTestRule.onAllNodesWithTag(UpgradeScreenTestTags.RESTORE_BANNER).assertCountEquals(0)
    }

    @Test
    fun `both restore affordances are disabled while a restore is running`() {
        setScreen(state = loadedState(wasPreviouslyPro = true, restoreInProgress = true))

        composeTestRule.onNodeWithTag(UpgradeScreenTestTags.RESTORE_BANNER_ACTION).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(UpgradeScreenTestTags.RESTORE_ACTION).assertIsNotEnabled()
        composeTestRule
            .onNodeWithTag(UpgradeScreenTestTags.RESTORE_BANNER_PROGRESS, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `wide layout also shows the banner for returning buyers`() {
        var restoreClicks = 0
        setScreen(
            state = loadedState(wasPreviouslyPro = true),
            onRestorePurchase = { restoreClicks++ },
        )

        composeTestRule.onAllNodesWithTag(UpgradeScreenTestTags.RESTORE_BANNER).assertCountEquals(1)
        composeTestRule.onNodeWithTag(UpgradeScreenTestTags.RESTORE_BANNER_ACTION).performClick()
        composeTestRule.runOnIdle { check(restoreClicks == 1) { "expected 1 restore click, got $restoreClicks" } }
    }

    @Test
    fun `subscription copy promises the trial only when the trial offer exists`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        setScreen(state = loadedState(trialAvailable = true))

        composeTestRule.onNodeWithText(context.getString(R.string.upgrade_screen_how_body)).assertExists()
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.upgrade_screen_how_body_no_trial))
            .assertCountEquals(0)
    }

    @Test
    fun `subscription copy does not promise a trial when play withholds the trial offer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        setScreen(state = loadedState(trialAvailable = false))

        composeTestRule.onNodeWithText(context.getString(R.string.upgrade_screen_how_body_no_trial)).assertExists()
        composeTestRule
            .onAllNodesWithText(context.getString(R.string.upgrade_screen_how_body))
            .assertCountEquals(0)
    }
}
