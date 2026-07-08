package eu.darken.butler.common.coil

import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.main.core.GeneralSettings
import eu.darken.butler.main.core.themeStateBlocking
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
        val themeState = generalSettings.themeStateBlocking

        val sizeWidth = (options.size.width as? Dimension.Pixels)?.px ?: 0
        val sizeHeight = (options.size.height as? Dimension.Pixels)?.px ?: 0

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
}
