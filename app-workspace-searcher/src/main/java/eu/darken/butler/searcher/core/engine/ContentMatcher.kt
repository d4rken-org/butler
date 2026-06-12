package eu.darken.butler.searcher.core.engine

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.withContext
import okio.buffer
import okio.use

class ContentMatcher @AssistedInject constructor(
    @Assisted private val workspaceId: Workspace.Id,
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
) {
    private val tag = logTag("Searcher", "Workspace", workspaceId.shortTag, "ContentMatcher")

    /**
     * Checks if file content matches the pattern and returns match context if found.
     *
     * @param lookup The file to search within
     * @param query The content query with search text and options
     * @param includeBinaries Whether to search binary files or skip them
     * @return MatchContext with line number and snippet if match found, null otherwise
     */
    suspend fun matchesContent(
        lookup: APathLookup<*>,
        query: ContentQuery,
        includeBinaries: Boolean,
    ): SearchItem.MatchContext? = withContext(dispatcherProvider.IO) {
        // 1. Size check - skip files that are too large
        if ((lookup.size ?: 0) > SearchConfig.MAX_CONTENT_FILE_SIZE) {
            log(tag, VERBOSE) { "Skipping ${lookup.name} - size ${lookup.size} exceeds max ${SearchConfig.MAX_CONTENT_FILE_SIZE}" }
            return@withContext null
        }

        // 2. Binary detection - skip binary files to avoid wasting time
        if (!includeBinaries && isBinaryFile(lookup)) {
            log(tag, VERBOSE) { "Skipping ${lookup.name} - detected as binary file" }
            return@withContext null
        }

        // 3. Read file content (first buffer only for performance)
        val content = try {
            readFileContent(lookup)
        } catch (e: Exception) {
            log(tag, WARN) { "Failed to read ${lookup.name}: ${e.asLog()}" }
            return@withContext null
        }

        // 4. Search for match in content
        findMatch(content, query)
    }

    /**
     * Reads file content up to buffer size limit.
     * Uses UTF-8 with fallback to ISO-8859-1 (single-byte encoding that never fails).
     */
    private suspend fun readFileContent(lookup: APathLookup<*>): String {
        return gatewaySwitch.file(lookup.lookedUp, readWrite = false).use { handle ->
            handle.source().buffer().use { source ->
                val bytes = ByteArray(SearchConfig.CONTENT_READ_BUFFER)
                val bytesRead = source.read(bytes)
                if (bytesRead > 0) {
                    val contentBytes = if (bytesRead == bytes.size) bytes else bytes.copyOf(bytesRead)
                    // Try UTF-8 first, fall back to ISO-8859-1 (never fails)
                    tryDecodeUtf8(contentBytes) ?: String(contentBytes, Charsets.ISO_8859_1)
                } else {
                    ""
                }
            }
        }
    }

    /**
     * Attempts UTF-8 decoding with strict validation.
     * Returns null if the bytes are not valid UTF-8.
     */
    private fun tryDecodeUtf8(bytes: ByteArray): String? {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    /**
     * Searches for pattern in content and returns match context with line information.
     */
    private fun findMatch(
        content: String,
        query: ContentQuery,
    ): SearchItem.MatchContext? {
        // Guard against empty/blank patterns
        if (query.pattern.isBlank()) {
            log(tag, WARN) { "Skipping content search - empty pattern" }
            return null
        }

        // Split into lines (content is already limited, so this is safe)
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            val matchResult = PatternMatcher.find(line, query.pattern, query.patternOptions)

            // Return first match found (early exit optimization)
            if (matchResult.isFound) {
                val matchRange = matchResult.toRange()!!
                // Capture context lines before and after match
                val contextBefore = if (index >= SearchConfig.CONTEXT_LINES_BEFORE) {
                    lines.subList(index - SearchConfig.CONTEXT_LINES_BEFORE, index)
                        .map { it.take(SearchConfig.MAX_CONTEXT_LINE_LENGTH) }
                } else if (index >= 1) {
                    lines.subList(0, index).map { it.take(SearchConfig.MAX_CONTEXT_LINE_LENGTH) }
                } else {
                    null
                }

                val contextAfter = if (index + SearchConfig.CONTEXT_LINES_AFTER < lines.size) {
                    lines.subList(index + 1, index + 1 + SearchConfig.CONTEXT_LINES_AFTER)
                        .map { it.take(SearchConfig.MAX_CONTEXT_LINE_LENGTH) }
                } else if (index + 1 < lines.size) {
                    lines.subList(index + 1, lines.size).map { it.take(SearchConfig.MAX_CONTEXT_LINE_LENGTH) }
                } else {
                    null
                }

                return SearchItem.MatchContext(
                    matchType = SearchItem.MatchContext.MatchType.CONTENT,
                    lineNumber = index + 1, // 1-based line numbers for UI
                    matchedLine = line, // Keep full line, UI will handle display truncation
                    startIndex = matchRange.first,
                    endIndex = minOf(matchRange.second, line.length), // Bounds check for safety
                    contextBefore = contextBefore,
                    contextAfter = contextAfter,
                )
            }
        }

        return null // No match found
    }

    private val searchableExtensions = setOf(
        "txt", "log", "md", "markdown", "rst",
        "json", "xml", "yaml", "yml", "toml", "ini", "conf", "config",
        "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx", "c", "cpp", "h", "hpp",
        "html", "css", "scss", "sass", "less",
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1",
        "sql", "gradle", "properties", "env",
    )

    /**
     * Detects if a file is likely binary (non-text) based on extension and content.
     */
    private suspend fun isBinaryFile(lookup: APathLookup<*>): Boolean {
        // Fast check: whitelist common text file extensions
        val extension = lookup.name.substringAfterLast('.', "").lowercase()
        if (extension in searchableExtensions) {
            return false // Definitely text
        }

        // For unknown extensions, check for null bytes (indicates binary)
        return try {
            val sampleSize = 512
            gatewaySwitch.file(lookup.lookedUp, readWrite = false).use { handle ->
                handle.source().buffer().use { source ->
                    val bytes = ByteArray(sampleSize)
                    val bytesRead = source.read(bytes)
                    if (bytesRead > 0) {
                        // Check for null bytes - strong indicator of binary file
                        bytes.take(bytesRead).contains(0.toByte())
                    } else {
                        false // Empty file, treat as text
                    }
                }
            }
        } catch (e: Exception) {
            log(tag, WARN) { "Failed binary detection for ${lookup.name}: ${e.asLog()}" }
            true // Assume binary if we can't read it
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): ContentMatcher
    }
}
