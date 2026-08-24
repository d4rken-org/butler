package eu.darken.butler.viewer.core

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.Build
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.preview.PreviewBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of an [ImageProbe] run. Three-way on purpose: "this format has no raster dimensions"
 * (SVG and friends) is an expected answer, not a failure, and must not be reported as one.
 */
sealed interface ProbeResult {
    data class Probed(
        val width: Int,
        val height: Int,
        val format: String,
    ) : ProbeResult

    data object NoRasterDimensions : ProbeResult

    data class ProbeFailed(val error: Throwable) : ProbeResult
}

/**
 * Reads image dimensions via [BitmapFactory.Options.inJustDecodeBounds] over a gateway-routed
 * stream, then checks that the file is structurally whole and not just a readable header: first a
 * region decode (see [verifyStructure]), then a full sub-sampled decode where one is possible (see
 * [verifyDecodable]).
 */
@Singleton
class ImageProbe @Inject constructor(
    private val contentReader: ViewerContentReader,
    private val dispatcherProvider: DispatcherProvider,
) {

    suspend fun probe(source: ViewerSource): ProbeResult = withContext(dispatcherProvider.IO) {
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // The reader keeps the gateway lease open around the read; a returned stream would have
            // outlived it.
            contentReader.readInput(source) { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val width = options.outWidth
            val height = options.outHeight
            if (width <= 0 || height <= 0) {
                log(TAG) { "No raster dimensions for $source" }
                ProbeResult.NoRasterDimensions
            } else {
                // The source's declared type, not its name: shared content routinely arrives
                // without an extension, and fromFileName would call a JPEG an octet-stream.
                val format = options.outMimeType ?: source.mime.rawType
                val defect = verifyStructure(source, format, width, height) ?: verifyDecodable(source)
                when (defect) {
                    null -> ProbeResult.Probed(width = width, height = height, format = format)
                    else -> ProbeResult.ProbeFailed(defect)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "probe($source) failed: ${e.asLog()}" }
            ProbeResult.ProbeFailed(e)
        }
    }

    /**
     * A readable header says nothing about the rest of the file. A truncated download or an
     * interrupted copy still reports dimensions and would then be announced as an image, only for
     * the tile decoder to fail behind the scenes and leave a permanently blank canvas.
     *
     * So the same decoder telephoto uses ([BitmapRegionDecoder], see [SubSamplableFormats]) gets a
     * look at the file, and then
     * decodes one heavily sub-sampled region from the bottom edge - the part a truncated file is
     * missing, and which a header-only check would never touch.
     *
     * Returns null when the file is sound (or when the format is none of this check's business).
     */
    private suspend fun verifyStructure(
        source: ViewerSource,
        format: String,
        width: Int,
        height: Int,
    ): Throwable? {
        if (!SubSamplableFormats.supports(format)) {
            log(TAG) { "Skipping structure check, $format has no decodable regions ($source)" }
            return null
        }
        return try {
            // A fresh stream: the probe's is spent and neither gateway nor provider streams rewind.
            val region = contentReader.readInput(source) { stream ->
                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(stream, false)
                try {
                    decoder?.decodeRegion(tailRegion(width, height), tailOptions())
                } finally {
                    decoder?.recycle()
                }
            }
            if (region == null) {
                log(TAG, WARN) { "Structure check found no decodable tail region in $source" }
                ViewerUndecodableImageException(source.displayName)
            } else {
                region.recycle()
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(TAG, WARN) { "Structure check failed for $source: ${e.asLog()}" }
            ViewerUndecodableImageException(source.displayName)
        }
    }

    /**
     * Second, stricter look at the same file. [BitmapRegionDecoder] above only asks for one tile
     * from the bottom edge, and a truncated file can still hand out something for it - the region
     * lands in the part that is missing, and some decoders answer with padding instead of an error.
     *
     * So the file also goes through [ImageDecoder] with an [ImageDecoder.OnPartialImageListener]
     * that refuses partial results, which turns "the input stopped mid-image" into a thrown
     * [ImageDecoder.DecodeException] instead of a silently half-grey bitmap. The two checks are
     * complementary: the region decode covers every API level and catches structurally broken
     * files, this one catches truncation from API 28 on.
     *
     * Deliberately best-effort - every uncertainty resolves to "sound":
     * - [ImageDecoder] starts at API 28. On 26/27 the region check stands alone.
     * - It needs a seekable source. A readable local file is the common case and costs nothing
     *   extra; otherwise [GatewaySwitch.openReadPFD] may still provide a descriptor (API 29+, where
     *   the decoder's descriptor entry point begins), including for root/ADB-routed local paths,
     *   which it serves through a proxy descriptor. Archive entries have neither and are skipped:
     *   buying a source there would mean buffering the whole file, throwing away the sub-sampling
     *   this module exists to provide, on our rarest paths.
     * - Only truncated or malformed data counts as a defect. A failing read, a format the decoder
     *   does not implement, or anything else it throws leaves the file alone.
     *
     * Returns null when the file is sound or the check could not run.
     */
    private suspend fun verifyDecodable(source: ViewerSource): Throwable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            log(TAG) { "Skipping decode check, ImageDecoder needs API 28 ($source)" }
            return null
        }
        // Owns whatever the resolve obtains from the moment it exists, so a cancellation that
        // discards the resolve's result is never the moment nobody holds an open descriptor.
        val pending = PendingInput()
        return try {
            when (ImageDecodeCheck.inspect(resolveDecodeInput(source, pending))) {
                ImageDecodeCheck.Verdict.DAMAGED -> {
                    log(TAG, WARN) { "Decode check rejected $source" }
                    ViewerUndecodableImageException(source.displayName)
                }

                ImageDecodeCheck.Verdict.SOUND -> null

                ImageDecodeCheck.Verdict.UNKNOWN -> {
                    log(TAG) { "Decode check had nothing to say about $source" }
                    null
                }
            }
        } finally {
            pending.close()
        }
    }

    /** The decoder reads the file itself, so it needs a source of its own - or none at all. */
    internal suspend fun resolveDecodeInput(
        source: ViewerSource,
        pending: PendingInput = PendingInput(),
    ): ImageDecodeInput {
        // The common case: no descriptor, no lease, no copy.
        contentReader.localFileOrNull(source)?.let {
            return ImageDecodeInput.LocalFile(it).also { input -> pending.adopt(input) }
        }

        // NonCancellable, and the hand-over happens inside it: the descriptor is created by a
        // suspending call, and a cancellation arriving while that call returns would otherwise
        // discard the only reference to an open fd. Enough of those and the process runs out.
        return withContext(NonCancellable) {
            val pfd = try {
                // Returns null for everything it cannot serve, including non-seekable providers.
                contentReader.openReadPfd(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "No descriptor for $source: ${e.asLog()}" }
                null
            }
            val input = pfd?.let { ImageDecodeInput.Descriptor(it) } ?: ImageDecodeInput.None
            pending.adopt(input)
            input
        }
    }

    private fun tailRegion(width: Int, height: Int): Rect {
        val edge = PreviewBudget.resolveEdge(TAIL_REGION_EDGE, max = PreviewBudget.MAX_ICON_DIM)
        val regionWidth = minOf(width, edge)
        val regionHeight = minOf(height, edge)
        return Rect(0, height - regionHeight, regionWidth, height)
    }

    private fun tailOptions() = BitmapFactory.Options().apply { inSampleSize = TAIL_SAMPLE_SIZE }

    companion object {
        private val TAG = logTag("Viewer", "ImageProbe")

        /** This runs on every image open, so the region stays inside the shared preview budget. */
        private const val TAIL_REGION_EDGE = 64
        private const val TAIL_SAMPLE_SIZE = 4
    }
}
