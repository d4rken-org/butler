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
import eu.darken.butler.common.files.TextFileDetector
import eu.darken.butler.common.files.text.CharsetDetector
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.workspace.contracts.searcher.ContentQuery
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.withContext
import okio.BufferedSource
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
     * The file is opened exactly once: a small head sample is read first for binary detection
     * (each open is an IPC round-trip in ROOT/ADB modes), and only files that pass it read the
     * full content buffer.
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

        // 2. Known text extensions skip binary detection entirely
        val knownTextExtension = TextFileDetector.isTextFile(lookup.name)

        // 3. Single open: sniff the head for binary content, then read the rest
        val content: String? = try {
            gatewaySwitch.file(lookup.lookedUp, readWrite = false).use { handle ->
                handle.source().buffer().use { source ->
                    val head = source.readUpTo(minOf(BINARY_SNIFF_SIZE, SearchConfig.CONTENT_READ_BUFFER))
                    if (!includeBinaries && !knownTextExtension && detectBinary(head)) {
                        log(tag, VERBOSE) { "Skipping ${lookup.name} - detected as binary file" }
                        null
                    } else {
                        val rest = source.readUpTo(SearchConfig.CONTENT_READ_BUFFER - head.size)
                        val bytes = if (rest.isEmpty()) head else head + rest
                        decodeContent(bytes, truncated = bytes.size == SearchConfig.CONTENT_READ_BUFFER)
                    }
                }
            }
        } catch (e: Exception) {
            log(tag, WARN) { "Failed to read ${lookup.name}: ${e.asLog()}" }
            return@withContext null
        }
        if (content == null) return@withContext null

        // 4. Search for match in content
        findMatch(content, query)
    }

    /**
     * Reads up to [limit] bytes, looping until the buffer is full or EOF — a single read call
     * can return short and would skew detection.
     */
    private fun BufferedSource.readUpTo(limit: Int): ByteArray {
        if (limit <= 0) return ByteArray(0)
        val bytes = ByteArray(limit)
        var filled = 0
        while (filled < limit) {
            val read = read(bytes, filled, limit - filled)
            if (read == -1) break
            filled += read
        }
        return if (filled == bytes.size) bytes else bytes.copyOf(filled)
    }

    /**
     * Searches for pattern in content and returns match context with line information.
     */
    internal fun findMatch(
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

    @AssistedFactory
    interface Factory {
        fun create(workspaceId: Workspace.Id): ContentMatcher
    }

    companion object {
        /** Binary detection samples at most this many bytes from the head of the file */
        internal const val BINARY_SNIFF_SIZE = 512

        /**
         * Null-byte sniff on the head sample, BOM-aware: UTF-16 text legitimately contains
         * null bytes, so files with a BOM are never classified as binary.
         */
        internal fun detectBinary(head: ByteArray): Boolean {
            if (head.isEmpty()) return false
            if (CharsetDetector.detectBom(head) != null) return false
            return head.any { it == 0.toByte() }
        }

        /**
         * Decodes content bytes: BOM charset first (BOM stripped), then strict UTF-8, falling
         * back to ISO-8859-1 (a single-byte encoding that never fails).
         *
         * With [truncated], a multibyte UTF-8 sequence cut off at the buffer boundary is trimmed
         * before validation — otherwise a valid UTF-8 file read up to the buffer limit would fail
         * strict validation and the WHOLE buffer would decode as ISO-8859-1 mojibake.
         */
        internal fun decodeContent(bytes: ByteArray, truncated: Boolean = false): String {
            if (bytes.isEmpty()) return ""
            CharsetDetector.detectBom(bytes)?.let { detection ->
                return String(bytes.copyOfRange(detection.bomSize, bytes.size), detection.charset)
            }
            val candidate = if (truncated) trimIncompleteUtf8Tail(bytes) else bytes
            return if (CharsetDetector.isValidUtf8(candidate)) {
                String(candidate, Charsets.UTF_8)
            } else {
                String(bytes, Charsets.ISO_8859_1)
            }
        }

        private fun trimIncompleteUtf8Tail(bytes: ByteArray): ByteArray {
            var leadIndex = bytes.size - 1
            var continuations = 0
            while (leadIndex >= 0 && continuations < 3 && (bytes[leadIndex].toInt() and 0xC0) == 0x80) {
                leadIndex--
                continuations++
            }
            if (leadIndex < 0) return bytes
            val lead = bytes[leadIndex].toInt() and 0xFF
            val expected = when {
                lead >= 0xF0 -> 4
                lead >= 0xE0 -> 3
                lead >= 0xC0 -> 2
                // ASCII or invalid lead byte: nothing that could be an incomplete tail
                else -> return bytes
            }
            val available = bytes.size - leadIndex
            return if (available < expected) bytes.copyOf(leadIndex) else bytes
        }
    }
}
