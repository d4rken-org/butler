package eu.darken.butler.common.upgrade.ui

import androidx.navigation3.runtime.EntryProviderBuilder
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.navigation.NavigationEntry
import eu.darken.butler.main.ui.DestinationUpgrade
import javax.inject.Inject

class UpgradeNavigation @Inject constructor() : NavigationEntry {
    override fun EntryProviderBuilder<NavKey>.setup() {
        entry<DestinationUpgrade> {
            UpgradeScreenHost()
        }
    }

    @Suppress("unused")
    @Module @InstallIn(SingletonComponent::class)
    abstract class Mod {
        @Binds @IntoSet abstract fun bind(entry: UpgradeNavigation): NavigationEntry
    }
}
