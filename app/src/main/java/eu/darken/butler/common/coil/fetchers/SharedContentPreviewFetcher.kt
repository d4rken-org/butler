package eu.darken.butler.common.coil.fetchers

import android.content.Context
import android.os.ParcelFileDescriptor
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coil.targetEdgePx
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.preview.PdfPreviewGenerator
import eu.darken.butler.common.pkgs.ApkIconExtractor
import eu.darken.butler.common.previews.SharedContentPreview
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Produces previews for shared `content://` items that Coil's built-in fetchers can't: an APK's icon
 * (no-copy, API 30+) and a PDF's first page. Images/videos are handled by Coil's built-in
 * ContentUriFetcher + VideoFrameDecoder and never reach here.
 *
 * Returns null (→ Coil error → the caller's placeholder) for unsupported types, non-seekable providers,
 * or any extraction failure. Never copies the source.
 */
class SharedContentPreviewFetcher(
    private val context: Context,
    private val apkIconExtractor: ApkIconExtractor,
    private val pdfPreviewGenerator: PdfPreviewGenerator,
    private val dispatcherProvider: DispatcherProvider,
    private val data: SharedContentPreview,
    private val options: Options,
) : Fetcher {

    private enum class Kind { APK, PDF }

    override suspend fun fetch(): FetchResult? {
        val kind = classify() ?: return null
        val targetPx = options.targetEdgePx()

        val bitmap = when (kind) {
            // ApkIconExtractor does NOT own the fd -> close it ourselves.
            Kind.APK -> openReadPfd()?.use { apkIconExtractor.extract(it, targetPx) }
            // PdfPreviewGenerator takes ownership of the fd and closes it.
            Kind.PDF -> openReadPfd()?.let { pdfPreviewGenerator.renderFirstPage(it, targetPx) }
        } ?: return null

        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    private fun classify(): Kind? {
        val mime = data.mimeType?.lowercase()
        val ext = data.displayName?.substringAfterLast('.', "")?.lowercase()
        return when {
            mime == "application/vnd.android.package-archive" || ext == "apk" -> Kind.APK
            mime == "application/pdf" || ext == "pdf" -> Kind.PDF
            else -> null
        }
    }

    // Opening/statting a content:// descriptor can block (cloud/document providers), so keep it off
    // Coil's fetch dispatcher.
    private suspend fun openReadPfd(): ParcelFileDescriptor? = withContext(dispatcherProvider.IO) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(data.uri, "r") ?: return@withContext null
            if (pfd.statSize < 0) {
                runCatching { pfd.close() } // non-seekable (pipe-backed provider) -> can't parse
                null
            } else {
                pfd
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "openFileDescriptor failed for ${data.uri}: ${e.asLog()}" }
            null
        }
    }

    class Factory @Inject constructor(
        @ApplicationContext private val context: Context,
        private val apkIconExtractor: ApkIconExtractor,
        private val pdfPreviewGenerator: PdfPreviewGenerator,
        private val dispatcherProvider: DispatcherProvider,
    ) : Fetcher.Factory<SharedContentPreview> {

        override fun create(
            data: SharedContentPreview,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = SharedContentPreviewFetcher(
            context,
            apkIconExtractor,
            pdfPreviewGenerator,
            dispatcherProvider,
            data,
            options,
        )
    }

    companion object {
        private val TAG = logTag("Coil", "Fetcher", "SharedContent")
    }
}
