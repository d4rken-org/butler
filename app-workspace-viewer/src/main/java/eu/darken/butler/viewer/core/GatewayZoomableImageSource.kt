package eu.darken.butler.viewer.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import coil3.ImageLoader
import coil3.compose.asPainter
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.util.canBeSubSampled
import me.saket.telephoto.zoomable.ZoomableImageSource
import okio.Path.Companion.toOkioPath
import okio.source
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * A [ZoomableImageSource] that reads through Butler's [GatewaySwitch] instead of Coil's disk cache.
 *
 * Telephoto's own Coil integration only sub-samples when the Coil result resolves to a real file in
 * Coil's disk cache. Butler's images are not guaranteed to be disk-cached, so that integration
 * would silently degrade every image to a whole-bitmap painter and run out of memory on large
 * photos. This resolver picks the sub-sampling source itself and falls back to Coil for everything
 * the tile decoder cannot handle (see [SubSamplableFormats]).
 */
class GatewayZoomableImageSource(
    private val context: Context,
    private val gatewaySwitch: GatewaySwitch,
    private val imageLoader: ImageLoader,
    private val dispatcherProvider: DispatcherProvider,
    private val path: APath<*>,
    private val onError: (Throwable) -> Unit,
) : ZoomableImageSource {

    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult {
        var resolution by remember(path) { mutableStateOf<Resolution>(Resolution.Pending) }
        // Owns every opened source until telephoto has it, so nothing dropped in between leaks.
        val pending = remember(path) { PendingSource() }

        LaunchedEffect(path) {
            resolution = try {
                when (val resolved = resolveSource(pending)) {
                    Resolution.NeedsPainter -> loadPainter()
                    else -> resolved
                }
            } catch (e: CancellationException) {
                pending.close()
                throw e
            } catch (e: Exception) {
                log(TAG, ERROR) { "Failed to resolve $path: ${e.asLog()}" }
                onError(e)
                Resolution.Failed
            }
        }

        DisposableEffect(path) {
            // Disposal before the sub-sampling delegate is installed: nothing downstream ever
            // learns about that source, and a gateway stream holds a lease.
            onDispose { pending.close() }
        }

        return when (val current = resolution) {
            // NeedsPainter is the hand-off between the tile probe and the Coil load, it never
            // reaches the UI: the load turns it into Rendered or Failed.
            Resolution.Pending,
            Resolution.NeedsPainter,
            Resolution.Failed -> ZoomableImageSource.ResolveResult(delegate = null)

            is Resolution.SubSampled -> {
                DisposableEffect(current.source) {
                    // The delegate is composed, telephoto's state owns and closes the source now.
                    pending.release()
                    onDispose { }
                }
                ZoomableImageSource.ResolveResult(
                    delegate = ZoomableImageSource.SubSamplingDelegate(current.source),
                )
            }

            is Resolution.Rendered -> ZoomableImageSource.ResolveResult(
                delegate = ZoomableImageSource.PainterDelegate(painter = current.painter),
            )
        }
    }

    /**
     * Runs the Coil load ourselves instead of handing telephoto a `rememberAsyncImagePainter`.
     *
     * `ZoomableImage` remembers whatever painter it gets from a `PainterDelegate`, and that remember
     * is what drives drawable-backed painters: `DrawablePainter.onRemembered()` is what makes the
     * drawable visible, wires its invalidation callback and starts the animation. An
     * `AsyncImagePainter` is itself a `RememberObserver`, so it swallows that remember slot - the
     * drawable painter underneath it is then only ever driven by Coil's internal forwarding, and
     * GIFs (and every other drawable-backed decode) render as nothing. Bitmap results survive
     * because `BitmapPainter` has no lifecycle to lose.
     *
     * Being remembered twice - once by `rememberAsyncImagePainter`, once by telephoto - also made
     * `AsyncImagePainter` start two requests and cancel the first one.
     */
    internal suspend fun loadPainter(): Resolution {
        val request = ImageRequest.Builder(context).data(ViewerImageRequest(path)).build()
        return when (val result = imageLoader.execute(request)) {
            is SuccessResult -> {
                log(TAG) { "Coil loaded $path" }
                Resolution.Rendered(result.image.asPainter(context))
            }

            is ErrorResult -> {
                log(TAG, ERROR) { "Coil failed to load $path: ${result.throwable.asLog()}" }
                onError(result.throwable)
                Resolution.Failed
            }
        }
    }

    internal suspend fun resolveSource(pending: PendingSource = PendingSource()): Resolution {
        // Telephoto only refuses SVG/GIF/AVIF, which is far more than BitmapRegionDecoder can
        // actually tile. Formats it cannot decode never reach the tile decoder from here.
        val format = MimeInfo.fromFileName(path.name).rawType
        if (!SubSamplableFormats.supports(format)) {
            log(TAG) { "$format cannot be tiled, falling back to Coil for $path" }
            return Resolution.NeedsPainter
        }

        val source = withContext(dispatcherProvider.IO) {
            // Handing ownership over inside the dispatcher block: a cancellation that discards the
            // result on the way out must not be the moment nobody owns the stream.
            openSource().also { pending.adopt(it) }
        }

        val subSamplable = try {
            source.canBeSubSampled(context)
        } catch (e: Throwable) {
            pending.close()
            throw e
        }

        if (subSamplable) {
            log(TAG) { "Sub-sampling $path" }
            // Telephoto owns the source once the delegate is composed - until then [pending] does.
            return Resolution.SubSampled(source)
        }

        // canBeSubSampled() opened the stream via peek(). Without a sub-sampling delegate nothing
        // downstream will ever close it, so it has to go before the Coil request starts.
        pending.close()
        log(TAG) { "$path cannot be sub-sampled, falling back to Coil" }
        return Resolution.NeedsPainter
    }

    /**
     * Single-slot owner for a resolved [SubSamplingImageSource] during the window between opening
     * it and telephoto taking it over. Gateway streams hold a lease, so anything dropped in that
     * window - a cancelled resolve, a composition disposed before the delegate is installed - has
     * to be closed here or the file descriptor is gone for good.
     */
    internal class PendingSource {

        private val held = AtomicReference<SubSamplingImageSource?>(null)

        fun adopt(source: SubSamplingImageSource) {
            held.getAndSet(source)?.let { runCatching { it.close() } }
        }

        /** Telephoto has taken the source over; closing it is its job from here on. */
        fun release() {
            held.set(null)
        }

        fun close() {
            held.getAndSet(null)?.let { runCatching { it.close() } }
        }
    }

    internal suspend fun openSource(): SubSamplingImageSource = when {
        // A LocalPath alone does NOT imply direct access: LocalGateway auto-escalates to
        // ISOLATED/ROOT/ADB, and file() would bypass that routing and fail on protected files
        // Butler can otherwise read.
        path is LocalPath && path.file.canRead() -> SubSamplingImageSource.file(path.file.toOkioPath())
        else -> rawGatewaySource(gatewaySwitch.openInputStream(path))
    }

    /**
     * The gateway open is suspending while telephoto's factory lambda is not, so the stream is
     * opened here (inside the resolver's coroutine, never via runBlocking) and handed over lazily.
     * Telephoto buffers it once and serves decoders via peek(), so a single-shot lambda is enough;
     * [onClose] covers the case where it never asks for the source at all.
     */
    internal fun rawGatewaySource(stream: InputStream): SubSamplingImageSource {
        val consumed = AtomicBoolean(false)
        return SubSamplingImageSource.rawSource(
            source = {
                consumed.set(true)
                stream.source()
            },
            onClose = {
                if (!consumed.get()) runCatching { stream.close() }
            },
        )
    }

    internal sealed interface Resolution {
        data object Pending : Resolution
        data class SubSampled(val source: SubSamplingImageSource) : Resolution

        /** The tile decoder refused the format, [loadPainter] takes over from here. */
        data object NeedsPainter : Resolution
        data class Rendered(val painter: Painter) : Resolution
        data object Failed : Resolution
    }

    class Factory @Inject constructor(
        @ApplicationContext private val context: Context,
        private val gatewaySwitch: GatewaySwitch,
        private val imageLoader: ImageLoader,
        private val dispatcherProvider: DispatcherProvider,
    ) {
        fun create(
            path: APath<*>,
            onError: (Throwable) -> Unit,
        ): ZoomableImageSource = GatewayZoomableImageSource(
            context = context,
            gatewaySwitch = gatewaySwitch,
            imageLoader = imageLoader,
            dispatcherProvider = dispatcherProvider,
            path = path,
            onError = onError,
        )
    }

    companion object {
        private val TAG = logTag("Viewer", "ZoomableImageSource")
    }
}
