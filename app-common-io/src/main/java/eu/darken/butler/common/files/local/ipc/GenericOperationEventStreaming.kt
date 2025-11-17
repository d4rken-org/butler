package eu.darken.butler.common.files.local.ipc

import android.os.Parcel
import android.os.Parcelable
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ipc.RemoteInputStream
import eu.darken.butler.common.ipc.inputStream
import eu.darken.butler.common.ipc.remoteInputStream
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
import java.io.PipedInputStream
import java.io.PipedOutputStream

private val TAG = logTag("FileOps", "IPC", "EventStreaming")

// Buffer size for streaming (balance between IPC overhead and memory usage)
private const val EVENT_BUFFER_SIZE = 512 * 1024  // 512KB

/**
 * Generic extension to stream any Parcelable events through RemoteInputStream.
 *
 * Each event is serialized individually (not batched) for real-time streaming.
 * Events are marshalled to Parcel, encoded as Base64, and written line-by-line.
 *
 * @param scope CoroutineScope for launching streaming job
 * @return RemoteInputStream that client can read from
 */
fun <T : Parcelable> Flow<T>.toRemoteInputStream(
    scope: CoroutineScope
): RemoteInputStream {
    val inputStream = PipedInputStream(EVENT_BUFFER_SIZE)
    val outputStream = PipedOutputStream()
    inputStream.connect(outputStream)

    val buffer = outputStream.writer().buffered(EVENT_BUFFER_SIZE)

    this@toRemoteInputStream
        .onEach { event ->
            // Serialize event to Parcel with class name for polymorphic types
            val parcel = Parcel.obtain().apply {
                // Write class name first for deserialization
                writeString(event::class.java.name)
                // Use writeParcelable() for symmetric serialization with readParcelable().
                // Do NOT use writeToParcel() as it writes raw data without metadata.
                writeParcelable(event, 0)
            }

            // Encode as Base64 and write as line (newline-delimited)
            val encodedEvent = parcel.marshall().toByteString().base64()
            parcel.recycle()

            buffer.write(encodedEvent)
            buffer.write('\n'.code)
            buffer.flush()
        }
        .onCompletion {
            buffer.flush()
            buffer.close()
        }
        .catch { e ->
            log(TAG, ERROR) { "Event streaming failed: ${e.asLog()}" }
            throw e
        }
        .launchIn(scope)

    return inputStream.remoteInputStream()
}

/**
 * Generic extension to read Parcelable events from RemoteInputStream.
 *
 * Reads line-by-line, decodes Base64, unmarshalls Parcel, creates event.
 *
 * @param creator Parcelable.Creator for deserializing events
 * @return Flow of deserialized events
 */
fun <T : Parcelable> RemoteInputStream.toEventFlow(
    creator: Parcelable.Creator<T>
): Flow<T> = flow {
    val buffer = this@toEventFlow.inputStream().reader().buffered(EVENT_BUFFER_SIZE)

    while (currentCoroutineContext().isActive) {
        val line = buffer.readLine() ?: break

        // Decode Base64 and unmarshall Parcel
        val decodedEvent = line.decodeBase64()!!
        val parcel = Parcel.obtain().apply {
            unmarshall(decodedEvent.toByteArray(), 0, decodedEvent.size)
            setDataPosition(0)
        }

        val event = creator.createFromParcel(parcel)
        parcel.recycle()

        emit(event)
    }

    close()
}
