package eu.darken.butler.common.files.smb

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.smb.KotlinSmbClient
import eu.darken.smb.SmbConnector
import eu.darken.smb.SmbShareLister
import javax.inject.Singleton

interface SmbClientFactory {
    fun create(): SmbConnector

    fun createLister(): SmbShareLister
}

@Module
@InstallIn(SingletonComponent::class)
object SmbClientFactoryModule {
    @Provides
    @Singleton
    fun clientFactory(): SmbClientFactory = object : SmbClientFactory {
        override fun create(): SmbConnector = KotlinSmbClient()

        override fun createLister(): SmbShareLister = KotlinSmbClient()
    }
}
