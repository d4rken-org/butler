package eu.darken.butler.common.files.webdav.location

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WebDavLocationManagerModule {

    @Binds
    @Singleton
    abstract fun locationManager(impl: WebDavLocationManagerImpl): WebDavLocationManager
}
