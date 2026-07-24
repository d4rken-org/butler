package eu.darken.butler.common.coil

import coil3.video.MediaDataSourceFetcher
import io.kotest.matchers.shouldBe
import okio.FileHandle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import testhelpers.BaseTest
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileHandleImageSourceTest : BaseTest() {

    private class TrackingFileHandle(
        private val data: ByteArray = ByteArray(64) { it.toByte() },
    ) : FileHandle(readWrite = false) {
        val closed = AtomicBoolean(false)

        override fun protectedRead(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int): Int {
            if (fileOffset >= data.size) return -1
            val count = minOf(byteCount, data.size - fileOffset.toInt())
            data.copyInto(array, arrayOffset, fileOffset.toInt(), fileOffset.toInt() + count)
            return count
        }

        override fun protectedSize(): Long = data.size.toLong()
        override fun protectedClose() {
            closed.set(true)
        }

        override fun protectedFlush() = Unit
        override fun protectedResize(size: Long) = throw UnsupportedOperationException()
        override fun protectedWrite(fileOffset: Long, array: ByteArray, arrayOffset: Int, byteCount: Int) =
            throw UnsupportedOperationException()
    }

    @Test
    fun `closing the image source without decoding closes the file handle`() {
        val handle = TrackingFileHandle()
        val imageSource = handle.toImageSource()

        // A fetch result that is cancelled/abandoned before any decoder runs: Coil closes the
        // ImageSource (buffered source) but never touches the MediaDataSource.
        imageSource.close()

        handle.closed.get() shouldBe true
    }

    @Test
    fun `closing the media data source closes the file handle`() {
        val handle = TrackingFileHandle()
        val imageSource = handle.toImageSource()

        val metadata = imageSource.metadata as MediaDataSourceFetcher.MediaSourceMetadata
        metadata.mediaDataSource.close()

        handle.closed.get() shouldBe true
    }

    @Test
    fun `double close via both paths is idempotent`() {
        val handle = TrackingFileHandle()
        val imageSource = handle.toImageSource()

        imageSource.close()
        val metadata = imageSource.metadata as MediaDataSourceFetcher.MediaSourceMetadata
        metadata.mediaDataSource.close()

        handle.closed.get() shouldBe true
    }

    @Test
    fun `source content is readable before close`() {
        val handle = TrackingFileHandle()
        val imageSource = handle.toImageSource()

        imageSource.source().use { source ->
            source.readByteArray(4).toList() shouldBe listOf<Byte>(0, 1, 2, 3)
        }
        handle.closed.get() shouldBe true
    }
}
