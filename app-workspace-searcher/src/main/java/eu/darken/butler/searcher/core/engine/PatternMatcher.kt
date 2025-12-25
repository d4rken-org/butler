package eu.darken.butler.searcher.core.engine

/**
 * Options for pattern matching operations.
 */
data class PatternOptions(
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val wholeWord: Boolean = false,
)

/**
 * Result of a pattern matching operation.
 */
sealed class MatchResult {
    /** Pattern was found at the given position */
    data class Found(val start: Int, val end: Int) : MatchResult()

    /** Pattern was not found in text */
    data object NotFound : MatchResult()

    /** Pattern was invalid (e.g., malformed regex) */
    data class InvalidPattern(val reason: String) : MatchResult()

    val isFound: Boolean get() = this is Found

    fun toRange(): Pair<Int, Int>? = when (this) {
        is Found -> start to end
        else -> null
    }
}

/**
 * Centralized pattern matching utility.
 * Consolidates regex, whole-word, and substring matching logic.
 * Uses thread-local caching for compiled regex patterns.
 */
object PatternMatcher {

    private const val CACHE_SIZE = 8

    // Thread-local LRU cache for compiled regex patterns
    private val regexCache = ThreadLocal.withInitial {
        object : LinkedHashMap<String, Regex>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>?): Boolean {
                return size > CACHE_SIZE
            }
        }
    }

    /**
     * Returns true if pattern matches anywhere in text.
     */
    fun matches(text: String, pattern: String, options: PatternOptions): MatchResult {
        if (pattern.isBlank()) return MatchResult.InvalidPattern("Pattern is blank")

        return when {
            options.useRegex -> matchRegex(text, pattern, options.caseSensitive)
            options.wholeWord -> matchWholeWord(text, pattern, options.caseSensitive)
            else -> {
                if (text.contains(pattern, ignoreCase = !options.caseSensitive)) {
                    // For simple contains, we don't track position in matches()
                    MatchResult.Found(0, 0)
                } else {
                    MatchResult.NotFound
                }
            }
        }
    }

    /**
     * Finds the first match of pattern in text.
     */
    fun find(text: String, pattern: String, options: PatternOptions): MatchResult {
        if (pattern.isBlank()) return MatchResult.InvalidPattern("Pattern is blank")

        return when {
            options.useRegex -> findRegex(text, pattern, options.caseSensitive)
            options.wholeWord -> findWholeWord(text, pattern, options.caseSensitive)
            else -> findSubstring(text, pattern, options.caseSensitive)
        }
    }

    private fun getOrCompileRegex(pattern: String, caseSensitive: Boolean): Regex {
        val cacheKey = "${if (caseSensitive) "s" else "i"}:$pattern"
        val cache = regexCache.get()!!

        return cache.getOrPut(cacheKey) {
            if (caseSensitive) {
                pattern.toRegex()
            } else {
                pattern.toRegex(RegexOption.IGNORE_CASE)
            }
        }
    }

    private fun matchRegex(text: String, pattern: String, caseSensitive: Boolean): MatchResult {
        return try {
            val regex = getOrCompileRegex(pattern, caseSensitive)
            if (regex.containsMatchIn(text)) {
                MatchResult.Found(0, 0) // Position not needed for matches()
            } else {
                MatchResult.NotFound
            }
        } catch (e: Exception) {
            MatchResult.InvalidPattern("Invalid regex: ${e.message}")
        }
    }

    private fun matchWholeWord(text: String, pattern: String, caseSensitive: Boolean): MatchResult {
        return try {
            val wordPattern = "\\b${Regex.escape(pattern)}\\b"
            val regex = getOrCompileRegex(wordPattern, caseSensitive)
            if (regex.containsMatchIn(text)) {
                MatchResult.Found(0, 0)
            } else {
                MatchResult.NotFound
            }
        } catch (e: Exception) {
            MatchResult.InvalidPattern("Invalid whole-word pattern: ${e.message}")
        }
    }

    private fun findRegex(text: String, pattern: String, caseSensitive: Boolean): MatchResult {
        return try {
            val regex = getOrCompileRegex(pattern, caseSensitive)
            val match = regex.find(text)
            if (match != null) {
                MatchResult.Found(match.range.first, match.range.last + 1)
            } else {
                MatchResult.NotFound
            }
        } catch (e: Exception) {
            MatchResult.InvalidPattern("Invalid regex: ${e.message}")
        }
    }

    private fun findWholeWord(text: String, pattern: String, caseSensitive: Boolean): MatchResult {
        return try {
            val wordPattern = "\\b${Regex.escape(pattern)}\\b"
            val regex = getOrCompileRegex(wordPattern, caseSensitive)
            val match = regex.find(text)
            if (match != null) {
                MatchResult.Found(match.range.first, match.range.last + 1)
            } else {
                MatchResult.NotFound
            }
        } catch (e: Exception) {
            MatchResult.InvalidPattern("Invalid whole-word pattern: ${e.message}")
        }
    }

    private fun findSubstring(text: String, pattern: String, caseSensitive: Boolean): MatchResult {
        val idx = text.indexOf(pattern, ignoreCase = !caseSensitive)
        return if (idx >= 0) {
            MatchResult.Found(idx, idx + pattern.length)
        } else {
            MatchResult.NotFound
        }
    }
}
