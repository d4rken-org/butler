package eu.darken.butler.setup.core.shizuku

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.R
import javax.inject.Singleton

object ShizukuInstallGuide : AdbManagerInstallGuide {
    override val labelRes: Int = R.string.setup_adb_install_manager_shizuku_label
    override val url: String = "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
}

@Module
@InstallIn(SingletonComponent::class)
object AdbManagerInstallGuideModule {

    @Provides
    @Singleton
    fun guide(): AdbManagerInstallGuide = ShizukuInstallGuide
}
