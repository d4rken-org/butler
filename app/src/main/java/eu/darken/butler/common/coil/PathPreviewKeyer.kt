package eu.darken.butler.common.coil

import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import eu.darken.butler.common.debug.Bugs
import eu.darken.butler.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.themeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Cache keyer for APathLookup that includes theme state.
 * Ensures text previews are cached separately per theme to avoid showing
 * stale bitmaps with wrong theme colors after theme changes.
 */
class PathPreviewKeyer @Inject constructor(
    private val generalSettings: GeneralSettings,
) : Keyer<APathLookup<*>> {

    override fun key(data: APathLookup<*>, options: Options): String {
        // Read the whole theme in a single blocking dispatch (DataStore is warm once the UI is
        // themed) rather than one runBlocking per theme field.
        val themeState = runBlocking { generalSettings.themeState.first() }

        val sizeWidth = (options.size.width as? Dimension.Pixels)?.px ?: 0
        val sizeHeight = (options.size.height as? Dimension.Pixels)?.px ?: 0

        // Diagnostic for cross-size re-decodes: reveals when the same path is requested at
        // multiple sizes (each size is a distinct cache entry due to the WxH key component).
        if (Bugs.isTrace) {
            log(TAG, VERBOSE) { "key: ${sizeWidth}x${sizeHeight} for ${data.lookedUp}" }
        }

        // Include path, theme, request size, plus file size + mtime so a replaced file at the same
        // path invalidates its cached (generated) preview.
        // Format: "path-preview-<path-hash>-<mode>-<style>-<color>-<w>x<h>-<bytes>-<mtimeMs>"
        return buildString {
            append("path-preview-")
            append(data.path.hashCode())
            append("-")
            append(themeState.mode.name.lowercase())
            append("-")
            append(themeState.style.name.lowercase())
            append("-")
            append(themeState.color.name.lowercase())
            append("-")
            append(sizeWidth)
            append("x")
            append(sizeHeight)
            append("-")
            append(data.size ?: -1L)
            append("-")
            append(data.modifiedAt?.toEpochMilliseconds() ?: -1L)
        }
    }

    companion object {
        private val TAG = logTag("Coil", "PathPreviewKeyer")
    }
}
