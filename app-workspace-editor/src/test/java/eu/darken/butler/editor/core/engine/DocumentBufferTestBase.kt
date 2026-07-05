package eu.darken.butler.editor.core.engine

import eu.darken.butler.editor.core.engine.text.BlockIndexBuilder
import eu.darken.butler.editor.core.sources.InMemoryDataSource
import eu.darken.butler.workspace.core.Workspace
import testhelpers.BaseTest

/**
 * Base class for DocumentBuffer tests with shared helper methods.
 */
abstract class DocumentBufferTestBase : BaseTest() {

    protected val workspaceId = Workspace.Id()

    /**
     * Creates a DocumentBuffer with in-memory content for testing.
     *
     * @param content The text content to load into the buffer
     * @param blockSize Original-document block size in bytes (small values force multi-block docs)
     */
    protected suspend fun createBuffer(
        content: String,
        blockSize: Int = BlockIndexBuilder.DEFAULT_BLOCK_SIZE,
    ): DocumentBuffer {
        val dataSource = InMemoryDataSource(workspaceId, content)
        dataSource.open()

        val buffer = DocumentBuffer(
            workspaceId = workspaceId,
            dataSource = dataSource,
            maxUndoStackSize = 100,
            maxUndoMemoryBytes = 10_485_760,
            blockSize = blockSize,
            assertions = true,
        )
        buffer.initialize().getOrThrow()
        return buffer
    }
}
