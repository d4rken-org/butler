package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import testhelpers.BaseTest

/**
 * Base class for ChunkedTextBuffer tests with shared helper methods.
 */
abstract class ChunkedTextBufferTestBase : BaseTest() {

    protected val workspaceId = Workspace.Id()

    /**
     * Creates a ChunkedTextBuffer with in-memory content for testing.
     * This avoids file I/O and makes tests faster and more isolated.
     *
     * @param content The text content to load into the buffer
     * @param chunkSize The size of each chunk in bytes (default: 64KB)
     */
    protected suspend fun createBuffer(
        content: String,
        chunkSize: Long = ChunkManager.DEFAULT_CHUNK_SIZE
    ): ChunkedTextBuffer {
        val dataSource = InMemoryDataSource(workspaceId, content)
        dataSource.open()

        val repository = ChunkRepository(workspaceId, dataSource, chunkSize)
        val manager = ChunkManager(workspaceId, repository, chunkSize)
        val buffer = ChunkedTextBuffer(workspaceId, manager, repository)

        buffer.initialize().getOrThrow()
        return buffer
    }
}
