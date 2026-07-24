package eu.darken.butler.common.coil

import android.media.MediaDataSource
import coil3.decode.ImageSource
import coil3.video.MediaDataSourceFetcher
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.atomic.AtomicBoolean

internal fun FileHandle.toImageSource(): ImageSource {
    val handle = this

    // Coil's SourceImageSource.close() only closes the buffered source, never the metadata's
    // MediaDataSource — so the FileHandle must be closed when EITHER is closed, or a request
    // cancelled before decode leaks the handle ("A resource failed to call close").
    // Caveat: a decoder forcing ImageSource.file() would copy the stream to a temp file and drop
    // the buffered source unclosed — no current Butler decoder does (video uses the metadata's
    // MediaDataSource, images consume source()).
    val handleClosed = AtomicBoolean(false)
    fun closeHandleOnce() {
        if (handleClosed.compareAndSet(false, true)) runCatching { handle.close() }
    }

    val sourceBuffer = object : ForwardingSource(handle.source()) {
        override fun close() {
            super.close()
            closeHandleOnce()
        }
    }.buffer()

    val mediaDataSource = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            return handle.read(position, buffer, offset, size)
        }

        override fun getSize(): Long {
            return handle.size()
        }

        override fun close() {
            runCatching { sourceBuffer.close() }
            closeHandleOnce()
        }
    }
    return ImageSource(
        source = sourceBuffer,
        fileSystem = FileSystem.SYSTEM,
        metadata = MediaDataSourceFetcher.MediaSourceMetadata(mediaDataSource),
    )
}
