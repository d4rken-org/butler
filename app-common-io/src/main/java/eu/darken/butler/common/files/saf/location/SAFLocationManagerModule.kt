package eu.darken.butler.common.files.saf.location

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SAFLocationManagerModule {

    @Binds
    @Singleton
    abstract fun locationManager(impl: SAFLocationManagerImpl): SAFLocationManager
}