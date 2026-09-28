package eu.darken.butler.setup.core.shizuku

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.adb.shizuku.AdbBackend

object PorterInstallGuide : AdbManagerInstallGuide {
    override val backend: AdbBackend = AdbBackend.PORTER
    override val url: String = "https://porter.darken.eu/setup"
}

@Module
@InstallIn(SingletonComponent::class)
object AdbManagerInstallGuideModule {

    @Provides
    fun guide(): AdbManagerInstallGuide = PorterInstallGuide
}
