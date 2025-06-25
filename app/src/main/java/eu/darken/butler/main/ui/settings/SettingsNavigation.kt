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
import eu.darken.butler.editor.ui.settings.EditorSettingsScreenHost
import eu.darken.butler.explorer.ui.settings.ExplorerSettingsScreenHost
import eu.darken.butler.main.ui.AppNav
import eu.darken.butler.main.ui.settings.acknowledgements.AcknowledgementsScreenHost
import eu.darken.butler.main.ui.settings.general.GeneralSettingsScreenHost
import eu.darken.butler.main.ui.settings.support.SupportScreenHost
import eu.darken.butler.searcher.ui.settings.SearcherSettingsScreenHost
import eu.darken.butler.workspace.ui.settings.WorkspaceSettingsScreenHost
import javax.inject.Inject

class SettingsNavigation @Inject constructor() : NavigationEntry {
    override fun EntryProviderBuilder<NavKey>.setup() {
        entry<AppNav.Main.Settings> {
            SettingsIndexScreenHost()
        }
        entry<AppNav.Settings.General> {
            GeneralSettingsScreenHost()
        }
        entry<AppNav.Settings.Explorer> {
            ExplorerSettingsScreenHost()
        }
        entry<AppNav.Settings.Search> {
            SearcherSettingsScreenHost()
        }
        entry<AppNav.Settings.Editor> {
            EditorSettingsScreenHost()
        }
        entry<AppNav.Settings.Support> {
            SupportScreenHost()
        }
        entry<AppNav.Settings.Acknowledgements> {
            AcknowledgementsScreenHost()
        }
        entry<AppNav.Settings.Workspace> {
            WorkspaceSettingsScreenHost()
        }
    }

    @Suppress("unused")
    @Module @InstallIn(SingletonComponent::class)
    abstract class Mod {
        @Binds @IntoSet abstract fun bind(entry: SettingsNavigation): NavigationEntry
    }
}
