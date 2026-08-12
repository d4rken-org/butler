package eu.darken.butler.explorer.core

/**
 * Pattern matcher supporting simple substring matching and regex patterns
 */
object PatternMatcher {

    /**
     * Convert simple text to regex pattern by wrapping with wildcards
     * If useRegex is true, return input as-is (raw regex)
     */
    fun toRegexPattern(input: String, useRegex: Boolean): String {
        if (input.isBlank()) return input
        if (useRegex) {
            return input // Raw regex mode
        }
        // Simple mode: escape regex metacharacters and wrap for substring matching
        val escaped = Regex.escape(input)
        return ".*$escaped.*"
    }

    /**
     * Compile [input] once for a whole filtering pass (case-insensitive), null for a pattern that
     * cannot be compiled - the invalid-regex fallback callers see per item via [matches].
     */
    fun compile(input: String, useRegex: Boolean): Regex? = try {
        Regex(toRegexPattern(input, useRegex), RegexOption.IGNORE_CASE)
    } catch (e: Exception) {
        null
    }

    /**
     * Match a string against a pattern compiled by [compile]
     * In simple mode: substring matching
     * In advanced mode: full regex support
     * An empty pattern matches only empty text, an uncompilable one matches nothing
     */
    fun matches(text: String, pattern: Regex?): Boolean {
        if (pattern == null) return false
        if (pattern.pattern.isEmpty()) return text.isEmpty()
        return pattern.containsMatchIn(text)
    }
}