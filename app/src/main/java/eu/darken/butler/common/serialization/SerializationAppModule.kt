package eu.darken.butler.common.serialization

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class SerializationAppModule {

    @Provides
    @Singleton
    fun json(
        @SerializationIO jsonIO: Json = SerializationIOModule().json()
    ): Json = jsonIO
}