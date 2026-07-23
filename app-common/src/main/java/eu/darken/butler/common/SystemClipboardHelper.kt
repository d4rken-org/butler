package eu.darken.butler.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.text.HtmlCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

@Singleton
class SystemClipboardHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val clipboard: ClipboardManager by lazy {
        return@lazy if (Looper.getMainLooper() == Looper.myLooper()) {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        } else {
            // java.lang.RuntimeException · Can't create handler inside thread that has not called Looper.prepare()
            log(TAG) { "Clipboard is not initialized on the main thread, applying workaround" }
            val lock = ReentrantLock()
            val lockCondition = lock.newCondition()

            var clipboardManager: ClipboardManager? = null

            Handler(Looper.getMainLooper()).postAtFrontOfQueue {
                lock.withLock {
                    clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    lockCondition.signal()
                }
            }

            lock.withLock {
                // Predicate loop: guards against a lost wakeup if main signals before we await.
                while (clipboardManager == null) lockCondition.await()
            }

            clipboardManager!!
        }
    }

    fun copyToClipboard(text: String) {
        val clip = ClipData.newPlainText(context.getString(R.string.app_name), text)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * Text representation of the primary clip. Prefers a plain-text item, then converts an HTML
     * item via [HtmlCompat], and finally falls back to a URI item's literal string. A URI's
     * *content* is never dereferenced here - that would be an unbounded, provider-controlled read.
     */
    fun getClipboardText(): String? {
        val primaryClip = clipboard.primaryClip ?: return null
        if (primaryClip.itemCount == 0) return null

        val item = primaryClip.getItemAt(0)
        item.text?.let { return it.toString() }
        item.htmlText?.let { return HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() }
        item.uri?.let { return it.toString() }
        return null
    }

    fun hasClipboardContent(): Boolean {
        if (!clipboard.hasPrimaryClip()) return false
        // "text/*" covers text/plain, text/html and text/uri-list.
        return clipboard.primaryClipDescription?.hasMimeType("text/*") == true
    }

    companion object {
        private val TAG = logTag("SystemClipboardHelper")
    }
}