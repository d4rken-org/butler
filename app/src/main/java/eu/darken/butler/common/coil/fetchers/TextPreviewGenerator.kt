package eu.darken.butler.common.coil.fetchers

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import coil3.request.Options
import coil3.size.Dimension
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.MimeInfo
import eu.darken.butler.common.files.preview.PreviewBudget
import eu.darken.butler.common.theming.ThemeColor
import eu.darken.butler.common.theming.ThemeColorProvider
import eu.darken.butler.common.theming.ThemeMode
import eu.darken.butler.common.theming.ThemeStyle
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.themeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val generalSettings: GeneralSettings,
) {

    private val themeColors: StateFlow<Pair<Int, Int>> = generalSettings.themeState
        .map { themeState ->
            val isDark = when (themeState.mode) {
                ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            val colorScheme = if (isDark) {
                ThemeColorProvider.getDarkColorScheme(themeState.color, themeState.style)
            } else {
                ThemeColorProvider.getLightColorScheme(themeState.color, themeState.style)
            }

            colorScheme.background.toArgb() to colorScheme.onBackground.toArgb()
        }
        .stateIn(
            scope = CoroutineScope(Dispatchers.Default),
            started = SharingStarted.Eagerly,
            initialValue = run {
                val colorScheme = ThemeColorProvider.getDarkColorScheme(
                    ThemeColor.GREEN,
                    ThemeStyle.DEFAULT
                )
                colorScheme.background.toArgb() to colorScheme.onBackground.toArgb()
            }
        )

    /** [MimeInfo] owns the table, so a thumbnail never disagrees with the rest of the app. */
    fun isTextPreviewable(mimeType: String): Boolean = MimeInfo(mimeType).isText

    companion object {
        private val TAG = logTag("Coil", "Fetcher", "Path", "Text")
    }

    /**
     * Generate a bitmap preview of a text file.
     *
     * @param lookup The file to preview
     * @param options Coil request options containing target size (optional)
     * @return Bitmap containing rendered text preview
     */
    suspend fun generate(
        lookup: APathLookup<*>,
        options: Options? = null
    ): Bitmap? {
        log(TAG) { "Generating text preview for: ${lookup.path}" }

        // Extract dimensions from Coil options if available
        val (width, height) = extractDimensions(options)
        log(TAG) { "Target dimensions: ${width}×${height}px" }

        val textContent = readTextContent(lookup) ?: return null

        // Get current theme colors from cache
        val (backgroundColor, textColor) = themeColors.value
        return renderTextToBitmap(textContent, width, height, backgroundColor, textColor)
    }

    private fun extractDimensions(options: Options?): Pair<Int, Int> {
        if (options == null) return 512 to 512

        // Clamp via PreviewBudget so a Size.ORIGINAL / oversized request can't allocate an unbounded
        // bitmap (undefined stays at the previous 512 default).
        val width = (options.size.width as? Dimension.Pixels)?.px ?: 0
        val height = (options.size.height as? Dimension.Pixels)?.px ?: 0
        return PreviewBudget.resolveEdge(width, default = 512) to PreviewBudget.resolveEdge(height, default = 512)
    }

    private suspend fun readTextContent(lookup: APathLookup<*>, maxBytes: Long = 4096L): String? = try {
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
                val maxLines = 50
                if (lines.size > maxLines) {
                    lines.take(maxLines).joinToString("\n") + "\n..."
                } else {
                    text
                }
            }
        }
    } catch (e: Exception) {
        log(TAG, WARN) { "Failed to read text content: ${e.asLog()}" }
        null
    }

    private fun renderTextToBitmap(
        text: String,
        width: Int,
        height: Int,
        backgroundColor: Int,
        textColor: Int
    ): Bitmap {
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)

        // Draw background with theme color
        canvas.drawColor(backgroundColor)

        // Setup text paint with theme color
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            color = textColor
            textSize = 6 * context.resources.displayMetrics.scaledDensity
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        // Calculate usable area with padding
        val paddingPx = 2 * context.resources.displayMetrics.density
        val textWidth = width - (paddingPx * 2).toInt()
        val textHeight = height - (paddingPx * 2).toInt()

        // Create static layout for multiline text
        val staticLayout = StaticLayout.Builder.obtain(
            text,
            0,
            text.length.coerceAtMost(4000),
            textPaint,
            textWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(true)
            .build()

        // Draw text with padding offset
        canvas.withTranslation(paddingPx, paddingPx) {
            // Clip to prevent text overflow
            clipRect(0f, 0f, textWidth.toFloat(), textHeight.toFloat())

            staticLayout.draw(this)
        }

        log(TAG) { "Generated text preview bitmap: ${width}×${height}px, ${text.length} chars" }

        return bitmap
    }

}