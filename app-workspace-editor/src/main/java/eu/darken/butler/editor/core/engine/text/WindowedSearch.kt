package eu.darken.butler.editor.core.engine.text

import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.SearchOptions
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Search over the logical document, tracking line/column in the same pass. Matches are
 * non-overlapping (findAll semantics); zero-length regex matches are skipped.
 *
 * Literal and whole-word queries run over a sliding decoded window and produce EXACTLY the
 * matches of `Regex.findAll` over the whole document, independent of window and overlap size:
 * the overlap covers the query length, one char of real document context on each side keeps
 * `\b` honest at window edges, and consumption state carries across windows via the end offset
 * of the last accepted match.
 *
 * Regex queries cannot be windowed correctly in general (`^`/`$` would anchor at window edges,
 * lookaround across a boundary is silently missed, matches longer than the overlap are
 * truncated). Documents up to [regexFullScanCap] chars are therefore materialized once and
 * scanned whole — exact findAll semantics. Above the cap the windowed scan is used as a
 * fallback: anchors, lookaround, and matches longer than the overlap are unreliable there.
 */
class WindowedSearch(
    private val baseWindowSize: Int = DEFAULT_WINDOW_SIZE,
    private val minOverlap: Int = DEFAULT_MIN_OVERLAP,
    private val regexFullScanCap: Int = REGEX_FULL_SCAN_CAP,
    private val readText: suspend (charStart: Long, charEnd: Long) -> String,
) {

    init {
        require(minOverlap >= 1 && baseWindowSize > minOverlap) {
            "Invalid window config: size=$baseWindowSize, overlap=$minOverlap"
        }
    }

    data class Match(
        val offset: Long,
        val line: Long,
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

        val fullScan = options.useRegex && totalLength <= regexFullScanCap
        if (options.useRegex && !fullScan) {
            log(TAG, WARN) {
                "Document exceeds regex full-scan cap ($totalLength > $regexFullScanCap chars), " +
                    "falling back to windowed scan: anchors, lookaround, and matches longer than " +
                    "the overlap are unreliable"
            }
        }

        val overlap = if (options.useRegex) minOverlap else maxOf(query.length - 1, minOverlap)
        val windowSize = when {
            fullScan -> maxOf(totalLength.toInt(), overlap + 1)
            options.useRegex -> maxOf(baseWindowSize, overlap + 1)
            else -> maxOf(baseWindowSize, 2 * query.length, overlap + 1)
        }
        val stride = windowSize - overlap

        val results = mutableListOf<Match>()
        var windowStart = 0L
        var line = 0L
        var lineStart = 0L
        var lastMatchEnd = 0L

        while (true) {
            coroutineContext.ensureActive()
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

            // Matching starts at the core or just past the last accepted match, whichever is
            // later: a pad-only match can never consume the slot of a real one, and carrying
            // the consumption point across windows makes output equal whole-document findAll.
            val matchFrom = maxOf(coreOffset.toLong(), lastMatchEnd - padStart)
                .toInt()
                .coerceAtMost(text.length)
            for (match in regex.findAll(text, matchFrom)) {
                if (match.value.isEmpty()) continue
                val absolute = padStart + match.range.first
                if (absolute < windowStart || absolute >= acceptLimit) continue
                advanceTo(match.range.first)
                results += Match(
                    offset = absolute,
                    line = line,
                    column = (absolute - lineStart).toInt(),
                    matchText = match.value,
                )
                lastMatchEnd = padStart + match.range.last + 1
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
        const val REGEX_FULL_SCAN_CAP = 8 * 1024 * 1024
        private val TAG = logTag("Editor", "Engine", "WindowedSearch")
    }
}
