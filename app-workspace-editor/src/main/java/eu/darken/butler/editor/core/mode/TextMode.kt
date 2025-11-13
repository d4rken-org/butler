package eu.darken.butler.editor.core.mode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.darken.butler.common.debug.logging.Logging.Priority.DEBUG
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.ChunkManager
import eu.darken.butler.editor.core.engine.ChunkRepository
import eu.darken.butler.editor.core.engine.ChunkedTextBuffer
import eu.darken.butler.editor.core.engine.EditorBuffer
import eu.darken.butler.editor.core.engine.EditorChunk
import eu.darken.butler.editor.core.engine.LineEnding
import eu.darken.butler.editor.core.sources.EditorDataSource
import java.nio.charset.Charset

/**
 * Text editing mode.
 *
 * Decodes file content as UTF-8 text and provides line-based editing operations.
 * Calculates line counts and detects line ending styles.
 */
class TextMode(
    private val encoding: Charset = Charsets.UTF_8
) : EditorMode {

    override val type = EditorModeType.TEXT

    override val capabilities = EditorCapabilities(
        canEdit = true,
        canSearch = true,
        canUndo = true,
        canGoToLine = true,
        canGoToOffset = true,
        canShowLineNumbers = true
    )

    override suspend fun loadChunk(
        dataSource: EditorDataSource,
        offset: Long,
        size: Long
    ): EditorChunk {
        log(TAG, DEBUG) { "loadChunk(offset=$offset, size=$size)" }

        // Read content from data source as ByteArray
        val bytes = dataSource.readChunk(offset, size)

        // Decode bytes to string using the specified encoding (default UTF-8)
        val content = bytes.toString(encoding)

        // Calculate line count
        val lineCount = calculateLineCount(content)

        // Detect line ending style
        val lineEnding = detectLineEnding(content)

        log(TAG, DEBUG) { "Loaded text chunk: ${content.length} chars, $lineCount lines, $lineEnding ending" }

        return EditorChunk.Text(
            offset = offset,
            content = content,
            size = content.length.toLong(),
            lineCount = lineCount,
            lineEnding = lineEnding,
            isDirty = false
        )
    }

    override suspend fun saveChunk(
        dataSource: EditorDataSource,
        chunk: EditorChunk
    ) {
        require(chunk is EditorChunk.Text) {
            "TextMode can only save Text chunks, got ${chunk::class.simpleName}"
        }

        log(TAG, DEBUG) { "saveChunk(offset=${chunk.offset}, size=${chunk.size})" }

        // Convert text content to bytes using the specified encoding (default UTF-8)
        val bytes = chunk.content.toByteArray(encoding)

        // Write bytes to data source
        dataSource.writeChunk(chunk.offset, bytes)

        log(TAG, DEBUG) { "Saved ${bytes.size} bytes at offset ${chunk.offset}" }
    }

    override fun createBuffer(chunkManager: ChunkManager): EditorBuffer {
        // TextMode uses ChunkedTextBuffer
        // Note: We'll need to update ChunkedTextBuffer constructor in Phase 1.4
        // For now, return a placeholder that will be properly integrated
        TODO("ChunkedTextBuffer needs to be updated to implement EditorBuffer interface")
    }

    @Composable
    override fun RenderEditor(buffer: EditorBuffer, modifier: Modifier) {
        // Will be implemented in Phase 4
        TODO("Text editor UI will be implemented in Phase 4")
    }

    /**
     * Calculate the number of lines in the content.
     *
     * Rules:
     * - Empty content = 0 lines
     * - Content without newlines = 1 line
     * - Count newlines, add 1 if content doesn't end with newline
     */
    private fun calculateLineCount(content: String): Int {
        if (content.isEmpty()) return 0

        val newlineCount = content.count { it == '\n' }

        // If content ends with newline, line count = newline count
        // Otherwise, line count = newline count + 1
        return if (content.endsWith('\n')) {
            newlineCount
        } else {
            newlineCount + 1
        }
    }

    /**
     * Detect the line ending style used in the content.
     *
     * Priority:
     * 1. MIXED if multiple styles detected
     * 2. CRLF if \r\n found
     * 3. CR if \r found (without \n)
     * 4. LF if \n found (without \r)
     * 5. LF as default if no line endings
     */
    private fun detectLineEnding(content: String): LineEnding {
        val hasCRLF = content.contains("\r\n")
        val hasCR = content.contains('\r')
        val hasLF = content.contains('\n')

        return when {
            // Mixed: multiple ending styles present
            (hasCRLF && hasLF && content.indexOf('\n') != content.indexOf("\r\n") + 1) -> LineEnding.MIXED
            (hasCR && hasLF && !hasCRLF) -> LineEnding.MIXED

            // CRLF: Windows style
            hasCRLF -> LineEnding.CRLF

            // CR only: Old Mac style
            hasCR && !hasLF -> LineEnding.CR

            // LF only: Unix/Linux/macOS style
            hasLF -> LineEnding.LF

            // Default: No line endings (single line or empty)
            else -> LineEnding.LF
        }
    }

    companion object {
        private val TAG = logTag("Editor", "TextMode")
    }
}
