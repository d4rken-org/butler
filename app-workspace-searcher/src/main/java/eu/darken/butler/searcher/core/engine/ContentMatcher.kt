package eu.darken.butler.searcher.core.engine

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okio.BufferedSource
import okio.buffer
import okio.use
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

@Singleton
class ContentMatcher @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
) {
    private val tag = logTag("Searcher", "ContentMatcher")

    sealed interface Outcome {
        data class Match(val context: SearchItem.MatchContext, val degraded: Boolean = false) : Outcome
        data class NoMatch(val degraded: Boolean = false) : Outcome
        data class Skipped(val reason: Reason) : Outcome {
            enum class Reason { TOO_LARGE, BINARY }
        }
        data class Failed(val error: Exception) : Outcome
    }

    /**
     * Streams file content and returns the first match with its context lines.
     *
     * The file is opened exactly once (each open is an IPC round-trip in ROOT/ADB modes): a small
     * head sample is read for binary detection, then content streams through an incremental
     * charset decoder in [SearchConfig.CONTENT_READ_BUFFER] chunks up to
     * [SearchConfig.MAX_CONTENT_FILE_SIZE] — a match anywhere within that limit is found, and
     * reading stops as soon as the match plus its context lines are assembled.
     *
     * `degraded` marks files that were only partially searched (line longer than
     * [SearchConfig.MAX_LINE_LENGTH], or unreported size exceeding the content cap).
     */
    suspend fun matchesContent(
        lookup: APathLookup<*>,
        query: ContentQuery,
        includeBinaries: Boolean,
    ): Outcome = withContext(dispatcherProvider.IO) {
        if ((lookup.size ?: 0) > SearchConfig.MAX_CONTENT_FILE_SIZE) {
            log(tag, VERBOSE) { "Skipping ${lookup.name} - size ${lookup.size} exceeds max" }
            return@withContext Outcome.Skipped(Outcome.Skipped.Reason.TOO_LARGE)
        }
        if (query.pattern.isBlank()) return@withContext Outcome.NoMatch()

        val knownTextExtension = TextFileDetector.isTextFile(lookup.name)

        try {
            gatewaySwitch.file(lookup.lookedUp, readWrite = false).use { handle ->
                handle.source().buffer().use { source ->
                    val head = source.readUpTo(BINARY_SNIFF_SIZE)
                    if (!includeBinaries && !knownTextExtension && detectBinary(head)) {
                        log(tag, VERBOSE) { "Skipping ${lookup.name} - detected as binary file" }
                        return@withContext Outcome.Skipped(Outcome.Skipped.Reason.BINARY)
                    }
                    streamAndMatch(head, source, query)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(tag, WARN) { "Failed to read ${lookup.name}: ${e.asLog()}" }
            Outcome.Failed(e)
        }
    }

    private suspend fun streamAndMatch(
        head: ByteArray,
        source: BufferedSource,
        query: ContentQuery,
    ): Outcome {
        val job = currentCoroutineContext().job
        val assembler = LineAssembler(query, job)

        val bom = CharsetDetector.detectBom(head)
        var decoder = newDecoder(
            charset = bom?.charset ?: Charsets.UTF_8,
            // Strict UTF-8 so a malformed sequence switches the remainder to ISO-8859-1; a
            // BOM-declared charset is authoritative and malformed bytes are just replaced.
            report = bom == null,
        )

        val byteBuffer = ByteBuffer.allocate(SearchConfig.CONTENT_READ_BUFFER + DECODER_SLACK)
        val charBuffer = CharBuffer.allocate(SearchConfig.CONTENT_READ_BUFFER + DECODER_SLACK)
        var totalBytes = 0L
        var degradedBySize = false

        fun drainCharBuffer(): SearchItem.MatchContext? {
            charBuffer.flip()
            val context = assembler.onChars(charBuffer)
            charBuffer.clear()
            return context
        }

        suspend fun decode(bytes: ByteArray, offset: Int, length: Int, endOfInput: Boolean): SearchItem.MatchContext? {
            var fed = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (fed < length) {
                    val space = minOf(byteBuffer.remaining(), length - fed)
                    byteBuffer.put(bytes, offset + fed, space)
                    fed += space
                }
                byteBuffer.flip()
                val lastFeed = fed >= length
                while (true) {
                    val result = decoder.decode(byteBuffer, charBuffer, endOfInput && lastFeed)
                    drainCharBuffer()?.let { return it }
                    when {
                        result.isUnderflow -> break
                        result.isOverflow -> continue
                        result.isError -> {
                            // First malformed sequence in strict UTF-8 mode: the rest of the
                            // file (including the offending bytes) decodes as ISO-8859-1.
                            decoder = newDecoder(Charsets.ISO_8859_1, report = false)
                        }
                    }
                }
                byteBuffer.compact()
                if (lastFeed) return null
            }
        }

        // Head first (BOM stripped), then stream chunks up to the content cap.
        val bomSize = bom?.bomSize ?: 0
        decode(head, bomSize, head.size - bomSize, endOfInput = false)?.let {
            return Outcome.Match(it, assembler.degraded)
        }
        totalBytes += head.size - bomSize

        val chunk = ByteArray(SearchConfig.CONTENT_READ_BUFFER)
        while (totalBytes < SearchConfig.MAX_CONTENT_FILE_SIZE) {
            currentCoroutineContext().ensureActive()
            val read = source.read(chunk, 0, chunk.size)
            if (read == -1) break
            totalBytes += read
            decode(chunk, 0, read, endOfInput = false)?.let {
                return Outcome.Match(it, assembler.degraded)
            }
        }
        if (totalBytes >= SearchConfig.MAX_CONTENT_FILE_SIZE && source.read(chunk, 0, 1) != -1) {
            // The file kept going past the cap despite the earlier size gate (unknown or stale size)
            log(tag, VERBOSE) { "Content capped at ${SearchConfig.MAX_CONTENT_FILE_SIZE} bytes" }
            degradedBySize = true
        }

        decode(EMPTY, 0, 0, endOfInput = true)?.let {
            return Outcome.Match(it, assembler.degraded || degradedBySize)
        }
        while (true) {
            val result = decoder.flush(charBuffer)
            drainCharBuffer()?.let { return Outcome.Match(it, assembler.degraded || degradedBySize) }
            if (!result.isOverflow) break
        }
        assembler.onEof()?.let { return Outcome.Match(it, assembler.degraded || degradedBySize) }

        return Outcome.NoMatch(assembler.degraded || degradedBySize)
    }

    private fun newDecoder(charset: Charset, report: Boolean): CharsetDecoder = charset.newDecoder()
        .onMalformedInput(if (report) CodingErrorAction.REPORT else CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

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
     * Incremental line splitter and matcher: keeps the two context lines before the current line,
     * matches each completed line, and after a match reads ahead until the two context lines
     * after it exist (or EOF). Handles \n, \r\n, and lone \r terminators, including a \r\n pair
     * split across chunk boundaries.
     */
    private class LineAssembler(
        private val query: ContentQuery,
        private val job: Job,
    ) {
        private val needsCancellableText = query.patternOptions.useRegex || query.patternOptions.wholeWord

        private val before = ArrayDeque<String>()
        private val current = StringBuilder()
        private var lineNumber = 0
        private var lastWasCr = false
        private var skippingOverlongTail = false

        private var pending: PendingMatch? = null
        var degraded = false
            private set

        private class PendingMatch(
            val lineNumber: Int,
            val matchedLine: String,
            val range: Pair<Int, Int>,
            val contextBefore: List<String>?,
            val contextAfter: MutableList<String> = mutableListOf(),
        )

        fun onChars(chars: CharSequence): SearchItem.MatchContext? {
            for (i in 0 until chars.length) {
                val c = chars[i]
                when {
                    c == '\r' -> {
                        lastWasCr = true
                        completeLine()?.let { return it }
                    }
                    c == '\n' -> {
                        if (lastWasCr) {
                            lastWasCr = false
                        } else {
                            completeLine()?.let { return it }
                        }
                    }
                    else -> {
                        lastWasCr = false
                        when {
                            skippingOverlongTail -> Unit
                            current.length >= SearchConfig.MAX_LINE_LENGTH -> {
                                degraded = true
                                skippingOverlongTail = true
                            }
                            else -> current.append(c)
                        }
                    }
                }
            }
            return null
        }

        fun onEof(): SearchItem.MatchContext? {
            if (current.isNotEmpty()) {
                completeLine()?.let { return it }
            }
            return pending?.let { buildContext(it) }
        }

        private fun completeLine(): SearchItem.MatchContext? {
            val line = current.toString()
            current.setLength(0)
            skippingOverlongTail = false
            lineNumber++

            pending?.let { match ->
                match.contextAfter += line.take(SearchConfig.MAX_CONTEXT_LINE_LENGTH)
                if (match.contextAfter.size >= SearchConfig.CONTEXT_LINES_AFTER) {
                    return buildContext(match)
                }
                return null
            }

            val text = if (needsCancellableText) CancellableCharSequence(line, job) else line
            val matchResult = PatternMatcher.find(text, query.pattern, query.patternOptions)
            if (matchResult.isFound) {
                val range = matchResult.toRange()!!
                pending = PendingMatch(
                    lineNumber = lineNumber,
                    matchedLine = line,
                    range = range,
                    contextBefore = before.toList().takeIf { it.isNotEmpty() },
                )
            } else {
                before.addLast(line.take(SearchConfig.MAX_CONTEXT_LINE_LENGTH))
                if (before.size > SearchConfig.CONTEXT_LINES_BEFORE) before.removeFirst()
            }
            return null
        }

        private fun buildContext(match: PendingMatch): SearchItem.MatchContext {
            pending = null
            return SearchItem.MatchContext(
                matchType = SearchItem.MatchContext.MatchType.CONTENT,
                lineNumber = match.lineNumber,
                matchedLine = match.matchedLine,
                startIndex = match.range.first,
                endIndex = minOf(match.range.second, match.matchedLine.length),
                contextBefore = match.contextBefore,
                contextAfter = match.contextAfter.toList().takeIf { it.isNotEmpty() },
            )
        }
    }

    /** Lets regex matching observe coroutine cancellation, bounding catastrophic backtracking. */
    private class CancellableCharSequence(
        private val delegate: CharSequence,
        private val job: Job,
    ) : CharSequence {
        private var counter = 0

        override val length: Int get() = delegate.length

        override fun get(index: Int): Char {
            if (++counter and 0x3FF == 0) job.ensureActive()
            return delegate[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            CancellableCharSequence(delegate.subSequence(startIndex, endIndex), job)

        override fun toString(): String = delegate.toString()
    }

    companion object {
        /** Binary detection samples at most this many bytes from the head of the file */
        internal const val BINARY_SNIFF_SIZE = 512

        private const val DECODER_SLACK = 16

        private val EMPTY = ByteArray(0)

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
         * back to ISO-8859-1 (a single-byte encoding that never fails). Retained for callers
         * and tests that decode a bounded buffer in one go; streaming uses CharsetDecoder.
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
