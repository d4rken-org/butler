package eu.darken.butler.viewer.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.SAFPath
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.coroutine.TestDispatcherProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gateway streams hold a lease, so every hand-off between our resolver, telephoto and Coil has to
 * close exactly one stream exactly once. Uses SAF paths on purpose: those never take the direct
 * file() shortcut, so the gateway stream path is always exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayStreamLifecycleTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val opened = AtomicInteger(0)
    private val closed = AtomicInteger(0)

    private val safPath: APath<*> = SAFPath.build(
        "content://com.example.provider/tree/primary",
        "photo.jpg",
    )

    // A minimal JFIF header. Telephoto only rejects formats it recognises as non-tileable, so
    // anything that is not SVG/GIF/AVIF has to come back as sub-samplable.
    private val jpegBytes = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
    )

    private val gifBytes = "GIF89a".toByteArray() + ByteArray(16)

    private inner class CountingStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)

        init {
            opened.incrementAndGet()
        }

        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()

        override fun close() {
            closed.incrementAndGet()
            delegate.close()
        }
    }

    private fun gateway(bytes: ByteArray): GatewaySwitch = mockk<GatewaySwitch>().apply {
        coEvery { openInputStream(any()) } answers { CountingStream(bytes) }
    }

    private fun sourceFor(
        gatewaySwitch: GatewaySwitch,
        path: APath<*> = safPath,
    ) = GatewayZoomableImageSource(
        context = context,
        contentReader = readerFor(gatewaySwitch),
        imageLoader = mockk<ImageLoader>(relaxed = true),
        dispatcherProvider = TestDispatcherProvider(),
        source = ViewerSource.Stored(path),
        onError = {},
    )

    @Test
    fun `a sub-samplable image opens the gateway stream once and is not closed by us`() {
        runBlocking {
            val resolution = sourceFor(gateway(jpegBytes)).resolveSource()

            resolution.shouldBeInstanceOf<GatewayZoomableImageSource.Resolution.SubSampled>()
            opened.get() shouldBe 1
            // Telephoto owns the source from here on; closing it here would be the double close.
            closed.get() shouldBe 0
        }
    }

    @Test
    fun `a rejected source is closed before the Coil fallback runs`() {
        runBlocking {
            val resolution = sourceFor(gateway(gifBytes)).resolveSource()

            resolution shouldBe GatewayZoomableImageSource.Resolution.NeedsPainter
            opened.get() shouldBe 1
            // canBeSubSampled() already opened the stream via peek() and nothing downstream would
            // ever close it, so it has to be gone before the Coil request starts.
            closed.get() shouldBe 1
        }
    }

    @Test
    fun `a cancelled resolve releases the stream`() {
        runBlocking {
            val gatewaySwitch = mockk<GatewaySwitch>().apply {
                coEvery { openInputStream(any()) } answers { CountingStream(jpegBytes) }
            }
            val source = sourceFor(gatewaySwitch)

            // Non-null for a gateway source: only a provider URI telephoto refuses yields null.
            val subSamplingSource = source.openSource()!!
            // Cancellation between opening and hand-off must not leak the lease.
            subSamplingSource.close()

            closed.get() shouldBe 1
        }
    }

    @Test
    fun `an unconsumed raw source still closes the stream it was handed`() {
        val stream = CountingStream(jpegBytes)
        val source = sourceFor(gateway(jpegBytes)).rawGatewaySource(stream)

        source.close()

        closed.get() shouldBe 1
    }

    @Test
    fun `a gateway failure during open is not swallowed`() {
        runBlocking {
            val gatewaySwitch = mockk<GatewaySwitch>().apply {
                coEvery { openInputStream(any()) } throws CancellationException("cancelled")
            }

            shouldThrow<CancellationException> { sourceFor(gatewaySwitch).resolveSource() }
        }
    }

    @Test
    fun `a sub-sampled source that telephoto never takes is closed on disposal`() {
        runBlocking {
            val pending = GatewayZoomableImageSource.PendingSource()

            sourceFor(gateway(jpegBytes)).resolveSource(pending)
                .shouldBeInstanceOf<GatewayZoomableImageSource.Resolution.SubSampled>()
            closed.get() shouldBe 0

            // Composition disposed before the sub-sampling delegate was installed.
            pending.close()

            closed.get() shouldBe 1
        }
    }

    @Test
    fun `a source telephoto took over is not closed by us`() {
        runBlocking {
            val pending = GatewayZoomableImageSource.PendingSource()

            sourceFor(gateway(jpegBytes)).resolveSource(pending)
            // The delegate is composed: telephoto owns and closes the source from here on.
            pending.release()
            pending.close()

            closed.get() shouldBe 0
        }
    }

    @Test
    fun `a resolve cancelled at the dispatcher boundary does not leak the opened stream`() {
        var job: Job? = null
        val gatewaySwitch = mockk<GatewaySwitch>().apply {
            coEvery { openInputStream(any()) } answers {
                // Cancelled while the open is still inside withContext(IO): the resolver's result
                // is discarded on the way out, so the holder has to be the owner by then.
                job!!.cancel()
                CountingStream(jpegBytes)
            }
        }
        val pending = GatewayZoomableImageSource.PendingSource()

        runBlocking {
            val resolve = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                runCatching { sourceFor(gatewaySwitch).resolveSource(pending) }
            }
            job = resolve
            resolve.start()
            resolve.join()
        }

        opened.get() shouldBe 1
        pending.close()
        closed.get() shouldBe 1
    }

    @Test
    fun `a format the tile decoder cannot handle never opens a stream`() {
        runBlocking {
            // BitmapRegionDecoder has no BMP support at any API level, so sub-sampling it would
            // render a blank canvas without ever reporting an error.
            val bmpPath = SAFPath.build("content://com.example.provider/tree/primary", "scan.bmp")
            val resolution = sourceFor(gateway(jpegBytes), path = bmpPath).resolveSource()

            resolution shouldBe GatewayZoomableImageSource.Resolution.NeedsPainter
            opened.get() shouldBe 0
            closed.get() shouldBe 0
        }
    }

    @Test
    fun `a stream that dies while being inspected is still released`() {
        runBlocking {
            // Composition disposal cancels the resolver mid-inspection: the stream is already open
            // at that point, so however the inspection ends the lease has to be gone.
            val gatewaySwitch = mockk<GatewaySwitch>().apply {
                coEvery { openInputStream(any()) } answers {
                    object : InputStream() {
                        override fun read(): Int = throw CancellationException("cancelled")
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            throw CancellationException("cancelled")

                        override fun close() {
                            closed.incrementAndGet()
                        }
                    }
                }
            }

            runCatching { sourceFor(gatewaySwitch).resolveSource() }

            closed.get() shouldBe 1
        }
    }
}
