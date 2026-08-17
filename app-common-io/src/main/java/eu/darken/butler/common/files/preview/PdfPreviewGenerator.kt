package eu.darken.butler.common.files.preview

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Renders the first page of a PDF from a seekable [ParcelFileDescriptor] into a bitmap bounded by
 * [PreviewBudget], WITHOUT copying the file. Returns null on empty/encrypted/corrupt PDFs or any failure.
 *
 * Ownership: [renderFirstPage] and [pageCount] take ownership of the [pfd] and always close it
 * ([PdfRenderer] owns the descriptor once constructed; every early-return path closes it explicitly).
 */
@Singleton
class PdfPreviewGenerator @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
) {

    suspend fun renderFirstPage(
        pfd: ParcelFileDescriptor,
        targetPx: Int,
        maxPx: Int = PreviewBudget.MAX_DIM,
        allowUpscale: Boolean = false,
    ): Bitmap? = try {
        withContext(dispatcherProvider.IO) { render(pfd, targetPx, maxPx, allowUpscale) }
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

    /** Page count of the document, null when it cannot be opened or has no pages. */
    suspend fun pageCount(pfd: ParcelFileDescriptor): Int? = try {
        withContext(dispatcherProvider.IO) { countPages(pfd) }
    } catch (e: CancellationException) {
        // countPages() only runs post-dispatch; if cancelled at the boundary the descriptor is still open.
        runCatching { pfd.close() }
        throw e
    } catch (e: Throwable) {
        log(TAG, WARN) { "pageCount failed: ${e.asLog()}" }
        runCatching { pfd.close() }
        null
    }

    private fun countPages(pfd: ParcelFileDescriptor): Int? {
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
        return renderer.use { it.pageCount.takeIf { count -> count > 0 } }
    }

    private fun render(pfd: ParcelFileDescriptor, targetPx: Int, maxPx: Int, allowUpscale: Boolean): Bitmap? {
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
                val (outW, outH) = resolveRenderSize(
                    pageWidth = page.width,
                    pageHeight = page.height,
                    targetPx = targetPx,
                    maxPx = maxPx,
                    allowUpscale = allowUpscale,
                ) ?: return@use null

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
        private val TAG = logTag("Pdf", "PreviewGenerator")
    }
}

/**
 * Output size for a page of [pageWidth] x [pageHeight], fit into a square of the budgeted edge while
 * keeping the aspect ratio. Null for non-positive page dimensions.
 *
 * Thumbnails never upscale ([allowUpscale] false): a tiny page blown up is blurrier than the same page
 * at native size. A full-screen viewer preview does want the upscale, because telephoto has to have
 * pixels to zoom into.
 */
internal fun resolveRenderSize(
    pageWidth: Int,
    pageHeight: Int,
    targetPx: Int,
    maxPx: Int,
    allowUpscale: Boolean,
): Pair<Int, Int>? {
    if (pageWidth <= 0 || pageHeight <= 0) return null
    val edge = PreviewBudget.resolveEdge(targetPx, max = maxPx)
    val fit = minOf(edge.toFloat() / pageWidth, edge.toFloat() / pageHeight)
    val scale = if (allowUpscale) fit else minOf(fit, 1f)
    return (pageWidth * scale).toInt().coerceIn(1, maxPx) to (pageHeight * scale).toInt().coerceIn(1, maxPx)
}
