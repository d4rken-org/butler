package eu.darken.butler.common.upgrade.ui

sealed class UpgradeEvents {
    data object RestoreFailed : UpgradeEvents()
}
