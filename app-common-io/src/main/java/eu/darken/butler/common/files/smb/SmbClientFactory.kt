package eu.darken.butler.common.files.smb

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.smb.KotlinSmbClient
import eu.darken.smb.SmbConnector
import javax.inject.Singleton

fun interface SmbClientFactory {
    fun create(): SmbConnector
}

@Module
@InstallIn(SingletonComponent::class)
object SmbClientFactoryModule {
    @Provides
    @Singleton
    fun clientFactory(): SmbClientFactory = SmbClientFactory { KotlinSmbClient() }
}
