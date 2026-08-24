package eu.darken.butler.common.files.smb.location

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SmbLocationManagerModule {

    @Binds
    @Singleton
    abstract fun locationManager(impl: SmbLocationManagerImpl): SmbLocationManager
}
