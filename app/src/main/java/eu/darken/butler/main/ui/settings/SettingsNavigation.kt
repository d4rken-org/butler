package eu.darken.butler.main.ui.settings

import androidx.navigation3.runtime.EntryProviderBuilder
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.navigation.NavigationEntry
import eu.darken.butler.main.ui.AppNav
import eu.darken.butler.main.ui.settings.general.GeneralSettingsScreenHost
import eu.darken.butler.main.ui.settings.support.SupportScreenHost
import javax.inject.Inject

class SettingsNavigation @Inject constructor() : NavigationEntry {
    override fun EntryProviderBuilder<NavKey>.setup() {
        entry<AppNav.Settings> {
            OnboardingScreenHost()
        }
        entry<AppNav.Settings.General> {
            GeneralSettingsScreenHost()
        }
        entry<AppNav.Settings.Support> {
            SupportScreenHost()
        }
    }

    @Module @InstallIn(SingletonComponent::class)
    abstract class Mod {
        @Binds @IntoSet abstract fun bind(entry: SettingsNavigation): NavigationEntry
    }
}