package eu.darken.butler.common.serialization

import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class SerializationAppModule {

    @Provides
    @Singleton
    fun moshi(
        @SerializationIO moshiIO: Moshi = SerializationIOModule().moshi()
    ): Moshi = moshiIO.newBuilder().apply {

    }.build()
}