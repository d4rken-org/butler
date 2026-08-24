package eu.darken.butler.common.files.io

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import okio.FileHandle
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

interface ProxyPfdFactory {
    /**
     * Wraps [fileHandle] in a seekable [ParcelFileDescriptor] whose reads/writes are served by the
     * handle.
     *
     * Ownership of [fileHandle] transfers at call entry: on failure this closes it before throwing,
     * on success the descriptor's release callback closes it. Callers must not close it themselves.
     */
    fun create(fileHandle: FileHandle, mode: String): ParcelFileDescriptor
}

@Singleton
class DefaultProxyPfdFactory internal constructor(
    private val opener: (Int, ProxyFileDescriptorCallback, Handler) -> ParcelFileDescriptor,
) : ProxyPfdFactory {

    @Inject constructor(@ApplicationContext context: Context) : this(
        context.getSystemService(StorageManager::class.java)::openProxyFileDescriptor,
    )

    // A single callback thread would serialize every FUSE read across all consumers of this
    // app-wide factory, while a thread per descriptor is unbounded: provider clients can hold a
    // descriptor open for as long as they like.
    private val handlers: List<Handler> = (0 until POOL_SIZE).map { index ->
        Handler(HandlerThread("ProxyPfd-$index").apply { start() }.looper)
    }
    private val handlerIndex = AtomicInteger(0)

    private fun nextHandler(): Handler = handlers[handlerIndex.getAndUpdate { (it + 1) % handlers.size }]

    override fun create(fileHandle: FileHandle, mode: String): ParcelFileDescriptor {
        var handedOver = false
        try {
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = try {
                    fileHandle.size()
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Proxy onGetSize failed: ${e.asLog()}" }
                    throw e.toProxyErrno("onGetSize")
                }

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                    val bytesRead = fileHandle.read(offset, data, 0, minOf(size, data.size))
                    if (bytesRead < 0) 0 else bytesRead
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Proxy onRead failed (offset=$offset, size=$size): ${e.asLog()}" }
                    throw e.toProxyErrno("onRead")
                }

                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = try {
                    fileHandle.write(offset, data, 0, minOf(size, data.size))
                    minOf(size, data.size)
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Proxy onWrite failed (offset=$offset, size=$size): ${e.asLog()}" }
                    throw e.toProxyErrno("onWrite")
                }

                override fun onFsync() = try {
                    fileHandle.flush()
                } catch (e: Exception) {
                    log(TAG, ERROR) { "Proxy onFsync failed: ${e.asLog()}" }
                    throw e.toProxyErrno("onFsync")
                }

                override fun onRelease() {
                    try {
                        fileHandle.close()
                    } catch (e: Exception) {
                        log(TAG, WARN) { "Proxy onRelease close failed: ${e.asLog()}" }
                    }
                }
            }
            return opener(ParcelFileDescriptor.parseMode(mode), callback, nextHandler()).also {
                handedOver = true
            }
        } finally {
            if (!handedOver) {
                try {
                    fileHandle.close()
                } catch (e: Exception) {
                    log(TAG, WARN) { "Cleanup close failed: ${e.asLog()}" }
                }
            }
        }
    }

    private fun Throwable.toProxyErrno(function: String): ErrnoException {
        if (this is ErrnoException) return this
        return ErrnoException(function, OsConstants.EIO).apply { initCause(this@toProxyErrno) }
    }

    companion object {
        private const val POOL_SIZE = 4
        private val TAG = logTag("Gateway", "ProxyPfdFactory")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProxyPfdFactoryModule {
    @Binds
    abstract fun bind(impl: DefaultProxyPfdFactory): ProxyPfdFactory
}
