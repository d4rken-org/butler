package eu.darken.butler.upgrade.ui

sealed class UpgradeEvents {
    data object RestoreFailed : UpgradeEvents()
}
