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
     * Match a string against a pattern (case-insensitive)
     * In simple mode: substring matching
     * In advanced mode: full regex support
     */
    fun matches(text: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return text.isEmpty()

        return try {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            regex.containsMatchIn(text)
        } catch (e: Exception) {
            // Invalid regex pattern - return false as safe fallback
            false
        }
    }
}