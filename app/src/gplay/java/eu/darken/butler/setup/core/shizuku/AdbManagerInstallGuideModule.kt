package eu.darken.butler.setup.core.shizuku

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.adb.shizuku.AdbBackend

object ShizukuInstallGuide : AdbManagerInstallGuide {
    override val backend: AdbBackend = AdbBackend.SHIZUKU
    override val url: String = "https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api"
}

@Module
@InstallIn(SingletonComponent::class)
object AdbManagerInstallGuideModule {

    @Provides
    fun guide(): AdbManagerInstallGuide = ShizukuInstallGuide
}
