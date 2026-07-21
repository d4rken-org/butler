package eu.darken.butler.searcher.core.engine.backend

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
object SearchBackendModule {

    @Provides
    @IntoSet
    fun fileSystemBackend(backend: FileSystemSearchBackend): SearchBackend = backend

    @Provides
    @IntoSet
    fun mediaStoreBackend(backend: MediaStoreSearchBackend): SearchBackend = backend

    // Future backends (network sources) register here the same way.
}
