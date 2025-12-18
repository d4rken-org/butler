package eu.darken.butler.searcher.ui.search.util

/**
 * Intelligently truncates a line to show a window around the matched content.
 * Adds ellipsis before/after when the line is too long.
 */
fun getEllipsizedMatchLine(
    line: String,
    startIndex: Int,
    endIndex: Int,
    maxLength: Int = 100,
): String {
    // Validate indices are within bounds (defensive check for data consistency issues)
    if (startIndex < 0 || endIndex > line.length || startIndex >= endIndex) {
        // Invalid indices, return truncated line from start
        return if (line.length <= maxLength) {
            line
        } else {
            line.take(maxLength) + "..."
        }
    }

    if (line.length <= maxLength) return line

    val matchLength = endIndex - startIndex
    // Reserve 6 chars for "..." on both sides
    val availableSpace = maxLength - matchLength - 6
    if (availableSpace < 0) {
        // Match itself is too long, just show it with minimal context
        return "...${line.substring(startIndex, endIndex)}..."
    }

    val windowSize = availableSpace / 2

    // Calculate how much context we can show before and after
    val contextBefore = startIndex.coerceAtMost(windowSize)
    val contextAfter = (line.length - endIndex).coerceAtMost(windowSize)

    val showStart = startIndex - contextBefore
    val showEnd = endIndex + contextAfter

    val prefix = if (showStart > 0) "..." else ""
    val suffix = if (showEnd < line.length) "..." else ""

    return prefix + line.substring(showStart, showEnd) + suffix
}
