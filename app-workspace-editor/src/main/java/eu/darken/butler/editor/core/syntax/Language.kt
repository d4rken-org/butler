package eu.darken.butler.editor.core.syntax

enum class Language {
    JAVASCRIPT,
    BASH,
    MARKDOWN,
    JSON,
    ;

    companion object {
        private val EXTENSION_MAP = mapOf(
            "js" to JAVASCRIPT,
            "mjs" to JAVASCRIPT,
            "cjs" to JAVASCRIPT,
            "sh" to BASH,
            "bash" to BASH,
            "zsh" to BASH,
            "ksh" to BASH,
            "md" to MARKDOWN,
            "markdown" to MARKDOWN,
            "json" to JSON,
        )

        fun fromExtension(extension: String?): Language? =
            extension?.lowercase()?.let { EXTENSION_MAP[it] }

        fun fromFileName(fileName: String?): Language? {
            if (fileName == null) return null
            val dot = fileName.lastIndexOf('.')
            // Leading dot = dotfile, not an extension; trailing dot = no extension
            if (dot <= 0 || dot == fileName.length - 1) return null
            return fromExtension(fileName.substring(dot + 1))
        }
    }
}
