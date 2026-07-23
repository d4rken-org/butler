package eu.darken.butler.upgrade.ui

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.navigation.DestinationUpgrade
import eu.darken.butler.common.navigation.NavigationEntry
import eu.darken.butler.main.ui.settings.DestinationUpgradeStatus
import javax.inject.Inject

class UpgradeNavigation @Inject constructor() : NavigationEntry {
    override fun EntryProviderScope<NavKey>.setup() {
        // Acquisition route (the "get Pro" pitch); auto-closes once the user becomes Pro.
        entry<DestinationUpgrade> {
            UpgradeScreenHost(manage = false)
        }
        // Status/manage route (the same flavor host, ownership + grace + sub->IAP switch); stays open.
        // Route class kept from the old flavor-agnostic status screen so restored back stacks resolve.
        entry<DestinationUpgradeStatus> {
            UpgradeScreenHost(manage = true)
        }
    }

    @Suppress("unused")
    @Module @InstallIn(SingletonComponent::class)
    abstract class Mod {
        @Binds @IntoSet abstract fun bind(entry: UpgradeNavigation): NavigationEntry
    }
}
