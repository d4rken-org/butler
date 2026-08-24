package eu.darken.butler.viewer.core

import android.content.res.AssetFileDescriptor
import android.graphics.ImageDecoder
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.preview.PreviewBudget
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicReference

/**
 * What the decode check was given to read a file with. [None] is an ordinary outcome, not an error:
 * archive entries and anything that fails to open have no seekable source, and the check then has
 * nothing to say about the file.
 */
internal sealed interface ImageDecodeInput {

    data object None : ImageDecodeInput

    data class LocalFile(val file: File) : ImageDecodeInput

    data class Descriptor(val pfd: ParcelFileDescriptor) : ImageDecodeInput

    /** Descriptors are handed over by the gateway, so we own closing them. */
    fun release() {
        if (this is Descriptor) runCatching { pfd.close() }
    }
}

/**
 * Single-slot owner for whatever the gateway hands over, for the window between obtaining it and
 * the check being done with it. A descriptor arrives from a suspending call, so a cancellation that
 * discards the result on the way out would otherwise drop the only reference to an open fd - and
 * enough of those exhaust the process limit. Adopting inside the acquiring scope means the holder
 * is already the owner by the time anything can be discarded.
 */
internal class PendingInput {

    private val held = AtomicReference<ImageDecodeInput?>(null)

    fun adopt(input: ImageDecodeInput) {
        held.getAndSet(input)?.release()
    }

    fun close() {
        held.getAndSet(null)?.release()
    }
}

/**
 * Reads a whole image through [ImageDecoder] into a bitmap capped at [MAX_EDGE] per side, purely to
 * find out whether the decoder reaches the end of it. The cap is absolute rather than a ratio of
 * the source: a proportional reduction of a header claiming 65535x65535 would still be enough heap
 * to take the process down, and refusing to allocate that is the whole point of this module.
 *
 * Lives in its own class so that [ImageDecoder] is never loaded on API 26/27, where it does not
 * exist.
 */
@RequiresApi(Build.VERSION_CODES.P)
internal object ImageDecodeCheck {

    enum class Verdict {
        SOUND,
        DAMAGED,

        /** Nothing to read, or the read itself went wrong - says nothing about the file. */
        UNKNOWN,
    }

    fun inspect(input: ImageDecodeInput): Verdict = try {
        when (val source = input.toSource()) {
            null -> Verdict.UNKNOWN
            else -> decode(source)
        }
    } catch (e: Throwable) {
        // Throwable, not Exception: a decode that runs out of heap raises an OutOfMemoryError, and
        // letting that escape would take the process down over a file we were only inspecting.
        classify(e)
    }

    /**
     * Turns whatever the decode threw into a verdict. Only the decoder complaining about the bytes
     * themselves counts against the file - everything else means the check could not form an
     * opinion, and an opinion it cannot form must never be a rejection.
     */
    internal fun classify(error: Throwable): Verdict = when {
        error is CancellationException -> throw error

        error is ImageDecoder.DecodeException -> when (error.error) {
            // The two the decoder raises about the bytes themselves.
            ImageDecoder.DecodeException.SOURCE_INCOMPLETE,
            ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA -> {
                log(TAG, WARN) { "Decoder rejected the data (error=${error.error}): ${error.asLog()}" }
                Verdict.DAMAGED
            }
            // SOURCE_EXCEPTION is a read that failed, which is not evidence against the file.
            else -> {
                log(TAG, WARN) { "Could not read the source: ${error.asLog()}" }
                Verdict.UNKNOWN
            }
        }

        error is OutOfMemoryError -> {
            // Running out of room says something about this device at this moment, not about the
            // image, so the file passes.
            log(TAG, WARN) { "Not enough memory to check the image: ${error.asLog()}" }
            Verdict.UNKNOWN
        }

        // A format ImageDecoder does not implement, a descriptor that died, a header we refuse to
        // allocate for, anything else: the check may only ever confirm damage, never invent it.
        else -> {
            log(TAG, WARN) { "Decode check inconclusive: ${error.asLog()}" }
            Verdict.UNKNOWN
        }
    }

    private fun decode(source: ImageDecoder.Source): Verdict {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // We are asking a yes/no question, so nothing near full size may be allocated. The
            // sample size follows the dimensions the header declares instead of a fixed ratio: a
            // ratio only bounds the result proportionally, and a proportion of 65535x65535 is
            // still far more heap than this module is allowed to want.
            val sampleSize = sampleSizeFor(info.size.width, info.size.height)
                ?: throw UncheckableImage("${info.size.width}x${info.size.height} is beyond what can be sampled safely")
            decoder.setTargetSampleSize(sampleSize)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Without this the decoder returns whatever it managed to read and stays quiet about
            // the rest - exactly the silent half-image this check exists to prevent.
            decoder.setOnPartialImageListener { false }
        }.recycle()
        return Verdict.SOUND
    }

    /**
     * Smallest power-of-two sample size that keeps both edges within [MAX_EDGE], so the check's
     * bitmap costs at most `MAX_EDGE * MAX_EDGE * 4` bytes no matter what the header claims.
     *
     * Null when even [MAX_SAMPLE_SIZE] would not get there, or when the header reports no usable
     * dimensions at all. There is no safe way to look at such a file, so the check does not look:
     * skipping is a pass, and a pass is always the right answer when we cannot tell.
     */
    internal fun sampleSizeFor(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null
        val longestEdge = maxOf(width, height)
        var sample = 1
        while (ceilDiv(longestEdge, sample) > MAX_EDGE) {
            if (sample >= MAX_SAMPLE_SIZE) return null
            sample *= 2
        }
        return sample
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        value / divisor + if (value % divisor == 0) 0 else 1

    /** The header asked for more memory than the check is allowed to spend. */
    internal class UncheckableImage(message: String) : RuntimeException(message)

    private fun ImageDecodeInput.toSource(): ImageDecoder.Source? = when (this) {
        is ImageDecodeInput.LocalFile -> ImageDecoder.createSource(file)
        // ImageDecoder has no descriptor overload before API 29; the callable one is the entry
        // point for a seekable fd, and below that the check simply does not run.
        is ImageDecodeInput.Descriptor -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ImageDecoder.createSource(
                Callable { AssetFileDescriptor(pfd, 0, pfd.statSize) }
            )

            else -> null
        }

        ImageDecodeInput.None -> null
    }

    private val TAG = logTag("Viewer", "ImageDecodeCheck")

    private const val CHECK_EDGE = 256

    /**
     * Hard cap per edge of the bitmap the check allocates, taken from the same budget every other
     * generated preview is clamped by. Worst case is 256*256*4 = 256 KB, whatever the file claims
     * to be. The decoder still reads every byte on the way there, it just does not keep them.
     */
    internal val MAX_EDGE = PreviewBudget.resolveEdge(CHECK_EDGE, max = PreviewBudget.MAX_ICON_DIM)

    /**
     * Covers edges up to `MAX_SAMPLE_SIZE * MAX_EDGE` px, several times past what JPEG can even
     * encode. Beyond it the check gives up rather than guess. Sizes the decoder cannot honour make
     * it throw, which lands in [Verdict.UNKNOWN] as well.
     */
    internal const val MAX_SAMPLE_SIZE = 1024
}
