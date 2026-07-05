package eu.darken.butler.editor.core.engine

/**
 * Line ending style detected in text content.
 */
enum class LineEnding {
    LF,      // \n (Unix/Linux/macOS)
    CRLF,    // \r\n (Windows)
    CR,      // \r (old Mac OS) - rare
    MIXED    // Inconsistent line endings
}
