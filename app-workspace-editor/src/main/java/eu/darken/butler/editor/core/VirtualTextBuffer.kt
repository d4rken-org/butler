package eu.darken.butler.editor.core

import kotlinx.coroutines.flow.StateFlow

interface VirtualTextBuffer {
    
    val fileInfo: StateFlow<FileInfo?>
    val totalLines: StateFlow<Int>
    val totalLength: StateFlow<Long>
    val isModified: StateFlow<Boolean>
    
    suspend fun initialize(): Result<Unit>
    
    suspend fun openFile(filePath: eu.darken.butler.common.files.APath): Result<Unit>
    
    suspend fun closeFile(): Result<Unit>
    
    suspend fun getText(startOffset: Long, endOffset: Long): Result<String>
    
    suspend fun getTextForLine(lineNumber: Int): Result<String>
    
    suspend fun getTextForRange(startLine: Int, endLine: Int): Result<String>
    
    suspend fun insertText(position: TextPosition, text: String): Result<TextPosition>
    
    suspend fun deleteText(startPosition: TextPosition, endPosition: TextPosition): Result<String>
    
    suspend fun replaceText(startPosition: TextPosition, endPosition: TextPosition, newText: String): Result<TextPosition>
    
    suspend fun findPosition(offset: Long): TextPosition
    
    suspend fun findOffset(line: Int, column: Int): Long
    
    suspend fun search(query: String, startFrom: TextPosition? = null, ignoreCase: Boolean = false): List<SearchResult>
    
    suspend fun saveFile(): Result<Unit>
    
    suspend fun saveFileAs(filePath: eu.darken.butler.common.files.APath): Result<Unit>
    
    suspend fun undo(): Result<EditOperation?>
    
    suspend fun redo(): Result<EditOperation?>
    
    fun canUndo(): Boolean
    
    fun canRedo(): Boolean
}

data class BufferState(
    val isLoading: Boolean = false,
    val error: Throwable? = null,
    val loadedChunks: Set<TextChunk.ChunkId> = emptySet(),
    val visibleRange: LongRange? = null
)