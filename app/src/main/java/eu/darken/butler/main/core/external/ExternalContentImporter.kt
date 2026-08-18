package eu.darken.butler.main.core.external

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.cache.CacheRepo
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.MimeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.uuid.Uuid

/**
 * Copies content another app handed to Butler into the app cache, so it can be opened as a real
 * local file (the Viewer needs a path, not a stream).
 *
 * Each import gets its own random sub-directory, which keeps the original display name usable even
 * when two apps hand over files with the same name.
 *
 * Imports are not cleaned up when the workspace that uses them closes: the file has to outlive the
 * arrival dialog and the workspace may be restored from a session later. Instead a lazy sweep on
 * first import per process drops everything older than [MAX_AGE_MS]. A Viewer workspace restored
 * from a week-old session can therefore point at a swept file; it then shows the viewer's existing
 * missing-file failure state, which is the accepted trade-off against unbounded cache growth.
 *
 * Raw [File] and stream use is deliberate here and follows the `LocalFileMaterializer` precedent:
 * this only ever touches Butler's own cache directory, not user storage.
 */
@Singleton
class ExternalContentImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    private val cacheRepo: CacheRepo,
) {

    private val baseDir: File
        get() = File(context.cacheDir, IMPORT_DIRNAME)

    private val swept = AtomicBoolean(false)
    private val sweepMutex = Mutex()

    /**
     * Streams [uri] into the cache and returns the resulting file, or null if the content could not
     * be imported (no stream, no space, read failure).
     */
    suspend fun importToCache(uri: Uri, displayName: String, mime: MimeInfo?): LocalPath? {
        sweepStaleImports()

        val targetDir = File(baseDir, Uuid.random().toString())
        val target = File(targetDir, buildFileName(displayName, mime))
        log(TAG) { "importToCache($uri, $displayName, $mime) -> $target" }

        try {
            return withContext(dispatcherProvider.IO) {
                // Providers often report no size at all, so an unknown size is guarded with the same
                // budget the copy loop rechecks against instead of skipping the check.
                val reported = reportedSize(uri)
                if (!cacheRepo.canSpare(reported ?: SPACE_RECHECK_INTERVAL)) {
                    log(TAG, WARN) { "Not enough cache space for $reported bytes, aborting import of $uri" }
                    return@withContext null
                }

                if (!targetDir.mkdirs() && !targetDir.exists()) {
                    log(TAG, WARN) { "Failed to create import dir $targetDir" }
                    return@withContext null
                }

                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    log(TAG, WARN) { "No input stream for $uri" }
                    cleanUp(targetDir)
                    return@withContext null
                }

                var spaceOk = true
                input.use { source ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        var sinceCheck = 0L
                        while (true) {
                            ensureActive()
                            val read = source.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            sinceCheck += read
                            if (sinceCheck >= SPACE_RECHECK_INTERVAL) {
                                sinceCheck = 0L
                                if (!cacheRepo.canSpare(SPACE_RECHECK_INTERVAL)) {
                                    log(TAG, WARN) { "Cache space ran out while importing $uri" }
                                    spaceOk = false
                                    break
                                }
                            }
                        }
                        output.flush()
                    }
                }

                if (!spaceOk) {
                    cleanUp(targetDir)
                    return@withContext null
                }

                LocalPath.build(target)
            }
        } catch (e: CancellationException) {
            cleanUp(targetDir)
            throw e
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to import $uri: ${e.asLog()}" }
            cleanUp(targetDir)
            return null
        }
    }

    private suspend fun cleanUp(targetDir: File) = withContext(NonCancellable + dispatcherProvider.IO) {
        try {
            if (!targetDir.deleteRecursively() && targetDir.exists()) {
                log(TAG, WARN) { "Failed to clean up partial import $targetDir" }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Error cleaning up partial import $targetDir: ${e.asLog()}" }
        }
    }

    private fun reportedSize(uri: Uri): Long? = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> uri.path?.let { File(it).length() }?.takeIf { it > 0L }
        else -> try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst() && index >= 0 && !cursor.isNull(index)) {
                    cursor.getLong(index).takeIf { it > 0L }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            log(TAG, WARN) { "Failed to query size of $uri: ${e.asLog()}" }
            null
        }
    }

    /**
     * The display name comes from another app, so it may carry path separators or be empty. The
     * Viewer picks its decoder by extension, so extensionless viewable content also gets one here.
     */
    private fun buildFileName(displayName: String, mime: MimeInfo?): String {
        val sanitized = displayName
            .filterNot { it == '/' || it == '\\' || it == '\u0000' }
            .trim()
            .ifBlank { FALLBACK_FILENAME }

        if (mime?.isViewable != true || mime.hasMatchingViewableExtension(sanitized)) return sanitized

        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.rawType)
            ?: VIEWABLE_EXTENSIONS[mime.rawType]
            ?: return sanitized

        return "$sanitized.$extension"
    }

    /**
     * Drops imports left behind by earlier runs. Runs once per process, before this instance writes
     * anything of its own, so it can never race an in-flight import.
     */
    private suspend fun sweepStaleImports() {
        if (swept.get()) return
        sweepMutex.withLock {
            if (swept.get()) return@withLock
            withContext(dispatcherProvider.IO) {
                try {
                    val cutOff = System.currentTimeMillis() - MAX_AGE_MS
                    baseDir.listFiles()?.forEach { entry ->
                        if (entry.lastModified() < cutOff) {
                            log(TAG, VERBOSE) { "Sweeping stale import $entry" }
                            entry.deleteRecursively()
                        }
                    }
                } catch (e: Exception) {
                    log(TAG, WARN) { "Failed to sweep stale imports: ${e.asLog()}" }
                }
            }
            swept.set(true)
        }
    }

    companion object {
        private val TAG = logTag("Main", "ExternalOpen", "Importer")
        private const val IMPORT_DIRNAME = "external_open"
        private const val FALLBACK_FILENAME = "file"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val SPACE_RECHECK_INTERVAL = 8 * 1024 * 1024L
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L
        /** Robolectric and some ROMs have an empty [MimeTypeMap], these are the types we care about. */
        private val VIEWABLE_EXTENSIONS = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/gif" to "gif",
            "image/webp" to "webp",
            "image/bmp" to "bmp",
            "image/heic" to "heic",
            "image/heif" to "heif",
            "image/avif" to "avif",
            "image/svg+xml" to "svg",
            "application/pdf" to "pdf",
            MimeInfo.MIME_APK to "apk",
        )
    }
}
