package eu.darken.butler.viewer.core

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.preview.PdfPreviewGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads what the viewer needs from a PDF: the page count for the hint, and the first page as a
 * full-screen bitmap. Everything it cannot serve resolves to null - no descriptor (root/ADB-routed or
 * archive paths), encrypted, corrupt, or a document without pages - and the page falls back to the
 * unsupported placeholder.
 */
@Singleton
class PdfPreviewLoader @Inject constructor(
    private val gatewaySwitch: GatewaySwitch,
    private val pdfPreviewGenerator: PdfPreviewGenerator,
) {

    /**
     * A native page render ignores cancellation, so retry spam would otherwise stack full-screen
     * bitmaps that are already unwanted by the time they exist.
     */
    private val renderMutex = Mutex()

    suspend fun pageCount(path: APath<*>): Int? = openPfd(path)?.let { pdfPreviewGenerator.pageCount(it) }

    suspend fun firstPage(path: APath<*>): Bitmap? = renderMutex.withLock {
        openPfd(path)?.let {
            pdfPreviewGenerator.renderFirstPage(
                pfd = it,
                targetPx = PDF_PREVIEW_EDGE,
                maxPx = PDF_PREVIEW_EDGE,
                allowUpscale = true,
            )
        }
    }

    /** The generator takes ownership of the descriptor, so this hands it over unclosed. */
    private suspend fun openPfd(path: APath<*>): ParcelFileDescriptor? = try {
        gatewaySwitch.openReadPFD(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log(TAG, WARN) { "No descriptor for $path: ${e.asLog()}" }
        null
    }

    companion object {
        private val TAG = logTag("Viewer", "PdfPreviewLoader")
        private const val PDF_PREVIEW_EDGE = 2048
    }
}
