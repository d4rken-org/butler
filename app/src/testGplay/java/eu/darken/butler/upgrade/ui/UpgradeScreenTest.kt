package eu.darken.butler.upgrade.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import eu.darken.butler.common.compose.PreviewWrapper
import org.junit.Test
import testhelpers.ComposeTest

class UpgradeScreenTest : ComposeTest() {

    private fun acquisition(
        restoreInProgress: Boolean = false,
        trial: Boolean = false,
    ) = previewLoaded(
        subscriptionAction = if (trial) UpgradeUiState.SubscriptionAction.TRIAL else UpgradeUiState.SubscriptionAction.STANDARD,
    ).copy(restoreInProgress = restoreInProgress)

    private fun ownedSub(renewing: Boolean) = previewLoaded(
        ownership = UpgradeUiState.Ownership(
            subscription = UpgradeUiState.SubscriptionOwnership(isAutoRenewing = renewing),
        ),
    )

    private fun setScreen(
        state: UpgradeUiState,
        onIap: () -> Unit = {},
        onRestore: () -> Unit = {},
    ) = composeTestRule.setContent {
        PreviewWrapper {
            UpgradeScreen(
                state = state,
                onNavigateUp = {},
                onIap = onIap,
                onSubscription = {},
                onSubscriptionTrial = {},
                onRestore = onRestore,
                onManageSubscription = {},
                onRetry = {},
            )
        }
    }

    @Test
    fun `acquisition shows both offers and a restore section`() {
        setScreen(state = acquisition())
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.SUBSCRIPTION).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.IAP).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.RESTORE).assertCountEquals(1)
    }

    @Test
    fun `restore action is disabled while a restore is running`() {
        setScreen(state = acquisition(restoreInProgress = true))
        composeTestRule.onNodeWithTag(UpgradeScreenTags.RESTORE).assertIsNotEnabled()
    }

    @Test
    fun `switch offer is locked while the subscription still renews`() {
        setScreen(state = ownedSub(renewing = true))
        composeTestRule.onNodeWithTag(UpgradeScreenTags.IAP).assertIsNotEnabled()
    }

    @Test
    fun `switch offer unlocks once the subscription stops renewing`() {
        var clicks = 0
        setScreen(state = ownedSub(renewing = false), onIap = { clicks++ })
        // The switch button sits below the fold in this design's scrolling column — scroll it into
        // view before clicking (assertIsEnabled is semantic-only and needs no visibility).
        composeTestRule.onNodeWithTag(UpgradeScreenTags.IAP).assertIsEnabled()
        composeTestRule.onNodeWithTag(UpgradeScreenTags.IAP).performScrollTo().performClick()
        composeTestRule.runOnIdle { check(clicks == 1) { "expected 1 switch click, got $clicks" } }
    }
}
