package eu.darken.butler.searcher.core.engine

import androidx.datastore.dataStore
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.createValue
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.datastore.valueBlocking
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.searcher.core.SearcherSettings
import kotlinx.coroutines.withContext
import okio.buffer
import okio.use
import javax.inject.Inject

class ContentMatcher @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
    private val searcherSettings: SearcherSettings,
) {
    private val tag = logTag("Searcher", "ContentMatcher")

    private val includeBinaries = searcherSettings.contentSearchBinaries.valueBlocking

    /**
     * Checks if file content matches the search query and returns match context if found.
     *
     * @param lookup The file to search within
     * @param query The search query parameters
     * @return MatchContext with line number and snippet if match found, null otherwise
     */
    suspend fun matchesContent(
        lookup: APathLookup<*>,
        query: SearchQuery,
    ): SearchItem.MatchContext? = withContext(dispatcherProvider.IO) {
        // 1. Size check - skip files that are too large
        val maxSize = 10_485_760L // 10MB
        if ((lookup.size ?: 0) > maxSize) {
            log(tag, VERBOSE) { "Skipping ${lookup.name} - size ${lookup.size} exceeds max $maxSize" }
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
     */
    private suspend fun readFileContent(lookup: APathLookup<*>): String {
        return gatewaySwitch.file(lookup.lookedUp, readWrite = false).use { handle ->
            handle.source().buffer().use { source ->
                val bytes = ByteArray(131_072) // 128 KB
                val bytesRead = source.read(bytes)
                if (bytesRead > 0) {
                    // Try UTF-8 first (most common)
                    String(bytes, 0, bytesRead, Charsets.UTF_8)
                } else {
                    ""
                }
            }
        }
    }

    /**
     * Searches for query in content and returns match context with line information.
     */
    private fun findMatch(
        content: String,
        query: SearchQuery,
    ): SearchItem.MatchContext? {
        val searchText = query.query
        val caseSensitive = query.filter.caseSensitive
        val useRegex = query.filter.useRegex
        val wholeWord = query.filter.wholeWord

        // Use line sequence for lazy evaluation (don't split entire file into memory)
        val lines = content.lineSequence()

        lines.forEachIndexed { index, line ->
            val matchIndex = when {
                useRegex -> {
                    val pattern = if (caseSensitive) {
                        searchText.toRegex()
                    } else {
                        searchText.toRegex(RegexOption.IGNORE_CASE)
                    }
                    val match = pattern.find(line)
                    match?.range?.first
                }

                wholeWord -> {
                    val pattern = if (caseSensitive) {
                        "\\b$searchText\\b".toRegex()
                    } else {
                        "\\b$searchText\\b".toRegex(RegexOption.IGNORE_CASE)
                    }
                    val match = pattern.find(line)
                    match?.range?.first
                }

                else -> {
                    line.indexOf(searchText, ignoreCase = !caseSensitive)
                }
            }

            // Return first match found (early exit optimization)
            if (matchIndex != null && matchIndex != -1) {
                return SearchItem.MatchContext(
                    lineNumber = index + 1, // 1-based line numbers for UI
                    matchedLine = line.take(200), // Truncate very long lines
                    startIndex = matchIndex,
                    endIndex = matchIndex + searchText.length,
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
}
