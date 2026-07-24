package eu.darken.butler.common.coil

import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.video.VideoFrameDecoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Bounds concurrent video-frame decodes to protect the device's shared MediaCodec pool.
 *
 * [VideoFrameDecoder] drives MediaMetadataRetriever/MediaCodec through blocking,
 * non-interruptible native calls. Unbounded, a fast grid scroll over video-heavy folders can
 * exhaust the codec pool and abort the process natively (SIGABRT, "Failed to query component
 * store for system resources"). A shared [Semaphore] hard-bounds live decode sessions to
 * [MAX_PARALLEL_DECODES], and the work runs on a dedicated limited-parallelism dispatcher so the
 * blocking native calls never occupy Coil's shared decode lanes (image decodes aren't starved by
 * slow — or hung — video work).
 */
class BoundedVideoFrameDecoder(
    private val delegate: Decoder,
    private val semaphore: Semaphore,
    private val dispatcher: CoroutineDispatcher,
) : Decoder {

    override suspend fun decode(): DecodeResult? = semaphore.withPermit {
        withContext(dispatcher) {
            // A request cancelled while queued for a decode slot must never start the
            // non-interruptible native work.
            currentCoroutineContext().ensureActive()
            delegate.decode()
        }
    }

    class Factory(
        private val delegate: Decoder.Factory = VideoFrameDecoder.Factory(),
        parallelism: Int = MAX_PARALLEL_DECODES,
        baseDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : Decoder.Factory {

        private val semaphore = Semaphore(parallelism)
        private val dispatcher = baseDispatcher.limitedParallelism(parallelism)

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            // The inner factory returns null for non-video mimes — only video is bounded.
            val inner = delegate.create(result, options, imageLoader) ?: return null
            return BoundedVideoFrameDecoder(inner, semaphore, dispatcher)
        }
    }

    companion object {
        private const val MAX_PARALLEL_DECODES = 2
    }
}
