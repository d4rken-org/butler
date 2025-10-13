package eu.darken.butler.editor.core

import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

class ChunkRepository @AssistedInject constructor(
    @Assisted val dataSource: EditorDataSource
) {
    
    private val tag = logTag("ChunkRepository")
    
    private var chunkSize: Long = ChunkManager.DEFAULT_CHUNK_SIZE
    
    suspend fun getFileInfo(): FileInfo? {
        return dataSource.fileInfo.value
    }
    
    suspend fun loadChunk(chunkId: TextChunk.ChunkId): TextChunk = withContext(Dispatchers.IO) {
        log(tag) { "Loading chunk: $chunkId" }
        
        val startOffset = extractOffsetFromChunkId(chunkId)
        val fileSize = dataSource.getSize()
        val endOffset = minOf(startOffset + chunkSize, fileSize)
        val chunkSizeToRead = endOffset - startOffset
        
        val contentResult = dataSource.readChunk(startOffset, chunkSizeToRead)
        if (contentResult.isFailure) {
            throw contentResult.exceptionOrNull() ?: Exception("Failed to read chunk")
        }
        
        val content = contentResult.getOrThrow()
        val lineCount = content.count { it == '\n' } + if (content.isNotEmpty() && !content.endsWith('\n')) 1 else 0
        
        val chunk = TextChunk(
            id = chunkId,
            startOffset = startOffset,
            endOffset = endOffset,
            content = content,
            lineCount = lineCount,
            isDirty = false,
            isLoaded = true
        )
        
        log(tag) { "Loaded chunk: $chunkId (${chunk.size} bytes, $lineCount lines)" }
        chunk
    }
    
    suspend fun saveChunk(chunk: TextChunk): Unit = withContext(Dispatchers.IO) {
        log(tag) { "Saving chunk: ${chunk.id}" }
        
        val result = dataSource.writeChunk(chunk.startOffset, chunk.content)
        if (result.isFailure) {
            throw result.exceptionOrNull() ?: Exception("Failed to save chunk")
        }
        
        log(tag) { "Saved chunk: ${chunk.id}" }
    }
    
    suspend fun saveFile(): Result<Unit> {
        return dataSource.save()
    }
    
    suspend fun searchInChunk(chunkId: TextChunk.ChunkId, query: String, ignoreCase: Boolean = false): List<SearchResult> {
        try {
            val chunk = loadChunk(chunkId)
            val results = mutableListOf<SearchResult>()
            
            val searchText = if (ignoreCase) chunk.content.lowercase() else chunk.content
            val searchQuery = if (ignoreCase) query.lowercase() else query
            
            var searchIndex = 0
            while (searchIndex < searchText.length) {
                val foundIndex = searchText.indexOf(searchQuery, searchIndex)
                if (foundIndex == -1) break
                
                val absoluteOffset = chunk.startOffset + foundIndex
                val lineNumber = chunk.content.substring(0, foundIndex).count { it == '\n' }
                val lineStart = chunk.content.lastIndexOf('\n', foundIndex - 1) + 1
                val columnNumber = foundIndex - lineStart
                
                results.add(
                    SearchResult(
                        position = TextPosition(absoluteOffset, lineNumber, columnNumber),
                        matchText = chunk.content.substring(foundIndex, foundIndex + query.length),
                        chunkId = chunkId
                    )
                )
                
                searchIndex = foundIndex + 1
            }
            
            return results
            
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to search in chunk: $chunkId - ${e.asLog()}" }
            return emptyList()
        }
    }
    
    fun updateChunkSize(newChunkSize: Long) {
        chunkSize = newChunkSize
        log(tag) { "Updated chunk size to: $chunkSize bytes" }
    }
    
    suspend fun closeFile() {
        log(tag) { "Closing data source" }
        dataSource.close()
    }
    
    private fun extractOffsetFromChunkId(chunkId: TextChunk.ChunkId): Long {
        return chunkId.value.removePrefix("chunk_").toLongOrNull() ?: 0L
    }
    
    companion object {
        private const val TAG = "ChunkRepository"
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