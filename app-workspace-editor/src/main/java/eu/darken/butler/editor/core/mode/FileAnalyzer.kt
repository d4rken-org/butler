package eu.darken.butler.editor.core.mode

import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.editor.core.sources.EditorDataSource
import javax.inject.Inject

/**
 * Analyzes files to determine the appropriate editor mode (text vs binary).
 *
 * Uses multiple heuristics:
 * - File extension detection
 * - Binary content detection (null bytes, non-printable characters)
 * - File size thresholds
 */
class FileAnalyzer @Inject constructor() {

    private val tag = logTag("Editor", "FileAnalyzer")

    /**
     * Analyze a file path and return the appropriate editor mode.
     *
     * @param filePath The path to analyze (null for in-memory content)
     * @param dataSource The data source to sample content from
     * @return EditorMode instance (TextMode or HexMode)
     */
    suspend fun analyzeFile(filePath: APath<*>?, dataSource: EditorDataSource): EditorMode {
        log(tag, DEBUG) { "Analyzing file: ${filePath?.name ?: "in-memory"}" }

        // Check file extension first (fast path)
        if (filePath != null) {
            val extensionMode = detectByExtension(filePath.name)
            if (extensionMode != null) {
                log(tag, DEBUG) { "Detected mode by extension: ${extensionMode.type}" }
                return extensionMode
            }
        }

        // Sample file content for binary detection
        val fileSize = dataSource.getSize()
        if (fileSize == 0L) {
            log(tag, DEBUG) { "Empty file - defaulting to TextMode" }
            return TextMode()
        }

        // Read sample from beginning of file (up to 8KB)
        val sampleSize = minOf(fileSize, SAMPLE_SIZE)
        val sample = try {
            dataSource.readChunk(0L, sampleSize)
        } catch (e: Exception) {
            log(tag, DEBUG) { "Failed to read sample, defaulting to TextMode: ${e.message}" }
            return TextMode()
        }

        // Detect binary content
        val isBinary = detectBinaryContent(sample)
        val mode = if (isBinary) HexMode() else TextMode()

        log(tag, DEBUG) { "Detected mode by content analysis: ${mode.type}" }
        return mode
    }

    /**
     * Detect editor mode based on file extension.
     *
     * @return EditorMode if extension is recognized, null otherwise
     */
    private fun detectByExtension(fileName: String): EditorMode? {
        val extension = fileName.substringAfterLast('.', "").lowercase()

        return when (extension) {
            // Text file extensions
            "txt", "log", "md", "markdown", "rst",
            "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
            "sh", "bash", "zsh", "fish",
            "py", "java", "kt", "kts", "js", "ts", "jsx", "tsx",
            "c", "cpp", "cc", "h", "hpp", "cs", "swift", "go", "rs",
            "html", "htm", "css", "scss", "sass", "less",
            "sql", "gradle", "properties", "gitignore", "dockerfile",
            "csv", "tsv" -> TextMode()

            // Binary file extensions
            "exe", "dll", "so", "dylib", "a", "o", "obj",
            "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
            "apk", "aar", "jar", "war",
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svg",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "mp3", "mp4", "avi", "mkv", "mov", "flv", "wmv",
            "dex", "class", "pyc" -> HexMode()

            // Unknown extension - return null for content-based detection
            else -> null
        }
    }

    /**
     * Detect if content is binary using multiple heuristics.
     *
     * @param sample Byte array sample from file
     * @return true if binary, false if text
     */
    private fun detectBinaryContent(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return false

        // Check for null bytes (strong indicator of binary)
        if (sample.contains(0x00.toByte())) {
            log(tag, DEBUG) { "Detected null bytes - binary content" }
            return true
        }

        // Count non-printable characters (excluding common whitespace)
        var nonPrintableCount = 0
        for (byte in sample) {
            val unsigned = byte.toInt() and 0xFF

            // Skip common text characters
            when (unsigned) {
                0x09, 0x0A, 0x0D -> continue  // Tab, LF, CR
                in 0x20..0x7E -> continue      // Printable ASCII
                else -> nonPrintableCount++
            }
        }

        // If more than 30% non-printable, consider it binary
        val nonPrintableRatio = nonPrintableCount.toFloat() / sample.size
        if (nonPrintableRatio > 0.30f) {
            log(tag, DEBUG) { "High non-printable ratio ($nonPrintableRatio) - binary content" }
            return true
        }

        log(tag, DEBUG) { "Low non-printable ratio ($nonPrintableRatio) - text content" }
        return false
    }

    companion object {
        private const val SAMPLE_SIZE = 8192L  // 8 KB sample
    }
}
