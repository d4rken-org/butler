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
        // Sponsor pitch route; auto-closes once the user becomes a supporter.
        entry<DestinationUpgrade> {
            UpgradeScreenHost(manage = false)
        }
        // Supporter-status route (same host). Route class kept for restored back-stack compatibility.
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
