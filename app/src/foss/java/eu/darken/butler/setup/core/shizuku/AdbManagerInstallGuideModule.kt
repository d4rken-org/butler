package eu.darken.butler.setup.core.shizuku

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.R
import javax.inject.Singleton

object PorterInstallGuide : AdbManagerInstallGuide {
    override val labelRes: Int = R.string.setup_adb_install_manager_porter_label
    override val url: String = "https://porter.darken.eu/setup"
}

@Module
@InstallIn(SingletonComponent::class)
object AdbManagerInstallGuideModule {

    @Provides
    @Singleton
    fun guide(): AdbManagerInstallGuide = PorterInstallGuide
}
