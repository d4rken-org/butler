package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.SearchOptions

/**
 * Search over the logical document via a sliding decoded window, tracking line/column in the
 * same pass. Literal/whole-word overlap covers the query length so matches spanning window
 * edges are found exactly once; regex matches longer than the overlap are a documented
 * limitation. Zero-length regex matches are skipped. Matches are non-overlapping (findAll
 * semantics, parity with the previous engine). Non-regex windows include one char of real
 * document context on each side so `\b` in whole-word queries never sees a false boundary
 * at a window edge.
 */
class WindowedSearch(
    private val baseWindowSize: Int = DEFAULT_WINDOW_SIZE,
    private val minOverlap: Int = DEFAULT_MIN_OVERLAP,
    private val readText: suspend (charStart: Long, charEnd: Long) -> String,
) {

    init {
        require(minOverlap >= 1 && baseWindowSize > minOverlap) {
            "Invalid window config: size=$baseWindowSize, overlap=$minOverlap"
        }
    }

    data class Match(
        val offset: Long,
        val line: Int,
        val column: Int,
        val matchText: String,
    )

    suspend fun search(totalLength: Long, query: String, options: SearchOptions): List<Match> {
        if (query.isEmpty()) return emptyList()

        val pattern = buildSearchPattern(query, options)
        val regexOptions = buildSet {
            if (!options.caseSensitive) add(RegexOption.IGNORE_CASE)
        }
        val regex = try {
            Regex(pattern, regexOptions)
        } catch (e: Exception) {
            log(TAG, WARN) { "Invalid regex pattern: $query - ${e.message}" }
            return emptyList()
        }

        val overlap = if (options.useRegex) minOverlap else maxOf(query.length - 1, minOverlap)
        val windowSize = maxOf(baseWindowSize, if (options.useRegex) 0 else 2 * query.length, overlap + 1)
        val stride = windowSize - overlap

        val results = mutableListOf<Match>()
        var windowStart = 0L
        var line = 0L
        var lineStart = 0L

        while (true) {
            val windowEnd = minOf(windowStart + windowSize, totalLength)
            val isFinal = windowEnd == totalLength
            val padStart = if (options.useRegex) windowStart else maxOf(windowStart - 1, 0L)
            val padEnd = if (options.useRegex) windowEnd else minOf(windowEnd + 1, totalLength)
            val text = readText(padStart, padEnd)
            val coreOffset = (windowStart - padStart).toInt()
            val acceptLimit = if (isFinal) Long.MAX_VALUE else windowStart + stride

            var scanPos = coreOffset

            // Advances counting breaks that END at or before the limit; stops inside a CRLF
            // whose '\n' lies beyond it. On non-final windows limits stay below the overlap,
            // so the one-char CRLF lookahead never leaves the window text.
            fun advanceTo(localLimit: Int) {
                while (scanPos < localLimit) {
                    when (text[scanPos]) {
                        '\n' -> {
                            line++
                            lineStart = padStart + scanPos + 1
                            scanPos++
                        }
                        '\r' -> {
                            if (scanPos + 1 < text.length && text[scanPos + 1] == '\n') {
                                if (scanPos + 2 <= localLimit) {
                                    line++
                                    lineStart = padStart + scanPos + 2
                                    scanPos += 2
                                } else {
                                    scanPos = localLimit
                                }
                            } else {
                                line++
                                lineStart = padStart + scanPos + 1
                                scanPos++
                            }
                        }
                        else -> scanPos++
                    }
                }
            }

            // Matching starts at the core so a pad-only match can never consume the non-overlap
            // slot of a real one; the pad still provides real \b context at the edges.
            for (match in regex.findAll(text, coreOffset)) {
                if (match.value.isEmpty()) continue
                val absolute = padStart + match.range.first
                if (absolute < windowStart || absolute >= acceptLimit) continue
                advanceTo(match.range.first)
                results += Match(
                    offset = absolute,
                    line = line.toInt(),
                    column = (absolute - lineStart).toInt(),
                    matchText = match.value,
                )
            }

            if (isFinal) break
            advanceTo(coreOffset + stride)
            windowStart += stride
        }
        return results
    }

    private fun buildSearchPattern(query: String, options: SearchOptions): String = when {
        options.useRegex -> query
        options.wholeWord -> "\\b${Regex.escape(query)}\\b"
        else -> Regex.escape(query)
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 64 * 1024
        const val DEFAULT_MIN_OVERLAP = 4 * 1024
        private val TAG = logTag("Editor", "Engine", "WindowedSearch")
    }
}
