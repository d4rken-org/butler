package eu.darken.butler.main.ui.home

import androidx.compose.foundation.layout.Column
import androidx.navigation3.runtime.EntryProviderBuilder
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.common.compose.SampleContent
import eu.darken.butler.common.navigation.NavigationController
import eu.darken.butler.common.navigation.NavigationEntry
import eu.darken.butler.main.ui.AppNav
import javax.inject.Inject

class HomeNavigation @Inject constructor(
    private val navCtrl: NavigationController
) : NavigationEntry {
    override fun EntryProviderBuilder<NavKey>.setup() {
        entry<AppNav.Home> {
            Column {

                SampleContent("Go to Onboarding") {
                    navCtrl.goTo(
                        AppNav.Onboarding,
                        popUpTo = AppNav.Onboarding,
                        inclusive = true
                    )
                }
                SampleContent("Go to Settings") {
                    navCtrl.goTo(
                        AppNav.Settings
                    )
                }
            }
        }
    }

    @Module @InstallIn(SingletonComponent::class)
    abstract class Mod {
        @Binds @IntoSet abstract fun bind(entry: HomeNavigation): NavigationEntry
    }
}