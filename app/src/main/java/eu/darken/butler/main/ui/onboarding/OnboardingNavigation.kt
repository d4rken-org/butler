package eu.darken.butler.main.ui.onboarding

import androidx.navigation3.runtime.EntryProviderBuilder
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.navigation.NavigationEntry
import eu.darken.butler.main.ui.DestinationOnboarding
import javax.inject.Inject

class OnboardingNavigation @Inject constructor() : NavigationEntry {
    override fun EntryProviderBuilder<NavKey>.setup() {
        entry<DestinationOnboarding> {
            OnboardingScreenHost()
        }
    }

    @Module @InstallIn(SingletonComponent::class)
    abstract class Mod {
        @Binds @IntoSet abstract fun bind(entry: OnboardingNavigation): NavigationEntry
    }
}
