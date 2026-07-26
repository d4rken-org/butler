package eu.darken.butler.apps.ui.details.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * Styles every case-insensitive occurrence of [query] in this string.
 *
 * The caller supplies the [style] so theming stays in the composable. A blank query returns the
 * plain string, which also guarantees the scan below advances by at least one character.
 */
internal fun String.highlightMatches(query: String, style: SpanStyle): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(this)
    val source = this
    return buildAnnotatedString {
        append(source)
        var startIndex = 0
        while (startIndex <= source.length) {
            val match = source.indexOf(query, startIndex, ignoreCase = true)
            if (match == -1) break
            addStyle(style, match, match + query.length)
            startIndex = match + query.length
        }
    }
}
