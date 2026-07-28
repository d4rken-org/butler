package eu.darken.butler.upgrade

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.butler.upgrade.core.UpgradeDiagnosticsGplay
import eu.darken.butler.upgrade.core.UpgradeRepoGplay
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
abstract class UpgradeModule {
    @Binds
    @Singleton
    abstract fun control(gplay: UpgradeRepoGplay): UpgradeRepo

    @Binds
    @IntoSet
    abstract fun diagnostics(gplay: UpgradeDiagnosticsGplay): UpgradeDiagnostics

}
