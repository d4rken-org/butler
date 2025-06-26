package eu.darken.butler.common.serialization

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class SerializationIOModule {

    @Provides
    @Singleton
    @SerializationIO
    fun json(
        @SerializationCommon jsonCommon: Json = SerializationCommonModule().json()
    ): Json = Json(jsonCommon) {
        serializersModule = jsonCommon.serializersModule
    }
}

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class SerializationIO
