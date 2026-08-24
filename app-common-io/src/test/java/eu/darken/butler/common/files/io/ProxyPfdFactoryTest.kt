package eu.darken.butler.common.files.io

import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okio.FileHandle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import testhelpers.EmptyApp
import java.io.IOException

/**
 * Drives the [ProxyFileDescriptorCallback] the factory installs, without a real FUSE mount: the
 * opener seam hands the callback back to the test instead of to [android.os.storage.StorageManager].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29], application = EmptyApp::class)
class ProxyPfdFactoryTest : BaseTest() {

    private val capturedCallbacks = mutableListOf<ProxyFileDescriptorCallback>()
    private val capturedHandlers = mutableListOf<Handler>()

    private fun factory(
        opener: (Int, ProxyFileDescriptorCallback, Handler) -> ParcelFileDescriptor = { _, _, _ -> mockk(relaxed = true) },
    ) = DefaultProxyPfdFactory { mode, callback, handler ->
        capturedCallbacks.add(callback)
        capturedHandlers.add(handler)
        opener(mode, callback, handler)
    }

    @Test
    fun `onGetSize reports the handle's size`() {
        val handle = mockk<FileHandle>(relaxed = true)
        every { handle.size() } returns 4096L

        factory().create(handle, "r")

        capturedCallbacks.single().onGetSize() shouldBe 4096L
    }

    @Test
    fun `onRead passes offset and length through to the handle`() {
        val handle = mockk<FileHandle>(relaxed = true)
        val data = ByteArray(64)
        every { handle.read(any(), any(), any(), any()) } returns 32

        factory().create(handle, "r")
        capturedCallbacks.single().onRead(128L, 32, data) shouldBe 32

        verify { handle.read(128L, data, 0, 32) }
    }

    @Test
    fun `onRead clamps the length to the buffer`() {
        val handle = mockk<FileHandle>(relaxed = true)
        val data = ByteArray(16)
        every { handle.read(any(), any(), any(), any()) } returns 16

        factory().create(handle, "r")
        capturedCallbacks.single().onRead(0L, 64, data)

        verify { handle.read(0L, data, 0, 16) }
    }

    @Test
    fun `a negative read result is reported as zero bytes`() {
        // End-of-file is -1 for okio but 0 for the proxy protocol, where negative means "errno".
        val handle = mockk<FileHandle>(relaxed = true)
        every { handle.read(any(), any(), any(), any()) } returns -1

        factory().create(handle, "r")

        capturedCallbacks.single().onRead(0L, 8, ByteArray(8)) shouldBe 0
    }

    @Test
    fun `a plain failure becomes an EIO ErrnoException carrying the cause`() {
        val handle = mockk<FileHandle>(relaxed = true)
        val boom = IOException("gateway went away")
        every { handle.size() } throws boom

        factory().create(handle, "r")

        val thrown = shouldThrow<ErrnoException> { capturedCallbacks.single().onGetSize() }
        thrown.errno shouldBe OsConstants.EIO
        thrown.cause shouldBe boom
    }

    @Test
    fun `an ErrnoException is passed through unchanged`() {
        val handle = mockk<FileHandle>(relaxed = true)
        val original = ErrnoException("read", OsConstants.EACCES)
        every { handle.read(any(), any(), any(), any()) } throws original

        factory().create(handle, "r")

        shouldThrow<ErrnoException> {
            capturedCallbacks.single().onRead(0L, 8, ByteArray(8))
        } shouldBe original
    }

    @Test
    fun `onRelease closes the handle`() {
        val handle = mockk<FileHandle>(relaxed = true)

        factory().create(handle, "r")
        verify(exactly = 0) { handle.close() }

        capturedCallbacks.single().onRelease()
        verify(exactly = 1) { handle.close() }
    }

    @Test
    fun `a failing open closes the handle instead of leaking it`() {
        val handle = mockk<FileHandle>(relaxed = true)
        val factory = factory { _, _, _ -> throw IOException("no proxy fd for you") }

        shouldThrow<IOException> { factory.create(handle, "r") }

        verify(exactly = 1) { handle.close() }
    }

    @Test
    fun `descriptors are spread round-robin over the callback thread pool`() {
        // One shared thread would serialize every read across all consumers, so the pool has to be
        // used, and it has to wrap around rather than grow.
        val factory = factory()
        repeat(5) { factory.create(mockk(relaxed = true), "r") }

        capturedHandlers.distinct().size shouldBe 4
        capturedHandlers[0] shouldBe capturedHandlers[4]
        capturedHandlers[0] shouldNotBe capturedHandlers[1]
    }
}
