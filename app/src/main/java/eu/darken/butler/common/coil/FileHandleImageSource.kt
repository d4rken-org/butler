package eu.darken.butler.common.coil

import android.media.MediaDataSource
import coil3.decode.ImageSource
import coil3.video.MediaDataSourceFetcher
import okio.FileHandle
import okio.FileSystem
import okio.buffer

internal fun FileHandle.toImageSource(): ImageSource {
    val handle = this
    val sourceBuffer = this.source().buffer()
    val mediaDataSource = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            return handle.read(position, buffer, offset, size)
        }

        override fun getSize(): Long {
            return handle.size()
        }

        override fun close() {
            sourceBuffer.close()
            handle.close()
        }
    }
    return ImageSource(
        source = sourceBuffer,
        fileSystem = FileSystem.SYSTEM,
        metadata = MediaDataSourceFetcher.MediaSourceMetadata(mediaDataSource),
    )
}