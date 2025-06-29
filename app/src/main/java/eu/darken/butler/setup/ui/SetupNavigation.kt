package eu.darken.butler.setup.ui

import androidx.navigation3.runtime.EntryProviderBuilder
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.navigation.DestinationSetup
import eu.darken.butler.common.navigation.NavigationEntry
import eu.darken.butler.setup.core.SetupModule
import javax.inject.Inject

class SetupNavigation @Inject constructor() : NavigationEntry {
    override fun EntryProviderBuilder<NavKey>.setup() {
        entry<DestinationSetup> { destination ->
            val options = SetupScreenOptions(
                typeFilter = destination.typeFilter?.map { SetupModule.Type.valueOf(it) }?.toSet(),
                isOnboarding = destination.isOnboarding,
                showCompleted = destination.showCompleted
            )
            SetupScreenHost(options = options)
        }
    }

    @Suppress("unused")
    @Module @InstallIn(SingletonComponent::class)
    abstract class Mod {
        @Binds @IntoSet abstract fun bind(entry: SetupNavigation): NavigationEntry
    }
}