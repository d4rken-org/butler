package eu.darken.butler.common.serialization

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import javax.inject.Qualifier
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class SerializationCommonModule {

    @Provides
    @Singleton
    @SerializationCommon
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        serializersModule = SerializersModule {
            contextual(InstantSerializer)
            contextual(DurationSerializer)
            contextual(UUIDSerializer)
            contextual(ByteStringSerializer)
            contextual(FileSerializer)
            contextual(UriSerializer)
            contextual(OffsetDateTimeSerializer)
            contextual(RegexSerializer)
            contextual(LocaleSerializer)
        }
    }
}

@Qualifier
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
annotation class SerializationCommon
