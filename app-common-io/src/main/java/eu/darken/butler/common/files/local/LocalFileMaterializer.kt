package eu.darken.butler.common.files.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Materializes any [APath] into a real local [File] for the duration of a scoped block.
 *
 * Some Android APIs (e.g. `PackageManager.getPackageArchiveInfo`) require a real filesystem path and
 * cannot consume a SAF `content://` URI or a remote backend. This helper bridges that gap:
 *
 * - [LocalPath]: the backing file is handed over directly (no copy).
 * - Every other backend (SAF today; FTP/SFTP/HTTP in the future): the content is streamed into a
 *   temp file under the app cache, passed to the block, and deleted again afterwards.
 *
 * The [File] passed to the block is valid ONLY for the duration of the block. Anything that captures
 * its path (e.g. `PackageInfo.applicationInfo.sourceDir`) becomes invalid once the block returns.
 */
@Singleton
class LocalFileMaterializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val gatewaySwitch: GatewaySwitch,
) {

    private val tempDir by lazy {
        File(context.cacheDir, TEMP_DIRNAME).apply {
            if (!mkdirs() && !exists()) log(TAG, WARN) { "Failed to create temp dir $this" }
        }
    }
    private val swept = AtomicBoolean(false)
    private val sweepMutex = Mutex()

    /**
     * Runs [block] with a guaranteed real local [File] for [path], cleaning up any temp copy afterwards.
     */
    suspend fun <R> useLocalFile(path: APath<*>, block: suspend (File) -> R): R {
        // LocalPath already has a real backing file - hand it over without copying.
        if (path is LocalPath) return block(path.file)

        sweepStaleFiles()

        val suffix = path.name.substringAfterLast('.', "").let { if (it.isBlank()) ".tmp" else ".$it" }
        // Tracked separately so the finally can clean up even if creation+copy is cancelled mid-flight.
        var tempFile: File? = null
        try {
            withContext(dispatcherProvider.IO) {
                // Create and remember the temp file in the same step the copy runs in, so cancellation
                // can never leave a created-but-untracked file behind.
                val materialized = File.createTempFile("mat_", suffix, tempDir).also { tempFile = it }
                log(TAG, VERBOSE) { "useLocalFile($path): materializing to $materialized" }

                // The InputStream owns its ParcelFileDescriptor (SAF), so we don't need to lease the
                // gateway resource here. Connection-pooled backends (FTP/SFTP) may want one in future.
                // A single blocking read() can't be pre-empted (FileInputStream isn't interruptible),
                // but cancellation is observed between chunks via ensureActive().
                gatewaySwitch.openInputStream(path).use { input ->
                    materialized.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
            }
            return block(tempFile!!)
        } finally {
            // Delete even on cancellation - the temp file is process-local scratch space. Cleanup
            // failures must never replace the original (possibly cancellation) outcome.
            tempFile?.let { file ->
                withContext(NonCancellable + dispatcherProvider.IO) {
                    try {
                        if (!file.delete() && file.exists()) {
                            log(TAG, WARN) { "useLocalFile($path): failed to delete temp $file" }
                        }
                    } catch (e: Exception) {
                        log(TAG, WARN) { "useLocalFile($path): error deleting temp $file: ${e.asLog()}" }
                    }
                }
            }
        }
    }

    /**
     * Deletes scratch files left behind by a previous process run (e.g. a crash mid-block). Runs once,
     * before this instance creates any temp file of its own, so it can never race an in-flight copy.
     */
    private suspend fun sweepStaleFiles() {
        if (swept.get()) return
        sweepMutex.withLock {
            if (!swept.get()) {
                withContext(dispatcherProvider.IO) {
                    try {
                        tempDir.listFiles()?.forEach { it.delete() }
                    } catch (e: Exception) {
                        log(TAG, WARN) { "Failed to sweep stale temp files: ${e.asLog()}" }
                    }
                }
                swept.set(true)
            }
        }
    }

    companion object {
        private val TAG = logTag("Gateway", "Materializer")
        private const val TEMP_DIRNAME = "materialized"
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
