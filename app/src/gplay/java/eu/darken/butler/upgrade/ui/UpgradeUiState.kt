package eu.darken.butler.upgrade.ui

import eu.darken.butler.upgrade.core.UpgradeRepoGplay

sealed interface UpgradeUiState {

    data object Loading : UpgradeUiState

    // SKU prices couldn't be loaded and the user owns nothing / isn't in grace: offer a retry.
    data class Unavailable(val error: Throwable?) : UpgradeUiState

    data class Loaded(
        val manage: Boolean,
        val settled: Boolean,
        val ownership: Ownership,
        val grace: GraceHint?,
        val subscriptionAction: SubscriptionAction,
        val subscriptionPrice: String?,
        val trialPrice: String?,
        val iapPrice: String?,
        val wasPreviouslyPro: Boolean,
        val restoreInProgress: Boolean,
        val verificationInProgress: Boolean,
    ) : UpgradeUiState {

        val ownsAnything: Boolean get() = ownership.ownsAnything

        val inGrace: Boolean get() = grace != null

        // Availability is ownership-baked and stable; kept separate from the busy-gated *Enabled below
        // so a transient restore/verify doesn't flicker a button to a generic "unavailable" fallback.
        val subscriptionAvailable: Boolean
            get() = subscriptionAction != SubscriptionAction.UNAVAILABLE && ownership.subscription == null

        val iapAvailable: Boolean get() = !ownership.hasIap

        private val busy: Boolean get() = restoreInProgress || verificationInProgress

        // Acquisition buys additionally require a settled ownership snapshot, so a fresh install whose
        // ownership query hasn't landed can't double-buy something already owned.
        val subscriptionEnabled: Boolean get() = settled && subscriptionAvailable && !busy
        val iapEnabled: Boolean get() = settled && iapAvailable && !busy

        // The sub->IAP switch offer on the ownership screen: shown when a sub is owned without the IAP;
        // only actionable once the subscription is no longer auto-renewing (so no double billing).
        val showSwitchOffer: Boolean get() = ownership.subscription != null && !ownership.hasIap
        val switchUnlocked: Boolean
            get() = ownership.subscription?.isAutoRenewing == false && !busy
    }

    data class Ownership(
        val hasIap: Boolean = false,
        val subscription: SubscriptionOwnership? = null,
    ) {
        val ownsAnything: Boolean get() = hasIap || subscription != null
    }

    data class SubscriptionOwnership(val isAutoRenewing: Boolean)

    // showDiagnostics: the unconfirmed episode has aged past the 24h boundary -> surface restore/offers.
    data class GraceHint(val showDiagnostics: Boolean)

    enum class SubscriptionAction { TRIAL, STANDARD, UNAVAILABLE }

    companion object {
        fun UpgradeRepoGplay.Info.toOwnership(): Ownership = Ownership(
            hasIap = hasIap,
            subscription = if (hasSubscription) {
                // Conservative: renewing if ANY owned sub still renews (keeps the switch offer locked).
                SubscriptionOwnership(isAutoRenewing = anySubscriptionRenewing)
            } else {
                null
            },
        )
    }
}
