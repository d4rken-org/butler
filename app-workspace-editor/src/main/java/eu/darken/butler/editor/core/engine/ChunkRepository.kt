package eu.darken.butler.editor.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.sources.EditorDataSource
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChunkRepository @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    @Assisted val dataSource: EditorDataSource,
) {

    private val tag = logTag("Editor", "Workspace", workspaceId.shortTag, "Engine", "ChunkRepository")

    fun getContentSource(): ContentSource {
        return dataSource.contentSource.value
    }

    suspend fun loadChunk(chunkId: TextChunk.ChunkId, boundary: ChunkBoundary): TextChunk =
        withContext(Dispatchers.IO) {
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
            // Note: isLastChunk defaults to true; ChunkManager will recalculate with proper values
            val lineCount = countLines(validContent, lineEnding, isLastChunk = true)

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
     * NOTE: We do NOT remove orphaned low surrogates from the start, as this would cause
     * data loss. Instead, we rely on loading chunks in order so that when a chunk ends
     * mid-pair (and shrinks), the next chunk's boundary is adjusted to include the complete
     * pair before it's loaded.
     *
     * @param content The decoded string content from DataSource
     * @return Content with complete surrogate pairs only at the end
     */
    private fun adjustForSurrogatePairs(content: String): String {
        if (content.isEmpty()) return content

        val lastIndex = content.length - 1

        // Check if content ends with a high surrogate (first half of pair)
        // If so, we have an incomplete pair - truncate it
        if (Character.isHighSurrogate(content[lastIndex])) {
            log(tag) { "Adjusting content: truncating orphaned high surrogate at end" }
            return content.take(lastIndex)
        }

        return content
    }

    /**
     * Detects the line ending style used in the given content.
     * Prioritizes the most common style found in the text.
     */
    fun detectLineEnding(content: String): LineEnding {
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
     *
     * @param content The text content to count lines in
     * @param lineEnding The line ending style detected in the content
     * @param isLastChunk Whether this is the last chunk in the file (affects +1 for missing newline)
     * @return The number of lines in the content
     */
    fun countLines(content: String, lineEnding: LineEnding, isLastChunk: Boolean = true): Int {
        if (content.isEmpty()) return if (isLastChunk) 1 else 0

        val newlineCount = when (lineEnding) {
            LineEnding.LF -> content.count { it == '\n' }
            LineEnding.CRLF -> content.count { it == '\n' }  // Count LF (part of every CRLF)
            LineEnding.CR -> content.count { it == '\r' }
            LineEnding.MIXED -> {
                // For mixed, count distinct line endings (avoid double-counting CRLF)
                val crlfCount = content.windowed(2).count { it == "\r\n" }
                val totalLfCount = content.count { it == '\n' }
                val totalCrCount = content.count { it == '\r' }
                val standaloneLf = totalLfCount - crlfCount
                val standaloneCr = totalCrCount - crlfCount
                crlfCount + standaloneLf + standaloneCr
            }
        }

        // Only add +1 for last chunk if it doesn't end with newline
        if (isLastChunk) {
            val endsWithNewline = when (lineEnding) {
                LineEnding.LF -> content.endsWith('\n')
                LineEnding.CRLF -> content.endsWith('\n') || content.endsWith("\r\n")
                LineEnding.CR -> content.endsWith('\r')
                LineEnding.MIXED -> content.endsWith('\n') || content.endsWith("\r\n") || content.endsWith('\r')
            }
            return newlineCount + if (!endsWithNewline) 1 else 0
        }

        return newlineCount
    }

    /**
     * Saves dirty chunks to the data source.
     * DataSource handles merging and persistence.
     *
     * @param dirtyChunks List of modified chunks to save
     * @param boundaries Map of chunk IDs to their file positions
     */
    suspend fun saveFile(dirtyChunks: List<TextChunk>, boundaries: Map<TextChunk.ChunkId, ChunkBoundary>) =
        withContext(Dispatchers.IO) {
            log(tag) { "Saving ${dirtyChunks.size} dirty chunks to data source" }
            dataSource.save(dirtyChunks, boundaries)
            log(tag) { "Successfully saved chunks" }
        }

    /**
     * Search for a query string within a specific chunk.
     *
     * Note: Line numbers in results are chunk-relative (0-based within the chunk).
     * The caller (ChunkedTextBuffer) is responsible for converting to file-relative line numbers.
     *
     * @param chunk The chunk to search in
     * @param boundary The chunk's position in the file
     * @param query The search query (plain text or regex pattern)
     * @param options Search options controlling case sensitivity, regex mode, and whole word matching
     * @return List of search results, or empty list on regex syntax error
     */
    suspend fun searchInChunk(
        chunk: TextChunk,
        boundary: ChunkBoundary,
        query: String,
        options: SearchOptions = SearchOptions(),
    ): List<SearchResult> {
        try {
            // Empty query returns no results
            if (query.isEmpty()) {
                return emptyList()
            }

            val results = mutableListOf<SearchResult>()

            // Build the effective pattern based on options
            val pattern = buildSearchPattern(query, options)
            val regexOptions = buildSet {
                if (!options.caseSensitive) add(RegexOption.IGNORE_CASE)
            }

            val regex = try {
                Regex(pattern, regexOptions)
            } catch (e: Exception) {
                log(tag, WARN) { "Invalid regex pattern: $query - ${e.message}" }
                return emptyList()
            }

            // Find all matches
            regex.findAll(chunk.content).forEach { matchResult ->
                val foundIndex = matchResult.range.first
                val matchText = matchResult.value

                // Skip zero-length matches (can happen with some regex patterns)
                if (matchText.isEmpty()) return@forEach

                val absoluteOffset = boundary.startOffset + foundIndex
                // Line number is chunk-relative (0-based within chunk)
                val lineNumber = chunk.content.take(foundIndex).count { it == '\n' }
                val lineStart = chunk.content.lastIndexOf('\n', foundIndex - 1) + 1
                val columnNumber = foundIndex - lineStart

                results.add(
                    SearchResult(
                        position = TextPosition(absoluteOffset, lineNumber, columnNumber),
                        matchText = matchText,
                        chunkId = chunk.id
                    )
                )
            }

            return results

        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to search in chunk: ${chunk.id} - ${e.asLog()}" }
            return emptyList()
        }
    }

    /**
     * Builds the regex pattern based on search options.
     *
     * - Plain search: escapes the query for literal matching
     * - Regex search: uses the query as-is
     * - Whole word: wraps with word boundaries (only for plain search)
     */
    private fun buildSearchPattern(query: String, options: SearchOptions): String {
        return when {
            options.useRegex -> {
                // User provides their own regex pattern
                query
            }
            options.wholeWord -> {
                // Wrap escaped query with word boundaries
                "\\b${Regex.escape(query)}\\b"
            }
            else -> {
                // Plain text search - escape for literal matching
                Regex.escape(query)
            }
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

data class SearchResult(
    val position: TextPosition,
    val matchText: String,
    val chunkId: TextChunk.ChunkId
)