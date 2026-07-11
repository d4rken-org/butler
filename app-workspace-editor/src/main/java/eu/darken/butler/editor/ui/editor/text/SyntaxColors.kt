package eu.darken.butler.editor.ui.editor.text

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import eu.darken.butler.editor.core.syntax.Token
import eu.darken.butler.editor.core.syntax.TokenType

/**
 * Token colors derived from ColorScheme roles (onScrim precedent) so all color families,
 * light/dark modes, and contrast levels stay consistent without hand-picked palettes.
 * EMPHASIS shares NUMBER's role: the two never co-occur within one language.
 */
internal fun TokenType.syntaxColor(colorScheme: ColorScheme): Color = when (this) {
    TokenType.KEYWORD -> colorScheme.primary
    TokenType.STRING -> colorScheme.tertiary
    TokenType.NUMBER -> colorScheme.secondary
    TokenType.COMMENT -> colorScheme.onSurfaceVariant
    TokenType.EMPHASIS -> colorScheme.secondary
}

/**
 * Applies [tokens] (RAW offsets, sorted, non-overlapping) as color spans over the tab-expanded
 * [displayText]. Offsets are remapped raw -> expanded in ONE forward pass across the whole line
 * (not per token boundary - that would be O(tokens * length) on token-dense lines). Control-char
 * substitution is 1:1, so only tabs shift the mapping. All bounds are clamped: a stale token
 * frame during async highlight catch-up renders clipped, never crashes.
 */
internal fun buildHighlightedText(
    displayText: String,
    rawLineContent: String,
    tokens: List<Token>,
    tabSize: Int,
    colorScheme: ColorScheme,
): AnnotatedString = buildAnnotatedString {
    append(displayText)
    val ts = tabSize.coerceAtLeast(1)
    var raw = 0
    var expanded = 0
    tokens.forEach { token ->
        val clampedStart = token.start.coerceIn(raw, rawLineContent.length)
        while (raw < clampedStart) {
            expanded += if (rawLineContent[raw] == '\t') ts else 1
            raw++
        }
        val expandedStart = expanded
        val clampedEnd = token.end.coerceIn(raw, rawLineContent.length)
        while (raw < clampedEnd) {
            expanded += if (rawLineContent[raw] == '\t') ts else 1
            raw++
        }
        val expandedEnd = expanded.coerceAtMost(displayText.length)
        if (expandedStart < expandedEnd) {
            addStyle(SpanStyle(color = token.type.syntaxColor(colorScheme)), expandedStart, expandedEnd)
        }
    }
}
