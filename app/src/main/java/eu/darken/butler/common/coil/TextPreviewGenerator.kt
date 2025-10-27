package eu.darken.butler.common.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import okio.buffer
import okio.use
import javax.inject.Inject

/**
 * Generates bitmap previews of text files for display in file explorers.
 * Creates a rendered preview showing the first few lines of text content.
 */
class TextPreviewGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
) {

    /**
     * Generate a bitmap preview of a text file.
     *
     * @param lookup The file to preview
     * @param maxBytes Maximum bytes to read from file (default 4KB)
     * @param width Bitmap width in pixels (default 512)
     * @param height Bitmap height in pixels (default 512)
     * @return Bitmap containing rendered text preview
     */
    suspend fun generate(
        lookup: APathLookup<*>,
        maxBytes: Long = MAX_BYTES_TO_READ,
        width: Int = PREVIEW_WIDTH,
        height: Int = PREVIEW_HEIGHT
    ): Bitmap {
        log(TAG) { "Generating text preview for: ${lookup.path}" }

        val textContent = readTextContent(lookup, maxBytes)
        return renderTextToBitmap(textContent, width, height)
    }

    private suspend fun readTextContent(lookup: APathLookup<*>, maxBytes: Long): String {
        return try {
            gatewaySwitch.file(lookup.lookedUp, readWrite = false).use { handle ->
                handle.source().buffer().use { source ->
                    val bytes = if (lookup.size != null && lookup.size!! < maxBytes) {
                        // Read entire file if smaller than maxBytes
                        source.readByteArray()
                    } else {
                        // Read only first maxBytes
                        val buffer = ByteArray(maxBytes.toInt())
                        val bytesRead = source.read(buffer)
                        if (bytesRead > 0) buffer.copyOf(bytesRead) else byteArrayOf()
                    }

                    // Convert to string using UTF-8
                    val text = String(bytes, Charsets.UTF_8)

                    // Limit to maximum number of lines
                    val lines = text.lines()
                    if (lines.size > MAX_LINES) {
                        lines.take(MAX_LINES).joinToString("\n") + "\n..."
                    } else {
                        text
                    }
                }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to read text content: ${e.asLog()}" }
            "Error reading file:\n${e.message ?: "Unknown error"}"
        }
    }

    private fun renderTextToBitmap(text: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw dark background
        canvas.drawColor(BACKGROUND_COLOR)

        // Setup text paint
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            color = TEXT_COLOR
            textSize = TEXT_SIZE_SP * context.resources.displayMetrics.scaledDensity
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        // Calculate usable area with padding
        val paddingPx = PADDING_DP * context.resources.displayMetrics.density
        val textWidth = width - (paddingPx * 2).toInt()
        val textHeight = height - (paddingPx * 2).toInt()

        // Create static layout for multiline text
        val staticLayout = StaticLayout.Builder.obtain(
            text,
            0,
            text.length.coerceAtMost(MAX_CHARS),
            textPaint,
            textWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            .setIncludePad(true)
            .build()

        // Draw text with padding offset
        canvas.save()
        canvas.translate(paddingPx, paddingPx)

        // Clip to prevent text overflow
        canvas.clipRect(0f, 0f, textWidth.toFloat(), textHeight.toFloat())

        staticLayout.draw(canvas)
        canvas.restore()

        log(TAG) { "Generated text preview bitmap: ${width}×${height}px, ${text.length} chars" }

        return bitmap
    }

    companion object {
        private val TAG = logTag("Coil", "TextPreviewGenerator")

        // Configuration constants
        private const val MAX_BYTES_TO_READ = 4096L // 4KB
        private const val MAX_LINES = 50
        private const val MAX_CHARS = 4000
        private const val PREVIEW_WIDTH = 512
        private const val PREVIEW_HEIGHT = 512
        private const val TEXT_SIZE_SP = 8f
        private const val PADDING_DP = 12f
        private const val LINE_SPACING_MULTIPLIER = 1.2f

        // Colors (dark theme to match grid overlay)
        private val BACKGROUND_COLOR = Color.parseColor("#1E1E1E") // Dark gray
        private val TEXT_COLOR = Color.parseColor("#E0E0E0") // Light gray
    }
}
