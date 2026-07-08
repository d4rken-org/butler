package eu.darken.butler.common.coil.fetchers

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.preview.PreviewBudget
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Renders the first page of a PDF from a seekable [ParcelFileDescriptor] into a bitmap bounded by
 * [PreviewBudget], WITHOUT copying the file. Returns null on empty/encrypted/corrupt PDFs or any failure.
 *
 * Ownership: [renderFirstPage] takes ownership of the [pfd] and always closes it ([PdfRenderer] owns the
 * descriptor once constructed; every early-return path closes it explicitly).
 */
@Singleton
class PdfPreviewGenerator @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
) {

    suspend fun renderFirstPage(pfd: ParcelFileDescriptor, targetPx: Int): Bitmap? = try {
        withContext(dispatcherProvider.IO) { render(pfd, targetPx) }
    } catch (e: CancellationException) {
        // render() only runs post-dispatch; if cancelled at the boundary the descriptor is still open.
        runCatching { pfd.close() }
        throw e
    } catch (e: Throwable) {
        // render() closes the descriptor on its own paths; close defensively in case a Throwable
        // escaped before ownership was established (e.g. dispatch). Double-close is swallowed.
        log(TAG, WARN) { "renderFirstPage failed: ${e.asLog()}" }
        runCatching { pfd.close() }
        null
    }

    private fun render(pfd: ParcelFileDescriptor, targetPx: Int): Bitmap? {
        if (pfd.statSize < 0) {
            runCatching { pfd.close() }
            return null
        }
        val renderer = try {
            PdfRenderer(pfd) // takes ownership of the fd
        } catch (e: Throwable) {
            // Encrypted, corrupt, not a real PDF, or OOM -> close the (not-yet-owned) fd and fall back.
            log(TAG, WARN) { "PdfRenderer init failed: ${e.asLog()}" }
            runCatching { pfd.close() }
            return null
        }
        return renderer.use { r ->
            if (r.pageCount <= 0) return@use null
            r.openPage(0).use { page ->
                val pw = page.width
                val ph = page.height
                if (pw <= 0 || ph <= 0) return@use null

                val edge = PreviewBudget.resolveEdge(targetPx)
                val scale = minOf(edge.toFloat() / pw, edge.toFloat() / ph, 1f)
                val outW = (pw * scale).toInt().coerceIn(1, PreviewBudget.MAX_DIM)
                val outH = (ph * scale).toInt().coerceIn(1, PreviewBudget.MAX_DIM)

                val bmp = try {
                    Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                } catch (e: OutOfMemoryError) {
                    log(TAG, WARN) { "PDF bitmap OOM at ${outW}x$outH" }
                    return@use null
                }
                bmp.eraseColor(Color.WHITE) // PDF pages assume white paper; transparent bg would look wrong
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    companion object {
        private val TAG = logTag("Coil", "Fetcher", "Pdf", "Generator")
    }
}
