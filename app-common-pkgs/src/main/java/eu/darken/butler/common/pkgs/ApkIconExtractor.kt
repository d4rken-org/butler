package eu.darken.butler.common.pkgs

import android.content.Context
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.preview.PreviewBudget
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Extracts an APK's launcher icon from a seekable [ParcelFileDescriptor] WITHOUT copying the archive,
 * using the framework's public resource-loader API (API 30+).
 *
 * The framework parses `resources.arsc`/binary XML and composites adaptive icons for us, so the result
 * is pixel-correct where a hand-rolled parser could not be. Returns null on API < 30, a non-seekable
 * descriptor, or any failure — callers fall back to a placeholder. The [pfd] is NOT closed here.
 */
@Singleton
class ApkIconExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) {

    suspend fun extract(pfd: ParcelFileDescriptor, targetPx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            withContext(dispatcherProvider.IO) { extractApi30(pfd, targetPx) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Broad by design: resource-loader / linkage / OOM failures must degrade to a fallback,
            // never crash a preview. CancellationException is rethrown above.
            log(TAG, WARN) { "extract() failed: ${e.asLog()}" }
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun extractApi30(pfd: ParcelFileDescriptor, targetPx: Int): Bitmap? {
        if (pfd.statSize < 0) return null // not seekable -> loadFromApk can't parse the central directory

        val provider = ResourcesProvider.loadFromApk(pfd)
        try {
            val loader = ResourcesLoader().apply { addProvider(provider) }
            // A fresh, isolated Resources we can safely mutate; the loader overrides same-id app resources.
            val res = context.createConfigurationContext(context.resources.configuration).resources
            try {
                res.addLoaders(loader)
                val iconId = readIconId(res) ?: return null
                val drawable = try {
                    res.getDrawableForDensity(iconId, res.displayMetrics.densityDpi, null)
                } catch (e: Resources.NotFoundException) {
                    log(TAG, WARN) { "icon 0x${iconId.toString(16)} not resolvable: ${e.asLog()}" }
                    null
                } ?: return null
                // Rasterize while provider/loader/resources are still alive (drawables bind lazily).
                return rasterize(drawable, PreviewBudget.resolveEdge(targetPx, max = PreviewBudget.MAX_ICON_DIM))
            } finally {
                runCatching { res.removeLoaders(loader) }
            }
        } finally {
            // Release the native APK resource table (ResourcesProvider is AutoCloseable on API 30+).
            runCatching { provider.close() }
        }
    }

    /** Reads `application@icon` (falling back to `@roundIcon`) from the foreign manifest via the loader. */
    private fun readIconId(res: Resources): Int? {
        var parser: XmlResourceParser? = null
        return try {
            parser = res.assets.openXmlResourceParser("AndroidManifest.xml")
            var icon = 0
            var round = 0
            var type = parser.eventType
            while (type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && parser.name == "application") {
                    icon = parser.getAttributeResourceValue(ANDROID_NS, "icon", 0)
                    round = parser.getAttributeResourceValue(ANDROID_NS, "roundIcon", 0)
                    break
                }
                type = parser.next()
            }
            (icon.takeIf { it != 0 } ?: round).takeIf { it != 0 }
        } catch (e: Exception) {
            log(TAG, WARN) { "readIconId failed: ${e.asLog()}" }
            null
        } finally {
            runCatching { parser?.close() }
        }
    }

    private fun rasterize(drawable: Drawable, edge: Int): Bitmap? {
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: edge
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: edge
        val scale = minOf(edge.toFloat() / w, edge.toFloat() / h, 1f)
        val outW = (w * scale).toInt().coerceIn(1, PreviewBudget.MAX_ICON_DIM)
        val outH = (h * scale).toInt().coerceIn(1, PreviewBudget.MAX_ICON_DIM)
        return try {
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, outW, outH)
            drawable.draw(Canvas(bmp))
            bmp
        } catch (e: OutOfMemoryError) {
            log(TAG, WARN) { "rasterize OOM at ${outW}x$outH" }
            null
        }
    }

    companion object {
        private val TAG = logTag("ApkIconExtractor")
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
