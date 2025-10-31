package eu.darken.butler.editor.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class ChunkRepository @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted val dataSource: EditorDataSource,
    @Assisted private val chunkSize: Long = ChunkManager.DEFAULT_CHUNK_SIZE
) {

    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "ChunkRepository")

    suspend fun getFileInfo(): FileInfo? {
        return dataSource.fileInfo.value
    }

    suspend fun loadChunk(chunkId: TextChunk.ChunkId, boundary: ChunkBoundary): TextChunk = withContext(Dispatchers.IO) {
        log(tag) { "Loading chunk: $chunkId at ${boundary.startOffset}-${boundary.endOffset}" }

        val content = dataSource.readChunk(boundary.startOffset, boundary.size)

        val lineCount = content.count { it == '\n' } + if (content.isNotEmpty() && !content.endsWith('\n')) 1 else 0

        val chunk = TextChunk(
            id = chunkId,
            startOffset = boundary.startOffset,
            endOffset = boundary.endOffset,
            content = content,
            lineCount = lineCount,
            isDirty = false,
            isLoaded = true
        )

        log(tag) { "Loaded chunk: $chunkId (${chunk.size} bytes, $lineCount lines)" }
        chunk
    }

    /**
     * Saves dirty chunks to the data source.
     * DataSource handles merging and persistence.
     *
     * @param dirtyChunks List of modified chunks to save
     */
    suspend fun saveFile(dirtyChunks: List<TextChunk>) = withContext(Dispatchers.IO) {
        log(tag) { "Saving ${dirtyChunks.size} dirty chunks to data source" }
        dataSource.save(dirtyChunks)
        log(tag) { "Successfully saved chunks" }
    }

    /**
     * Search for a query string within a specific chunk.
     *
     * Note: Line numbers in results are chunk-relative (0-based within the chunk).
     * The caller (ChunkedTextBuffer) is responsible for converting to file-relative line numbers.
     */
    suspend fun searchInChunk(
        chunk: TextChunk,
        query: String,
        ignoreCase: Boolean = false
    ): List<SearchResult> {
        try {
            // Empty query returns no results
            if (query.isEmpty()) {
                return emptyList()
            }

            val results = mutableListOf<SearchResult>()

            val searchText = if (ignoreCase) chunk.content.lowercase() else chunk.content
            val searchQuery = if (ignoreCase) query.lowercase() else query

            var searchIndex = 0
            while (searchIndex < searchText.length) {
                val foundIndex = searchText.indexOf(searchQuery, searchIndex)
                if (foundIndex == -1) break

                val absoluteOffset = chunk.startOffset + foundIndex
                // Line number is chunk-relative (0-based within chunk)
                val lineNumber = chunk.content.substring(0, foundIndex).count { it == '\n' }
                val lineStart = chunk.content.lastIndexOf('\n', foundIndex - 1) + 1
                val columnNumber = foundIndex - lineStart

                results.add(
                    SearchResult(
                        position = TextPosition(absoluteOffset, lineNumber, columnNumber),
                        matchText = chunk.content.substring(foundIndex, foundIndex + query.length),
                        chunkId = chunk.id
                    )
                )

                searchIndex = foundIndex + 1
            }

            return results

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to search in chunk: ${chunk.id} - ${e.asLog()}" }
            return emptyList()
        }
    }

    suspend fun closeFile() {
        log(tag) { "Closing data source" }
        dataSource.close()
    }

    @AssistedFactory
    interface Factory {
        fun create(
            workspaceId: Workspace.Id,
            dataSource: EditorDataSource,
            chunkSize: Long = ChunkManager.DEFAULT_CHUNK_SIZE
        ): ChunkRepository
    }
}

data class FileInfo(
    val path: APath<*>,
    val size: Long,
    val lastModified: Instant,
    val canWrite: Boolean
)

data class SearchResult(
    val position: TextPosition,
    val matchText: String,
    val chunkId: TextChunk.ChunkId
)