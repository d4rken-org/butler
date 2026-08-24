package eu.darken.butler.viewer.core

import android.os.ParcelFileDescriptor
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
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
import testhelpers.coroutine.runTest2
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * The probe reads through a gateway-routed stream, which holds a lease. It must never leave one
 * open, no matter how the read ends.
 *
 * The decode check on top of that is best-effort: whether it can obtain a source at all decides
 * whether it has an opinion, and having none must always mean the file passes. The decoding itself
 * is not testable here - Robolectric stubs ImageDecoder - so what is asserted is the tiering, the
 * API gate and the ownership of what the gateway hands over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageProbeTest {

    private val path = LocalPath.build("/storage/emulated/0/DCIM/photo.jpg")
    private val svgPath = LocalPath.build("/storage/emulated/0/Download/diagram.svg")
    private val gifPath = LocalPath.build("/storage/emulated/0/Download/dancing.gif")
    private val safPath = SAFPath.build("content://com.example.provider/tree/primary", "photo.jpg")
    private val source = ViewerSource.Stored(path)
    private val safSource = ViewerSource.Stored(safPath)
    private val opened = AtomicInteger(0)
    private val closed = AtomicInteger(0)
    private lateinit var gatewaySwitch: GatewaySwitch

    private inner class CountingStream(private val delegate: InputStream) : InputStream() {
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

    private fun probe(
        descriptor: suspend () -> ParcelFileDescriptor? = { null },
        stream: () -> InputStream,
    ): ImageProbe {
        gatewaySwitch = mockk<GatewaySwitch>().apply {
            coEvery { useRes(any<suspend (Any) -> Any?>()) } coAnswers {
                firstArg<suspend (Any) -> Any?>().invoke(this@apply)
            }
            coEvery { openInputStream(any()) } answers { stream() }
            coEvery { openReadPFD(any()) } coAnswers { descriptor() }
        }
        return ImageProbe(readerFor(gatewaySwitch), TestDispatcherProvider())
    }

    private fun anyStream() = CountingStream(ByteArrayInputStream(ByteArray(64)))

    @Test
    fun `closes every stream it opens after a successful read`() = runTest2 {
        val probe = probe { CountingStream(ByteArrayInputStream(ByteArray(64))) }

        probe.probe(source).shouldBeInstanceOf<ProbeResult.Probed>()

        // Bounds and structure check each get their own stream - gateway streams don't rewind.
        opened.get() shouldBe 2
        closed.get() shouldBe 2
    }

    @Test
    fun `a stream that never yields a header is a probe failure`() = runTest2 {
        val probe = probe { throw IOException("disk gave up") }

        probe.probe(source).shouldBeInstanceOf<ProbeResult.ProbeFailed>()

        // The gateway never handed out a stream, so there is no lease to release either.
        opened.get() shouldBe 0
        closed.get() shouldBe 0
    }

    @Test
    fun `closes the stream when the read blows up`() = runTest2 {
        val probe = probe {
            CountingStream(
                object : InputStream() {
                    override fun read(): Int = throw IOException("disk gave up")
                    override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("disk gave up")
                }
            )
        }

        probe.probe(source)

        // The decoder stops at the header and swallows stream failures on the way, so what the
        // read reports is its business - releasing every lease afterwards is ours.
        closed.get() shouldBe opened.get()
        closed.get() shouldBeGreaterThan 0
    }

    @Test
    fun `does not swallow cancellation`() = runTest2 {
        val probe = probe { throw CancellationException("cancelled") }

        shouldThrow<CancellationException> { probe.probe(source) }
    }

    @Test
    fun `closes the stream when the read is cancelled`() = runTest2 {
        val probe = probe {
            CountingStream(
                object : InputStream() {
                    override fun read(): Int = throw CancellationException("cancelled")
                    override fun read(b: ByteArray, off: Int, len: Int): Int =
                        throw CancellationException("cancelled")
                }
            )
        }

        shouldThrow<CancellationException> { probe.probe(source) }

        closed.get() shouldBe 1
    }

    @Test
    fun `a stream without raster dimensions is not reported as a failure`() = runTest2 {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"/>""".toByteArray()
        val probe = probe { CountingStream(ByteArrayInputStream(svg)) }

        val result = probe.probe(ViewerSource.Stored(svgPath))

        // Either the decoder recognises nothing (NoRasterDimensions) or it reports real bounds -
        // what must never happen is a ProbeFailed for a perfectly valid vector file.
        (result is ProbeResult.NoRasterDimensions || result is ProbeResult.Probed) shouldBe true
        // A vector file has no regions to decode, so the structure check must not even look.
        opened.get() shouldBe 1
        closed.get() shouldBe 1
    }

    @Test
    fun `a gif is never structure checked`() = runTest2 {
        // A valid, complete 1x1 GIF89a.
        val gif = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        val probe = probe { CountingStream(ByteArrayInputStream(gif)) }

        // BitmapRegionDecoder is not the right tool for a GIF, animated or not, and a valid one
        // must never be rejected just because it cannot be tiled. The skip keys on the format, so
        // the frame count does not matter here.
        probe.probe(ViewerSource.Stored(gifPath)).shouldNotBeInstanceOf<ProbeResult.ProbeFailed>()
        opened.get() shouldBe 1
        closed.get() shouldBe 1
    }

    @Test
    fun `a readable local file is decoded straight from disk`() = runTest2 {
        val file = File.createTempFile("butler-probe", ".jpg").apply {
            writeBytes(ByteArray(64))
            deleteOnExit()
        }
        val probe = probe { anyStream() }

        probe.resolveDecodeInput(ViewerSource.Stored(LocalPath.build(file))) shouldBe ImageDecodeInput.LocalFile(file)

        // The cheap path: a file the process can read needs no descriptor from the gateway.
        coVerify(exactly = 0) { gatewaySwitch.openReadPFD(any()) }
    }

    @Test
    fun `a path the process cannot read directly asks the gateway for a descriptor`() = runTest2 {
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        val probe = probe(descriptor = { descriptor }) { anyStream() }

        probe.resolveDecodeInput(safSource) shouldBe ImageDecodeInput.Descriptor(descriptor)
    }

    @Test
    fun `a path the gateway cannot serve is skipped, not failed`() = runTest2 {
        // Archive entries end up here: no source, no verdict.
        val probe = probe(descriptor = { null }) { anyStream() }

        probe.resolveDecodeInput(safSource) shouldBe ImageDecodeInput.None
        probe.probe(safSource).shouldNotBeInstanceOf<ProbeResult.ProbeFailed>()
    }

    @Test
    fun `a descriptor that blows up on open is skipped, not failed`() = runTest2 {
        val probe = probe(descriptor = { throw IOException("no fd for you") }) { anyStream() }

        probe.resolveDecodeInput(safSource) shouldBe ImageDecodeInput.None
        // Failing to obtain a source says nothing about the file, so it must never reject one.
        probe.probe(safSource).shouldNotBeInstanceOf<ProbeResult.ProbeFailed>()
    }

    @Test
    fun `a descriptor handed to the check is closed again`() {
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)

        ImageDecodeInput.Descriptor(descriptor).release()

        // The gateway hands over ownership, so the check owns the close.
        verify { descriptor.close() }
    }

    @Test
    fun `a resolved descriptor belongs to the holder that outlives the resolve`() = runTest2 {
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        val probe = probe(descriptor = { descriptor }) { anyStream() }
        val pending = PendingInput()

        probe.resolveDecodeInput(safSource, pending) shouldBe ImageDecodeInput.Descriptor(descriptor)
        // The check still needs it while it runs.
        verify(exactly = 0) { descriptor.close() }

        pending.close()

        verify(exactly = 1) { descriptor.close() }
    }

    @Test
    fun `a descriptor obtained while the resolve is cancelled is still owned`() {
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        var job: Job? = null
        // Cancelled while the gateway is handing the descriptor over: the resolve's return value is
        // discarded on the way out, so by then the holder has to be the owner - otherwise the only
        // reference to an open fd is gone and repeating it exhausts the process limit.
        val probe = probe(descriptor = { job!!.cancel(); descriptor }) { anyStream() }
        val pending = PendingInput()

        runBlocking {
            val resolve = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                runCatching { probe.resolveDecodeInput(safSource, pending) }
            }
            job = resolve
            resolve.start()
            resolve.join()
        }

        pending.close()

        verify(exactly = 1) { descriptor.close() }
    }

    @Test
    fun `the holder closes what it owns exactly once`() = runTest2 {
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        val probe = probe(descriptor = { descriptor }) { anyStream() }
        val pending = PendingInput()

        probe.resolveDecodeInput(safSource, pending)
        // The check runs inside a finally that cannot know whether the resolve got that far.
        pending.close()
        pending.close()

        verify(exactly = 1) { descriptor.close() }
    }

    @Test
    fun `a path with nothing to hand over leaves the holder empty`() = runTest2 {
        val probe = probe(descriptor = { null }) { anyStream() }
        val pending = PendingInput()

        probe.resolveDecodeInput(safSource, pending) shouldBe ImageDecodeInput.None

        // Nothing was opened, so closing the holder has nothing to do and must not blow up.
        pending.close()
    }

    @Test
    fun `nothing to decode is an inconclusive verdict, never a defect`() {
        ImageDecodeCheck.inspect(ImageDecodeInput.None) shouldBe ImageDecodeCheck.Verdict.UNKNOWN
    }

    @Test
    @Config(sdk = [28])
    fun `a descriptor is only usable from API 29 on`() {
        // ImageDecoder has no descriptor entry point on 28, and no source means no verdict.
        val input = ImageDecodeInput.Descriptor(mockk(relaxed = true))

        ImageDecodeCheck.inspect(input) shouldBe ImageDecodeCheck.Verdict.UNKNOWN
    }

    @Test
    @Config(sdk = [26])
    fun `below API 28 the decode check does not even look for a source`() = runTest2 {
        val probe = probe { anyStream() }

        probe.probe(source).shouldNotBeInstanceOf<ProbeResult.ProbeFailed>()

        // ImageDecoder does not exist there, so the region check stands alone.
        coVerify(exactly = 0) { gatewaySwitch.openReadPFD(any()) }
        opened.get() shouldBe 2
        closed.get() shouldBe 2
    }
}
