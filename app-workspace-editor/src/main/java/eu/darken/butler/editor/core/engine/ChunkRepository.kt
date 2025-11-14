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
) {

    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "ChunkRepository")

    suspend fun getFileInfo(): FileInfo? {
        return dataSource.fileInfo.value
    }

    suspend fun loadChunk(chunkId: TextChunk.ChunkId, boundary: ChunkBoundary): TextChunk = withContext(Dispatchers.IO) {
        log(tag) { "Loading chunk: $chunkId at ${boundary.startOffset}-${boundary.endOffset}" }

        // Read from DataSource (may contain incomplete UTF-16 surrogate pairs at boundaries)
        val rawContent = dataSource.readChunk(boundary.startOffset, boundary.size)

        // CRITICAL: Adjust for UTF-16 surrogate pairs
        // DataSource reads byte-based chunks, which can split multi-byte UTF-8 characters
        // This creates incomplete UTF-16 surrogate pairs in JVM Strings
        val validContent = adjustForSurrogatePairs(rawContent)

        // Detect line ending style in this chunk
        val lineEnding = detectLineEnding(validContent)

        // Count lines using detected style
        val lineCount = countLines(validContent, lineEnding)

        val chunk = TextChunk(
            id = chunkId,
            content = validContent,  // Adjusted content with complete characters only
            lineCount = lineCount,
            lineEnding = lineEnding,
            isDirty = false,
            isLoaded = true
        )

        log(tag) { "Loaded chunk: $chunkId (${validContent.length} bytes, $lineCount lines, $lineEnding)" }
        chunk
    }

    /**
     * Adjusts content to ensure it doesn't end mid-surrogate-pair.
     *
     * UTF-16 surrogate pairs consist of two Char values:
     * - High surrogate: U+D800 to U+DBFF
     * - Low surrogate: U+DC00 to U+DFFF
     *
     * When DataSource reads byte-based chunks, multi-byte UTF-8 characters (like emoji)
     * can be split at chunk boundaries. This creates incomplete UTF-16 surrogate pairs
     * in the decoded String.
     *
     * If content ends with a high surrogate (incomplete pair), we truncate it.
     * ChunkManager will adjust chunk boundaries accordingly and the "missing" character
     * will become the first character of the next chunk.
     *
     * @param content The decoded string content from DataSource
     * @return Content with complete surrogate pairs only
     */
    private fun adjustForSurrogatePairs(content: String): String {
        if (content.isEmpty()) return content

        val lastIndex = content.length - 1

        // Check if content ends with a high surrogate (first half of pair)
        // If so, we have an incomplete pair - truncate it
        if (Character.isHighSurrogate(content[lastIndex])) {
            log(tag) { "Adjusting content: truncating orphaned high surrogate at end" }
            return content.substring(0, lastIndex)
        }

        return content
    }

    /**
     * Detects the line ending style used in the given content.
     * Prioritizes the most common style found in the text.
     */
    private fun detectLineEnding(content: String): LineEnding {
        if (content.isEmpty()) return LineEnding.LF  // Default for empty content

        val crlfCount = content.windowed(2).count { it == "\r\n" }
        val lfCount = content.count { it == '\n' } - crlfCount  // LF not part of CRLF
        val crCount = content.count { it == '\r' } - crlfCount  // CR not part of CRLF

        return when {
            // Pure CRLF (Windows)
            crlfCount > 0 && lfCount == 0 && crCount == 0 -> LineEnding.CRLF
            // Pure LF (Unix)
            lfCount > 0 && crlfCount == 0 && crCount == 0 -> LineEnding.LF
            // Pure CR (old Mac)
            crCount > 0 && lfCount == 0 && crlfCount == 0 -> LineEnding.CR
            // Mixed or multiple styles present
            else -> {
                if (crlfCount + lfCount + crCount == 0) LineEnding.LF  // No newlines, default LF
                else LineEnding.MIXED
            }
        }
    }

    /**
     * Counts the number of lines in content based on the detected line ending style.
     */
    private fun countLines(content: String, lineEnding: LineEnding): Int {
        if (content.isEmpty()) return 1  // Empty content is 1 line

        val lineCount = when (lineEnding) {
            LineEnding.LF -> content.count { it == '\n' }
            LineEnding.CRLF -> content.windowed(2).count { it == "\r\n" }
            LineEnding.CR -> content.count { it == '\r' }
            LineEnding.MIXED -> content.count { it == '\n' }  // Use LF as primary for mixed
        }

        // Add 1 if content doesn't end with a newline (last line has no terminator)
        val endsWithNewline = when (lineEnding) {
            LineEnding.LF -> content.endsWith('\n')
            LineEnding.CRLF -> content.endsWith("\r\n")
            LineEnding.CR -> content.endsWith('\r')
            LineEnding.MIXED -> content.endsWith('\n') || content.endsWith("\r\n") || content.endsWith('\r')
        }

        return lineCount + if (!endsWithNewline) 1 else 0
    }

    /**
     * Saves dirty chunks to the data source.
     * DataSource handles merging and persistence.
     *
     * @param dirtyChunks List of modified chunks to save
     * @param boundaries Map of chunk IDs to their file positions
     */
    suspend fun saveFile(dirtyChunks: List<TextChunk>, boundaries: Map<TextChunk.ChunkId, ChunkBoundary>) = withContext(Dispatchers.IO) {
        log(tag) { "Saving ${dirtyChunks.size} dirty chunks to data source" }
        dataSource.save(dirtyChunks, boundaries)
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
        boundary: ChunkBoundary,
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

                val absoluteOffset = boundary.startOffset + foundIndex
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
        ): ChunkRepository
    }
}

data class FileInfo(
    val path: APath<*>,
    val size: Long,
    val lastModified: Instant,
    val canWrite: Boolean,
    val lineEnding: LineEnding = LineEnding.LF
)

data class SearchResult(
    val position: TextPosition,
    val matchText: String,
    val chunkId: TextChunk.ChunkId
)