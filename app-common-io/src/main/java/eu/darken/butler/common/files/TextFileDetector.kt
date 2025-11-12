package eu.darken.butler.common.files

object TextFileDetector {
    private val TEXT_FILE_EXTENSIONS = setOf(
        // Plain text
        "txt", "md", "markdown",

        // Configuration
        "json", "xml", "yml", "yaml", "toml", "ini", "cfg", "conf", "config",

        // Web
        "html", "htm", "css", "js", "jsx", "ts", "tsx",

        // Programming languages
        "kt", "kts", "java", "py", "c", "cpp", "cc", "cxx", "h", "hpp",
        "cs", "php", "rb", "go", "rs", "swift", "m", "mm",

        // Shell scripts
        "sh", "bash", "zsh", "fish",

        // Build files
        "gradle", "cmake", "make", "mk",

        // Documentation
        "log", "rst", "adoc", "tex",

        // Data
        "csv", "tsv",
    )

    /**
     * Checks if a file is a text file based on its MIME type
     */
    fun isTextFile(mimeInfo: MimeInfo): Boolean {
        return mimeInfo.isText
    }

    /**
     * Checks if a file is a text file based on its file extension
     */
    fun isTextFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in TEXT_FILE_EXTENSIONS
    }

    /**
     * Checks if a file is a text file based on its path
     */
    fun isTextFile(path: APath<*>): Boolean {
        return isTextFile(path.name)
    }
}
