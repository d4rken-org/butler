package eu.darken.butler.common.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.theming.themeState
import eu.darken.butler.main.core.GeneralSettings
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
        // Get current theme state synchronously
        val themeState = runBlocking { generalSettings.themeState.first() }

        // Include path and theme in cache key
        // Format: "path-preview-<path-hash>-<mode>-<style>-<color>"
        return buildString {
            append("path-preview-")
            append(data.path.hashCode())
            append("-")
            append(themeState.mode.name.lowercase())
            append("-")
            append(themeState.style.name.lowercase())
            append("-")
            append(themeState.color.name.lowercase())
        }
    }
}
