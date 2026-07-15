package eu.darken.butler.common.files.archive

import okio.FileHandle
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel

/**
 * Read-only [SeekableByteChannel] over an okio [FileHandle], allowing commons-compress'
 * ZipFile to random-access archives on ANY gateway backend (local, SAF, root, ADB)
 * without materializing them first.
 */
internal class OkioSeekableByteChannel(
    private val handle: FileHandle,
) : SeekableByteChannel {

    private var position = 0L
    private var open = true

    override fun isOpen(): Boolean = open

    override fun close() {
        if (!open) return
        open = false
        handle.close()
    }

    @Synchronized
    override fun read(dst: ByteBuffer): Int {
        val buffer = ByteArray(dst.remaining())
        val read = handle.read(position, buffer, 0, buffer.size)
        if (read > 0) {
            dst.put(buffer, 0, read)
            position += read
        }
        return read
    }

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    @Synchronized
    override fun position(): Long = position

    @Synchronized
    override fun position(newPosition: Long): SeekableByteChannel = apply {
        require(newPosition >= 0)
        position = newPosition
    }

    override fun size(): Long = handle.size()

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
}
