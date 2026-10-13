package eu.darken.butler.common.files.webdav

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.webdav.OkHttpWebDavConnector
import eu.darken.webdav.WebDavConnector
import javax.inject.Singleton

fun interface WebDavConnectorFactory {
    fun create(): WebDavConnector
}

@Module
@InstallIn(SingletonComponent::class)
object WebDavConnectorFactoryModule {
    @Provides
    @Singleton
    fun connectorFactory(): WebDavConnectorFactory = WebDavConnectorFactory { OkHttpWebDavConnector() }
}
