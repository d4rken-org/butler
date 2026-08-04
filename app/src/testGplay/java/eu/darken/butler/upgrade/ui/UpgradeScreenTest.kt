package eu.darken.butler.upgrade.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.ProductDetails
import eu.darken.butler.R
import eu.darken.butler.common.R as CommonR
import eu.darken.butler.common.compose.PreviewWrapper
import eu.darken.butler.upgrade.core.OurSku
import eu.darken.butler.upgrade.core.billing.SkuDetails
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import testhelpers.ComposeTest

class UpgradeScreenTest : ComposeTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun appNameWithPostfixedHeroBody(bodyRes: Int): String = context.getString(
        bodyRes,
        "${context.getString(CommonR.string.app_name)} ${context.getString(R.string.app_name_upgrade_postfix)}",
    )

    private val brand: String
        get() = "${context.getString(CommonR.string.app_name)} ${context.getString(R.string.app_name_upgrade_postfix)}"

    private val acquisitionTitle: String
        get() = context.getString(R.string.upgrade_screen_title_template, brand)

    private fun acquisitionState() = UpgradeUiState.Loaded(
        subscriptionAction = SubscriptionAction.STANDARD,
        subscriptionEnabled = true,
        subscriptionPrice = "$12.99",
        iapEnabled = true,
        iapPrice = "$24.99",
    )

    @Test
    fun `the acquisition title wraps the composed brand in the pitch sentence`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(uiState = acquisitionState())
        }

        // The whole sentence, not just the brand: the pattern is what carries the translation.
        composeTestRule.onAllNodesWithText(acquisitionTitle).assertCountEquals(1)
    }

    @Test
    fun `the acquisition title colors exactly the upgrade postfix`() {
        var capturedPrimary = Color.Unspecified
        var capturedTertiary = Color.Unspecified
        composeTestRule.setContent {
            PreviewWrapper {
                // Theme roles, captured from the composition under test rather than hardcoded.
                capturedPrimary = MaterialTheme.colorScheme.primary
                capturedTertiary = MaterialTheme.colorScheme.tertiary
                UpgradeScreen(uiState = acquisitionState())
            }
        }

        val rendered = composeTestRule.onNodeWithText(acquisitionTitle)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()

        rendered.text shouldBe acquisitionTitle
        // Two spans, always: the app name in the brand color, the postfix highlighted on top.
        rendered.spanStyles.size shouldBe 2

        val base = rendered.spanStyles[0]
        base.item.color shouldBe capturedPrimary
        rendered.text.substring(base.start, base.end) shouldBe "${context.getString(CommonR.string.app_name)} "

        val postfix = context.getString(R.string.app_name_upgrade_postfix)
        val highlight = rendered.spanStyles[1]
        highlight.item.color shouldBe capturedTertiary
        rendered.text.substring(highlight.start, highlight.end) shouldBe postfix
        // Pins the range rather than just its content: only one candidate position exists.
        rendered.text.indexOf(postfix) shouldBe highlight.start
        rendered.text.lastIndexOf(postfix) shouldBe highlight.start
    }

    @Test
    fun `the hero card opens the acquisition screen`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(uiState = acquisitionState())
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.HERO).assertCountEquals(1)
    }

    @Test
    fun `the hero card is present while the offers are still loading`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(uiState = UpgradeUiState.Loading)
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.HERO).assertCountEquals(1)
    }

    @Test
    fun `owners get no hero card`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(uiState = ownedState(Ownership(hasIap = true)))
        }

        // Owners have their own congrats hero; grace users keep the standalone header, because the
        // preamble the hero pairs the mascot with is sales copy they must not see.
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.HERO).assertCountEquals(0)
    }

    @Test
    fun `the quiet grace stage keeps the standalone header instead of a hero`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(uiState = graceState(showDiagnostics = false))
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.HERO).assertCountEquals(0)
    }

    @Test
    fun `the aged grace stage keeps the standalone header instead of a hero`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(uiState = graceState(showDiagnostics = true))
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.HERO).assertCountEquals(0)
    }

    @Test
    fun `the benefits render as rows without their bullet markers`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(uiState = acquisitionState())
        }

        // The checkmark icon IS the bullet: a literal "•" left in the text would double it up.
        composeTestRule.onAllNodesWithText("Unlimited tabs and operations").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("• Unlimited tabs and operations").assertCountEquals(0)
        // The unmarked trailing line stays plain footnote text, not a checkmarked feature.
        composeTestRule.onAllNodesWithText("and more…").assertCountEquals(1)
    }

    @Test
    fun `loading state shows progress and hides actions`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(uiState = UpgradeUiState.Loading)
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.LOADING).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.ACTIONS).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_preamble)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_benefits_title)).assertCountEquals(1)
    }

    @Test
    fun `loaded state shows trial before iap and hides loading`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Loaded(
                    subscriptionAction = SubscriptionAction.TRIAL,
                    subscriptionEnabled = true,
                    subscriptionPrice = "$12.99",
                    iapEnabled = true,
                    iapPrice = "$24.99",
                ),
            )
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.LOADING).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.ACTIONS).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_subscription_trial_action)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_iap_action)).assertCountEquals(1)
        // Compact rows: the price shares the title line, per-offer captions carry the terms (the
        // standalone "Options" card is gone), and there is no badge.
        composeTestRule.onAllNodesWithText(
            "${context.getString(R.string.upgrade_screen_subscription_offer_title)} · $12.99"
        ).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(
            "${context.getString(R.string.upgrade_screen_iap_offer_title)} · $24.99"
        ).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_offers_title)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_offers_body)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_offers_or)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_subscription_offer_body)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_iap_offer_body)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_restore_purchase_action)).assertCountEquals(1)

        val subscriptionButtonTop = composeTestRule.onNodeWithTag(UpgradeScreenTags.SUBSCRIPTION).getUnclippedBoundsInRoot().top
        val iapButtonTop = composeTestRule.onNodeWithTag(UpgradeScreenTags.IAP).getUnclippedBoundsInRoot().top

        check(subscriptionButtonTop < iapButtonTop) {
            "Expected subscription action to appear above IAP action, but got top=$subscriptionButtonTop and top=$iapButtonTop"
        }

        // Terms read ABOVE their buttons (description-then-action, like the restore section); the
        // header anchors the top and the parity footnote sits below both offers.
        val subscriptionCaptionTop = composeTestRule
            .onNodeWithText(context.getString(R.string.upgrade_screen_subscription_offer_body))
            .getUnclippedBoundsInRoot().top
        val headerTop = composeTestRule
            .onNodeWithText(context.getString(R.string.upgrade_screen_offers_title))
            .getUnclippedBoundsInRoot().top
        val footerTop = composeTestRule
            .onNodeWithText(context.getString(R.string.upgrade_screen_offers_body))
            .getUnclippedBoundsInRoot().top
        check(subscriptionCaptionTop < subscriptionButtonTop) {
            "Expected subscription terms above their button, got terms=$subscriptionCaptionTop button=$subscriptionButtonTop"
        }
        check(headerTop < subscriptionButtonTop) {
            "Expected the offers header above the offers, got header=$headerTop subButton=$subscriptionButtonTop"
        }
        check(footerTop > iapButtonTop) {
            "Expected the parity footnote below both offers, got footer=$footerTop iapButton=$iapButtonTop"
        }
    }

    @Test
    fun `loaded state keeps unavailable actions visible but disabled`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Loaded(
                    subscriptionAction = SubscriptionAction.UNAVAILABLE,
                    subscriptionEnabled = false,
                    subscriptionPrice = null,
                    iapEnabled = false,
                    iapPrice = null,
                ),
            )
        }

        composeTestRule.onNodeWithText(context.getString(R.string.upgrade_screen_subscription_action)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(context.getString(R.string.upgrade_screen_iap_action)).assertIsNotEnabled()
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_restore_purchase_action)).assertCountEquals(1)
        // Without prices the rows fall back to bare titles (exact match proves no dangling "·"),
        // and the unavailable subscription promises no trial.
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_subscription_offer_title)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_iap_offer_title)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_subscription_offer_body)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_subscription_offer_body_no_trial)).assertCountEquals(1)
    }

    @Test
    fun `unavailable state hides loading and purchase actions while keeping static content`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Unavailable(
                    error = RuntimeException("Google Play services unavailable"),
                ),
            )
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.LOADING).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.SUBSCRIPTION).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.IAP).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.RESTORE).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.UNAVAILABLE).assertCountEquals(1)
        // Only the prices are missing — the generic "Google Play is unavailable" title overstated
        // the problem while the rest of the screen (benefits, restore) still works.
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_offers_unavailable_title)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrades_gplay_unavailable_error_title)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_offers_unavailable_message)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_benefits_title)).assertCountEquals(1)
    }

    @Test
    fun `returning buyer sees the restore banner and can trigger restore`() {
        var restoreClicks = 0
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Loaded(
                    subscriptionAction = SubscriptionAction.STANDARD,
                    subscriptionEnabled = true,
                    subscriptionPrice = "$12.99",
                    iapEnabled = true,
                    iapPrice = "$24.99",
                    wasPreviouslyPro = true,
                ),
                onRestore = { restoreClicks++ },
            )
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.RESTORE_BANNER).assertCountEquals(1)
        // The targeted section is the ONLY restore affordance — no second generic one below.
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.RESTORE).assertCountEquals(0)
        composeTestRule.onNodeWithTag(UpgradeScreenTags.RESTORE_BANNER_ACTION).performClick()
        composeTestRule.runOnIdle { check(restoreClicks == 1) { "expected 1 restore click, got $restoreClicks" } }
    }

    @Test
    fun `banner is hidden without a prior purchase on this device`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Loaded(
                    subscriptionAction = SubscriptionAction.STANDARD,
                    subscriptionEnabled = true,
                    subscriptionPrice = "$12.99",
                    iapEnabled = true,
                    iapPrice = "$24.99",
                    wasPreviouslyPro = false,
                ),
            )
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.RESTORE_BANNER).assertCountEquals(0)
    }

    @Test
    fun `the returning-buyer restore is disabled while a restore is running`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Loaded(
                    subscriptionAction = SubscriptionAction.STANDARD,
                    subscriptionEnabled = true,
                    subscriptionPrice = "$12.99",
                    iapEnabled = true,
                    iapPrice = "$24.99",
                    wasPreviouslyPro = true,
                    busy = BusyOp.RESTORE,
                ),
            )
        }

        composeTestRule.onNodeWithTag(UpgradeScreenTags.RESTORE_BANNER_ACTION).assertIsNotEnabled()
    }

    @Test
    fun `plain acquisition gets a described restore section below the offers`() {
        var restoreClicks = 0
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Loaded(
                    subscriptionAction = SubscriptionAction.STANDARD,
                    subscriptionEnabled = true,
                    subscriptionPrice = "$12.99",
                    iapEnabled = true,
                    iapPrice = "$24.99",
                ),
                onRestore = { restoreClicks++ },
            )
        }

        // The offers card holds only offers — restore lives in its own described section. No
        // contact-support affordance here: support is only suggested after a restore came up
        // empty (the failed-restore dialog).
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_restore_body)).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.RESTORE).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_contact_support_action))
            .assertCountEquals(0)
        // STANDARD subscription (no trial offer): the row must not promise a trial.
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_subscription_offer_body))
            .assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_subscription_offer_body_no_trial))
            .assertCountEquals(1)
        composeTestRule.onNodeWithTag(UpgradeScreenTags.RESTORE).performScrollTo().performClick()
        composeTestRule.runOnIdle { check(restoreClicks == 1) { "expected 1 restore click, got $restoreClicks" } }
    }

    @Test
    fun `a running entitlement action pauses the buy actions too`() {
        // toLoadedState computes the enabled flags: any busy op (a restore -- manual or the
        // invisible already-owned recovery -- or a purchase launch) must gate them even when offers
        // are available; a buy tap would just race the other operation into ITEM_ALREADY_OWNED.
        val iapOffer = mockk<ProductDetails.OneTimePurchaseOfferDetails>(relaxed = true)
        val iapDetails = mockk<ProductDetails>(relaxed = true).apply {
            every { oneTimePurchaseOfferDetails } returns iapOffer
        }
        val subOffer = mockk<ProductDetails.SubscriptionOfferDetails>(relaxed = true).apply {
            every { basePlanId } returns OurSku.Sub.PRO_UPGRADE.BASE_OFFER.basePlanId
            every { offerId } returns null
        }
        val subDetails = mockk<ProductDetails>(relaxed = true).apply {
            every { subscriptionOfferDetails } returns listOf(subOffer)
        }

        val loaded = toLoadedState(
            iap = SkuDetails(OurSku.Iap.PRO_UPGRADE, iapDetails),
            sub = SkuDetails(OurSku.Sub.PRO_UPGRADE, subDetails),
            ownership = Ownership(),
            busy = BusyOp.RESTORE,
        )

        check(!loaded.iapEnabled) { "IAP buy must be disabled during a restore" }
        check(!loaded.subscriptionEnabled) { "Subscription buy must be disabled during a restore" }

        // Same offers without a running restore: both buys are available.
        val idle = toLoadedState(
            iap = SkuDetails(OurSku.Iap.PRO_UPGRADE, iapDetails),
            sub = SkuDetails(OurSku.Sub.PRO_UPGRADE, subDetails),
            ownership = Ownership(),
            busy = null,
        )
        check(idle.iapEnabled) { "IAP buy should be enabled when idle" }
        check(idle.subscriptionEnabled) { "Subscription buy should be enabled when idle" }
    }

    @Test
    fun `unavailable state offers a retry that fires the callback`() {
        var retryClicks = 0
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Unavailable(
                    error = RuntimeException("Google Play services unavailable"),
                ),
                onRetry = { retryClicks++ },
            )
        }

        // The unavailable card sits below the fold of the scrollable screen: an offscreen click
        // would silently miss the button.
        composeTestRule.onNodeWithTag(UpgradeScreenTags.RETRY).performScrollTo().performClick()
        composeTestRule.runOnIdle { check(retryClicks == 1) { "expected 1 retry click, got $retryClicks" } }
    }

    @Test
    fun `two retry taps in the same frame only fire one query`() {
        var retryClicks = 0
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Unavailable(
                    error = RuntimeException("Google Play services unavailable"),
                ),
                onRetry = { retryClicks++ },
            )
        }

        // Two sequential performClick calls allow a recomposition in between, so `enabled` alone
        // would absorb the second one and the in-callback guard would go untested. Invoking the
        // node's CACHED OnClick lambda twice is what two taps within one frame look like.
        val onClick = composeTestRule.onNodeWithTag(UpgradeScreenTags.RETRY)
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            .action!!
        composeTestRule.runOnUiThread {
            onClick()
            onClick()
        }

        composeTestRule.runOnIdle { check(retryClicks == 1) { "expected 1 retry click, got $retryClicks" } }
        composeTestRule.onNodeWithTag(UpgradeScreenTags.RETRY).assertIsNotEnabled()
    }

    @Test
    fun `offer copy promises the trial only when Play returned the trial offer`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Loaded(
                    subscriptionAction = SubscriptionAction.TRIAL,
                    subscriptionEnabled = true,
                    subscriptionPrice = "$12.99",
                    iapEnabled = true,
                    iapPrice = "$24.99",
                ),
            )
        }

        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_subscription_offer_body)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_subscription_offer_body_no_trial))
            .assertCountEquals(0)
    }

    private fun ownedState(ownership: Ownership, busy: BusyOp? = null) =
        UpgradeUiState.Loaded(
            subscriptionAction = SubscriptionAction.UNAVAILABLE,
            subscriptionEnabled = false,
            subscriptionPrice = null,
            iapEnabled = !ownership.hasIap,
            iapPrice = "$24.99",
            ownership = ownership,
            busy = busy,
        )

    @Test
    fun `renewing subscription owner sees a locked one-time offer and management`() {
        var iapClicks = 0
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = ownedState(Ownership(subscription = SubscriptionOwnership(isAutoRenewing = true))),
                onIap = { iapClicks++ },
            )
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.MANAGE_SUB).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.SUBSCRIPTION).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_owned_sub_renewing_body)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("${context.getString(CommonR.string.app_name)} ${context.getString(R.string.app_name_upgrade_postfix)}").assertCountEquals(1)
        // The congrats hero names the variant.
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.OWNED_HERO).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(appNameWithPostfixedHeroBody(R.string.upgrade_screen_owned_hero_sub_body))
            .assertCountEquals(1)
        // The switch path is a visible LOCKED offer: present, disabled, with the unlock condition.
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_switch_locked_note)).assertCountEquals(1)
        composeTestRule.onNodeWithTag(UpgradeScreenTags.IAP).assertIsNotEnabled()
        composeTestRule.runOnIdle { check(iapClicks == 0) { "locked offer must not be clickable" } }
        // No acquisition upsell copy anywhere on the ownership screen.
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_preamble)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_benefits_title)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_offers_title)).assertCountEquals(0)
        // Restore stays available in every ownership state: it reconciles entitlements, not upsell.
        // Framed as a status re-check; support is only offered by the failed-restore dialog.
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.RESTORE).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_restore_status_title)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_contact_support_action))
            .assertCountEquals(0)
    }

    @Test
    fun `non-renewing subscription owner can buy the one-time upgrade`() {
        var iapClicks = 0
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = ownedState(Ownership(subscription = SubscriptionOwnership(isAutoRenewing = false))),
                onIap = { iapClicks++ },
            )
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.MANAGE_SUB).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_owned_sub_not_renewing_body)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_switch_purchase_note)).assertCountEquals(1)
        // The offer is unlocked — the locked-state note must be gone.
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_switch_locked_note)).assertCountEquals(0)
        composeTestRule.onNodeWithTag(UpgradeScreenTags.IAP).performScrollTo().performClick()
        composeTestRule.runOnIdle { check(iapClicks == 1) { "expected 1 iap click, got $iapClicks" } }
    }

    @Test
    fun `one-time owner sees owned status without purchase options`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(uiState = ownedState(Ownership(hasIap = true)))
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.OWNED_IAP).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.IAP).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.SUBSCRIPTION).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.MANAGE_SUB).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_owned_iap_body)).assertCountEquals(1)
        // The hero names the permanent purchase as the unlock, never the subscription variant.
        composeTestRule.onAllNodesWithText(appNameWithPostfixedHeroBody(R.string.upgrade_screen_owned_hero_iap_body))
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithText(appNameWithPostfixedHeroBody(R.string.upgrade_screen_owned_hero_sub_body))
            .assertCountEquals(0)
    }

    @Test
    fun `owning both with a renewing subscription shows the renewal warning`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = ownedState(
                    Ownership(hasIap = true, subscription = SubscriptionOwnership(isAutoRenewing = true)),
                ),
            )
        }

        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_owned_both_renewing_warning)).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.MANAGE_SUB).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.IAP).assertCountEquals(0)
    }

    private fun graceState(showDiagnostics: Boolean) = UpgradeUiState.Loaded(
        subscriptionAction = SubscriptionAction.STANDARD,
        subscriptionEnabled = true,
        subscriptionPrice = "$12.99",
        iapEnabled = true,
        iapPrice = "$24.99",
        grace = GraceHint(showDiagnostics = showDiagnostics),
    )

    @Test
    fun `quiet grace stage confirms pro without diagnostics or offers`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(uiState = graceState(showDiagnostics = false))
        }

        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.GRACE).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_grace_title)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_grace_body_short)).assertCountEquals(1)
        // "Confirming…" is backed by motion during the quiet stage; the mascot stays cheerful.
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.GRACE_SPINNER).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.MASCOT_HAPPY).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.MASCOT_GRUMPY).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.GRACE_RESTORE).assertCountEquals(0)
        // The grace card owns restore via its two-stage disclosure — the generic restore section
        // must not undercut the calm quiet stage with its own restore CTA.
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.RESTORE).assertCountEquals(0)
        // Grace users are still Pro: neutral status title, not the acquisition pitch title.
        composeTestRule.onAllNodesWithText("${context.getString(CommonR.string.app_name)} ${context.getString(R.string.app_name_upgrade_postfix)}").assertCountEquals(1)
        // A young episode is treated as a blip: calm status only — no offers, no sales pitch.
        // The offers return with the aged (diagnostics) stage.
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.SUBSCRIPTION).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.IAP).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_preamble)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_benefits_title)).assertCountEquals(0)
    }

    @Test
    fun `grace restore action is disabled while a restore runs`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(uiState = graceState(showDiagnostics = true).copy(busy = BusyOp.RESTORE))
        }

        composeTestRule.onNodeWithTag(UpgradeScreenTags.GRACE_RESTORE).assertIsNotEnabled()
    }

    @Test
    fun `ownership buy button is paused while a restore runs`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = ownedState(Ownership(subscription = SubscriptionOwnership(isAutoRenewing = false)))
                    .copy(busy = BusyOp.RESTORE),
            )
        }

        composeTestRule.onNodeWithTag(UpgradeScreenTags.IAP).assertIsNotEnabled()
    }

    @Test
    fun `aged grace stage shows diagnostics with an inline restore action`() {
        var restoreClicks = 0
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = graceState(showDiagnostics = true),
                onRestore = { restoreClicks++ },
            )
        }

        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_grace_body)).assertCountEquals(1)
        // The aged copy asks the user to act — no spinner contradicting the restore CTA, and the
        // mascot switches to the grumpy "needs your attention" face.
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.GRACE_SPINNER).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.MASCOT_GRUMPY).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.MASCOT_HAPPY).assertCountEquals(0)
        // The aged episode is treated as likely-permanent: the offers come back so an expired
        // subscriber can switch without waiting out the full grace window. Still no sales pitch.
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.SUBSCRIPTION).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.IAP).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_preamble)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_benefits_title)).assertCountEquals(0)
        composeTestRule.onNodeWithTag(UpgradeScreenTags.GRACE_RESTORE).performScrollTo().performClick()
        composeTestRule.runOnIdle { check(restoreClicks == 1) { "expected 1 restore click, got $restoreClicks" } }
    }

    @Test
    fun `ownership buy button is disabled while verification is running`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = ownedState(
                    Ownership(subscription = SubscriptionOwnership(isAutoRenewing = false)),
                    busy = BusyOp.IAP,
                ),
            )
        }

        composeTestRule.onNodeWithTag(UpgradeScreenTags.IAP).assertIsNotEnabled()
    }

    @Test
    fun `a running subscription launch spins on its own button and pauses the rest`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Loaded(
                    subscriptionAction = SubscriptionAction.STANDARD,
                    subscriptionEnabled = true,
                    subscriptionPrice = "$12.99",
                    iapEnabled = true,
                    iapPrice = "$24.99",
                    busy = BusyOp.SUBSCRIPTION,
                ),
            )
        }

        // The spinner belongs to the action the user started -- the IAP button must not claim it,
        // and every other entitlement action is paused while Play is being talked to.
        composeTestRule.onNodeWithTag(UpgradeScreenTags.SUBSCRIPTION).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(UpgradeScreenTags.IAP).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(UpgradeScreenTags.RESTORE).assertIsNotEnabled()
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.SUBSCRIPTION_SPINNER).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(UpgradeScreenTags.IAP_SPINNER).assertCountEquals(0)
    }

    @Test
    fun `offer copy drops the trial promise when only the base offer is available`() {
        composeTestRule.setUpgradeContent {
            UpgradeScreen(
                uiState = UpgradeUiState.Loaded(
                    subscriptionAction = SubscriptionAction.STANDARD,
                    subscriptionEnabled = true,
                    subscriptionPrice = "$12.99",
                    iapEnabled = true,
                    iapPrice = "$24.99",
                ),
            )
        }

        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_subscription_offer_body_no_trial))
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_subscription_offer_body)).assertCountEquals(0)
    }

    @Test
    fun `the inconclusive dialog neither claims a check nor escalates`() {
        // The whole point of splitting this off RestoreFailed: a non-answer must not assert that
        // Play was checked, must not blame the account setup, and must not push toward support.
        composeTestRule.setUpgradeContent { RestoreInconclusiveDialog() }

        composeTestRule.onNodeWithText(
            context.getString(R.string.upgrade_screen_restore_inconclusive_message),
            substring = true,
        ).assertExists()
        composeTestRule.onNodeWithText(
            context.getString(R.string.upgrade_screen_restore_sync_patience_hint),
            substring = true,
        ).assertExists()

        composeTestRule.onAllNodesWithText(
            context.getString(R.string.upgrade_screen_restore_checked_message),
            substring = true,
        ).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(
            context.getString(R.string.upgrade_screen_restore_multiaccount_hint),
            substring = true,
        ).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(context.getString(R.string.upgrade_screen_contact_support_action))
            .assertCountEquals(0)
    }

    @Test
    fun `the inconclusive dialog offers a retry that fires the callback`() {
        var retries = 0
        composeTestRule.setUpgradeContent { RestoreInconclusiveDialog(onRetry = { retries++ }) }

        composeTestRule.onNodeWithText(context.getString(CommonR.string.general_retry_action)).performClick()
        composeTestRule.runOnIdle { retries shouldBe 1 }
    }

    @Test
    fun `the empty-result dialog keeps the escalation path`() {
        // Counterpart to the test above: here Play really did answer, so the multi-account hint and
        // contact-support action remain warranted.
        var supportTaps = 0
        composeTestRule.setUpgradeContent { RestoreFailedDialog(onContactSupport = { supportTaps++ }) }

        composeTestRule.onNodeWithText(
            context.getString(R.string.upgrade_screen_restore_checked_message),
            substring = true,
        ).assertExists()
        composeTestRule.onNodeWithText(
            context.getString(R.string.upgrade_screen_restore_multiaccount_hint),
            substring = true,
        ).assertExists()

        composeTestRule.onNodeWithText(context.getString(R.string.upgrade_screen_contact_support_action)).performClick()
        composeTestRule.runOnIdle { supportTaps shouldBe 1 }
    }
}

private fun ComposeContentTestRule.setUpgradeContent(
    content: @Composable () -> Unit,
) {
    setContent {
        PreviewWrapper {
            content()
        }
    }
}
