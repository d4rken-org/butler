package eu.darken.butler.common.files.io

import okio.Buffer
import okio.Sink
import okio.Timeout

class ProgressTrackingSink(
    private val wrappedSink: Sink,
    private val onProgress: (bytesWritten: Long) -> Unit
) : Sink {
    private var totalBytesWritten = 0L

    override fun write(source: Buffer, byteCount: Long) {
        wrappedSink.write(source, byteCount)
        totalBytesWritten += byteCount
        onProgress(byteCount)
    }

    override fun flush() = wrappedSink.flush()

    override fun timeout(): Timeout = wrappedSink.timeout()

    override fun close() = wrappedSink.close()
}

fun Sink.trackProgress(onProgress: (bytesWritten: Long) -> Unit): Sink =
    ProgressTrackingSink(this, onProgress)
