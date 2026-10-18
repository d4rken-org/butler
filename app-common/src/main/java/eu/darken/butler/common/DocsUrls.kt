package eu.darken.butler.common

import java.util.Locale

internal object DocsUrls {

    private const val ORIGIN = "https://butler.darken.eu"

    // Pinned to docs/_data/languages.json by DocsUrlsDataTest.
    val LANGUAGE_PATHS = mapOf("de" to "de")

    // Pinned to tooling/docs/english_only.json by DocsUrlsDataTest.
    val ENGLISH_ONLY = setOf("changelog", "privacy", "security")

    fun localize(address: String, locales: List<Locale>): String {
        if (!address.startsWith(ORIGIN)) return address
        val rest = address.removePrefix(ORIGIN)
        if (rest.isNotEmpty() && rest[0] !in "/?#") return address

        val pathEnd = rest.indexOfFirst { it == '?' || it == '#' }.takeIf { it >= 0 } ?: rest.length
        val path = rest.substring(0, pathEnd).ifEmpty { "/" }
        val suffix = rest.substring(pathEnd)

        val firstSegment = path.removePrefix("/").substringBefore('/')
        if (firstSegment in ENGLISH_ONLY || firstSegment in LANGUAGE_PATHS.values) return address

        val language = locales.firstOrNull { it.language == "en" || it.language in LANGUAGE_PATHS } ?: return address
        val languagePath = LANGUAGE_PATHS[language.language] ?: return address
        return "$ORIGIN/$languagePath$path$suffix"
    }
}
