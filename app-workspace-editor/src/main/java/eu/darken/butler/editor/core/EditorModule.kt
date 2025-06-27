package eu.darken.butler.editor.core

import dagger.assisted.AssistedFactory
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch

@Module
@InstallIn(SingletonComponent::class)
interface EditorModule {

    @AssistedFactory
    interface ChunkRepositoryFactory {
        fun create(dataSource: EditorDataSource): ChunkRepository
    }

    @AssistedFactory
    interface ChunkManagerFactory {
        fun create(chunkRepository: ChunkRepository): ChunkManager
    }

    @AssistedFactory
    interface ChunkedTextBufferFactory {
        fun create(chunkManager: ChunkManager, chunkRepository: ChunkRepository): ChunkedTextBuffer
    }

    @AssistedFactory
    interface FileDataSourceFactory {
        fun create(filePath: APath, gatewaySwitch: GatewaySwitch): FileDataSource
    }

    @AssistedFactory
    interface InMemoryDataSourceFactory {
        fun create(initialContent: String): InMemoryDataSource
    }
}