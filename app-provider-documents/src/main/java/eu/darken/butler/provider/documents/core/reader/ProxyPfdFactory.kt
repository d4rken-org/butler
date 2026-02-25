package eu.darken.butler.provider.documents.core.reader

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
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
import javax.inject.Inject
import javax.inject.Singleton

interface ProxyPfdFactory {
    fun create(fileHandle: FileHandle, mode: String): ParcelFileDescriptor
}

@Singleton
class DefaultProxyPfdFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProxyPfdFactory {

    private val storageManager: StorageManager = context.getSystemService(StorageManager::class.java)
    private val callbackThread = HandlerThread("DocumentReader-proxy").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    override fun create(fileHandle: FileHandle, mode: String): ParcelFileDescriptor {
        val callback = object : android.os.ProxyFileDescriptorCallback() {
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
        return try {
            storageManager.openProxyFileDescriptor(
                ParcelFileDescriptor.parseMode(mode),
                callback,
                callbackHandler,
            )
        } catch (e: Exception) {
            try {
                fileHandle.close()
            } catch (_: Exception) {
                // Best effort cleanup
            }
            throw e
        }
    }

    private fun Throwable.toProxyErrno(function: String): ErrnoException {
        if (this is ErrnoException) return this
        return ErrnoException(function, OsConstants.EIO).apply { initCause(this@toProxyErrno) }
    }

    companion object {
        private val TAG = logTag("Provider", "Documents", "ProxyPfdFactory")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ProxyPfdFactoryModule {
    @Binds
    abstract fun bind(impl: DefaultProxyPfdFactory): ProxyPfdFactory
}
