package eu.darken.butler.upgrade.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import eu.darken.butler.common.compose.PreviewWrapper
import org.junit.Test
import testhelpers.ComposeTest

class UpgradeScreenTest : ComposeTest() {

    private fun acquisition(
        wasPreviouslyPro: Boolean = false,
        restoreInProgress: Boolean = false,
        trial: Boolean = false,
    ) = UpgradeUiState.Loaded(
        manage = false,
        settled = true,
        ownership = UpgradeUiState.Ownership(),
        grace = null,
        subscriptionAction = if (trial) UpgradeUiState.SubscriptionAction.TRIAL else UpgradeUiState.SubscriptionAction.STANDARD,
        subscriptionPrice = "$2.99",
        trialPrice = "$2.99",
        iapPrice = "$4.99",
        wasPreviouslyPro = wasPreviouslyPro,
        restoreInProgress = restoreInProgress,
        verificationInProgress = false,
    )

    private fun ownedSub(renewing: Boolean) = UpgradeUiState.Loaded(
        manage = true,
        settled = true,
        ownership = UpgradeUiState.Ownership(
            hasIap = false,
            subscription = UpgradeUiState.SubscriptionOwnership(isAutoRenewing = renewing),
        ),
        grace = null,
        subscriptionAction = UpgradeUiState.SubscriptionAction.UNAVAILABLE,
        subscriptionPrice = null,
        trialPrice = null,
        iapPrice = "$4.99",
        wasPreviouslyPro = false,
        restoreInProgress = false,
        verificationInProgress = false,
    )

    private fun setScreen(
        state: UpgradeUiState,
        onSwitchOrIap: () -> Unit = {},
        onRestorePurchase: () -> Unit = {},
    ) = composeTestRule.setContent {
        PreviewWrapper {
            UpgradeScreen(
                state = state,
                snackbarHostState = SnackbarHostState(),
                onNavigateBack = {},
                onGoIap = onSwitchOrIap,
                onGoSubscription = {},
                onGoSubscriptionTrial = {},
                onRestorePurchase = onRestorePurchase,
                onManageSubscription = {},
                onRetry = {},
            )
        }
    }

    @Test
    fun `acquisition shows both offers and a restore section`() {
        setScreen(state = acquisition())
        composeTestRule.onAllNodesWithTag(UpgradeScreenTestTags.SUB_ACTION).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTestTags.IAP_ACTION).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTestTags.RESTORE_SECTION).assertCountEquals(1)
    }

    @Test
    fun `restore action is disabled while a restore is running`() {
        setScreen(state = acquisition(restoreInProgress = true))
        composeTestRule.onNodeWithTag(UpgradeScreenTestTags.RESTORE_ACTION).assertIsNotEnabled()
    }

    @Test
    fun `switch offer is locked while the subscription still renews`() {
        setScreen(state = ownedSub(renewing = true))
        composeTestRule.onNodeWithTag(UpgradeScreenTestTags.SWITCH_ACTION).assertIsNotEnabled()
    }

    @Test
    fun `switch offer unlocks once the subscription stops renewing`() {
        var clicks = 0
        setScreen(state = ownedSub(renewing = false), onSwitchOrIap = { clicks++ })
        composeTestRule.onNodeWithTag(UpgradeScreenTestTags.SWITCH_ACTION).assertIsEnabled()
        composeTestRule.onNodeWithTag(UpgradeScreenTestTags.SWITCH_ACTION).performClick()
        composeTestRule.runOnIdle { check(clicks == 1) { "expected 1 switch click, got $clicks" } }
    }
}
