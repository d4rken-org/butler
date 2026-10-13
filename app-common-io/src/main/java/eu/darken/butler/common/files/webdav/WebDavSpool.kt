package eu.darken.butler.common.files.webdav

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scratch files under the app cache that hold the content of a WebDAV upload until its length is
 * known: a PUT always declares its `Content-Length`, an aborted chunked upload can leave a partial
 * file on the server.
 */
@Singleton
class WebDavSpool internal constructor(directory: () -> File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this({ File(context.cacheDir, DIRNAME) })

    /** Swept once before the first spool is handed out: leftovers stem from a process that died mid-upload. */
    private val directory by lazy {
        directory().apply {
            listFiles()?.forEach { it.delete() }
            if (!mkdirs() && !isDirectory) log(TAG, WARN) { "Failed to create spool dir $this" }
        }
    }

    fun create(): File = File.createTempFile("spool_", ".tmp", directory)

    companion object {
        private val TAG = logTag("WebDAV", "Spool")
        private const val DIRNAME = "webdav_spool"
    }
}
