package eu.darken.butler.viewer.core

import android.graphics.BitmapRegionDecoder
import android.os.Build

/**
 * The formats [BitmapRegionDecoder] can actually decode regions from - the decoder behind
 * telephoto's tiled sub-sampling and behind the viewer's structure check.
 *
 * Telephoto itself only refuses SVG, GIF and AVIF, which is far more permissive than the decoder:
 * a perfectly valid BMP (or HEIF below API 28, and minSdk is 26) would be handed to the tile
 * decoder, fail there, and leave a blank canvas with no error. Anything outside this set has to go
 * to the Coil painter fallback, which handles it.
 */
internal object SubSamplableFormats {

    private val ALWAYS = setOf(
        "image/jpeg",
        "image/png",
        "image/webp",
    )

    /** BitmapRegionDecoder gained HEIF support in API 28. */
    private val SINCE_API_28 = setOf(
        "image/heif",
        "image/heic",
    )

    fun supports(format: String?): Boolean = when {
        format == null -> false
        format in ALWAYS -> true
        format in SINCE_API_28 -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        else -> false
    }
}
