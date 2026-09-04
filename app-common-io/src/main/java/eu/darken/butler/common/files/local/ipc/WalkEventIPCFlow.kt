package eu.darken.butler.common.files.local.ipc

import android.os.Parcel
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.flow.chunked
import eu.darken.butler.common.ipc.RemoteInputStream
import eu.darken.butler.common.ipc.inputStream
import eu.darken.butler.common.ipc.remoteInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

// Chunked base64 lines over a pipe, carrying WalkEvents so directory errors and the terminal
// Done/FatalError marker cross the IPC boundary alongside the items.

private const val CHUNK_COUNT = 100
private const val EVENT_SIZE = 1024

fun RemoteInputStream.toWalkEventFlow(): Flow<WalkEvent> = flow {
    if (Bugs.isTrace) log(FileOpsClient.TAG, VERBOSE) { "RemoteInputStream.toWalkEventFlow() starting..." }

    val buffer = this@toWalkEventFlow.inputStream().reader().buffered(CHUNK_COUNT * EVENT_SIZE)
    try {
        while (currentCoroutineContext().isActive) {
            val line = buffer.readLine() ?: break

            val decodedChunk = line.decodeBase64()
                ?: throw IOException("Malformed walk stream chunk (${line.length} chars)")
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(decodedChunk.toByteArray(), 0, decodedChunk.size)
                parcel.setDataPosition(0)
                val wrapper = WalkEventsIPCWrapper.createFromParcel(parcel)

                if (Bugs.isTrace) {
                    log(FileOpsClient.TAG, VERBOSE) { "READCHUNK: ${decodedChunk.size}B to ${wrapper.payload.size} events" }
                }
                wrapper.payload.forEach { emit(it) }
            } finally {
                parcel.recycle()
            }
        }
    } finally {
        runCatching { buffer.close() }
        runCatching { close() }
    }
}

fun Flow<WalkEvent>.toEventRemoteStream(scope: CoroutineScope): RemoteInputStream {
    if (Bugs.isTrace) log(FileOpsHost.TAG, VERBOSE) { "Flow<WalkEvent>.toEventRemoteStream()..." }

    val inputStream = PipedInputStream(2 * CHUNK_COUNT * EVENT_SIZE)
    val outputStream = PipedOutputStream()
    inputStream.connect(outputStream)

    val buffer = outputStream.writer().buffered(CHUNK_COUNT * EVENT_SIZE)

    this@toEventRemoteStream
        .chunked(CHUNK_COUNT)
        .onEach { chunk ->
            val parcel = Parcel.obtain()
            val encodedChunk = try {
                WalkEventsIPCWrapper(chunk).writeToParcel(parcel, 0)
                parcel.marshall().toByteString().base64()
            } finally {
                parcel.recycle()
            }

            // Only the pipe write can fail with the consumer gone; marshalling above stays unwrapped
            // so genuine serialization errors still fault loudly.
            try {
                buffer.write(encodedChunk)
                buffer.write('\n'.code)
                buffer.flush()
            } catch (e: IOException) {
                throw ConsumerGone(e)
            }

            if (Bugs.isTrace) {
                log(FileOpsHost.TAG, VERBOSE) { "WRITECHUNK: ${chunk.size} events to ${encodedChunk.length}B" }
            }
        }
        .onCompletion { cause ->
            // Skip the final flush when the stream is unwinding on a failure/cancel (the consumer is
            // likely gone, so flush would just throw again); always attempt close, ignoring failures.
            if (cause == null) runCatching { buffer.flush() }
            runCatching { buffer.close() }
        }
        .catch { e ->
            when {
                e is CancellationException -> throw e
                // The client closed its end (cancelled scan, take()); nobody is left to stream to.
                // Contain it — rethrowing would fault the helper's app scope and kill the process.
                e is ConsumerGone -> log(FileOpsHost.TAG, WARN) { "toEventRemoteStream consumer gone: ${e.asLog()}" }
                else -> {
                    log(FileOpsHost.TAG, ERROR) { "toEventRemoteStream failed: ${e.asLog()}" }
                    throw e
                }
            }
        }
        .launchIn(scope)

    return inputStream.remoteInputStream()
}
