package eu.darken.butler.upgrade.ui

sealed class UpgradeEvents {
    data object RestoreFailed : UpgradeEvents()
    data object RestoreSucceeded : UpgradeEvents()

    // Sub->IAP switch gate outcomes: the user still has an auto-renewing (or pending) subscription, or
    // we couldn't verify the subscription state and refused to launch (fail-closed).
    data object SubscriptionStillRenewing : UpgradeEvents()
    data object SubscriptionCheckFailed : UpgradeEvents()
}
