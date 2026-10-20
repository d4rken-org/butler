package eu.darken.butler.workspace.ui.hosted

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.navigation.NavigationEntry
import eu.darken.butler.workspace.contracts.picker.DestinationHostedPicker
import javax.inject.Inject

class HostedPickerNavigation @Inject constructor() : NavigationEntry {
    override fun EntryProviderScope<NavKey>.setup() {
        entry<DestinationHostedPicker> { destination ->
            HostedPickerScreenHost(token = destination.token)
        }
    }

    @Suppress("unused")
    @Module @InstallIn(SingletonComponent::class)
    abstract class Mod {
        @Binds @IntoSet abstract fun bind(entry: HostedPickerNavigation): NavigationEntry
    }
}
