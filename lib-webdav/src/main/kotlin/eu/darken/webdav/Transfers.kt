package eu.darken.webdav

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.Pipe

internal data class Exchange(val call: Call, val response: Response) : Closeable {
    override fun close() = response.close()
}

internal class OkHttpDownload(
    private val exchange: Exchange,
    override val contentLength: Long?,
    override val totalLength: Long?,
    override val etag: String?,
    override val rangeHonored: Boolean,
) : WebDavDownload {
    private val stream: InputStream = exchange.response.body.byteStream()

    override suspend fun read(buffer: ByteArray, start: Int, length: Int): Int {
        checkBounds(buffer, start, length)
        if (length == 0) return 0
        return try {
            exchange.call.blockingIo { stream.read(buffer, start, length) }
        } catch (error: IOException) {
            throw error.toWebDavException()
        }
    }

    override fun blocking(): InputStream = stream

    /** Cancels first so an unread remainder is dropped instead of drained. */
    override suspend fun close() {
        exchange.call.cancel()
        exchange.close()
    }
}

internal class EmptyDownload(override val totalLength: Long?, override val etag: String?) : WebDavDownload {
    override val contentLength: Long = 0

    override val rangeHonored: Boolean = true

    override suspend fun read(buffer: ByteArray, start: Int, length: Int): Int {
        checkBounds(buffer, start, length)
        return if (length == 0) 0 else -1
    }

    override fun blocking(): InputStream = ByteArrayInputStream(ByteArray(0))

    override suspend fun close() = Unit
}

/**
 * A one-shot PUT whose body is fed through a pipe while the call runs on an I/O thread. A body that ends
 * early never sends its terminating chunk or final byte: [abort] cancels the call before the pipe.
 */
internal class OkHttpUpload(
    client: OkHttpClient,
    request: Request.Builder,
    private val contentLength: Long?,
    private val precondition: Precondition,
) : WebDavUpload {
    private val pipe = Pipe(PIPE_SIZE)
    private val outcome = CompletableFuture<Unit>()
    private val call: Call
    private val lock = Any()
    private var written = 0L
    private var closed = false

    @Volatile private var aborted = false

    init {
        val body =
            object : RequestBody() {
                override fun contentType(): MediaType? = null

                override fun contentLength(): Long = contentLength ?: -1L

                override fun isOneShot(): Boolean = true

                override fun writeTo(sink: BufferedSink) {
                    sink.writeAll(pipe.source)
                }
            }
        call = client.newCall(request.put(body).build())
        ioExecutor.execute {
            try {
                call.execute().use { response ->
                    when (response.code) {
                        200, 201, 204 -> outcome.complete(Unit)
                        else -> outcome.completeExceptionally(statusFailure(response.code, "PUT", precondition))
                    }
                }
            } catch (error: Throwable) {
                outcome.completeExceptionally(error.toWebDavException())
            } finally {
                pipe.cancel()
            }
        }
    }

    override suspend fun write(buffer: ByteArray, start: Int, length: Int) =
        blockingIo(::abort) { writeBlocking(buffer, start, length) }

    private fun writeBlocking(buffer: ByteArray, start: Int, length: Int) {
        checkBounds(buffer, start, length)
        synchronized(lock) {
            check(!closed) { "Upload is closed" }
            if (aborted) throw WebDavException(WebDavException.Kind.OTHER, message = "Upload was aborted")
            if (contentLength != null && written + length > contentLength) {
                abort()
                throw lengthMismatch(written + length)
            }
            written += length
        }
        if (length == 0) return
        try {
            pipe.sink.write(Buffer().write(buffer, start, length), length.toLong())
        } catch (error: IOException) {
            throw failure(error)
        }
    }

    override fun blocking(): OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = writeBlocking(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) = writeBlocking(b, off, len)

            override fun close() = closeBlocking()
        }

    override fun abort() {
        aborted = true
        call.cancel()
        pipe.cancel()
    }

    override suspend fun close() {
        if (!finishBody()) return
        try {
            outcome.await()
        } catch (error: CancellationException) {
            abort()
            throw error
        }
    }

    private fun closeBlocking() {
        if (!finishBody()) return
        try {
            outcome.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: InterruptedException) {
            abort()
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Interrupted while waiting for the upload response")
        }
    }

    /** Returns false when there is nothing to wait for. */
    private fun finishBody(): Boolean {
        synchronized(lock) {
            if (closed || aborted) return false
            closed = true
            if (outcome.isDone) return true
            if (contentLength != null && written != contentLength) {
                abort()
                throw lengthMismatch(written)
            }
        }
        try {
            pipe.sink.close()
        } catch (_: IOException) {
            // The call already ended; its outcome explains why.
        }
        return true
    }

    private fun failure(error: IOException): Throwable {
        if (aborted) return WebDavException(WebDavException.Kind.OTHER, message = "Upload was aborted", cause = error)
        val outcomeError =
            try {
                outcome.get()
                null
            } catch (failed: ExecutionException) {
                failed.cause
            }
        return outcomeError ?: error.toWebDavException()
    }

    private fun lengthMismatch(count: Long) =
        WebDavException(WebDavException.Kind.OTHER, message = "Upload declared $contentLength bytes but got $count")

    private companion object {
        const val PIPE_SIZE = 64L * 1024
    }
}

internal fun checkBounds(buffer: ByteArray, start: Int, length: Int) {
    require(start >= 0 && length >= 0 && start <= buffer.size - length) { "Invalid buffer range" }
}
