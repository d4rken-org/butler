package eu.darken.butler.common.coil

import coil3.request.Options
import coil3.size.pxOrElse

/** Upper bound so a 56dp grid cell never allocates a huge bitmap. */
internal const val MAX_ICON_PX = 192

internal data class IconRasterSize(val width: Int, val height: Int)

/**
 * Dimensions [eu.darken.butler.common.coil.fetchers.AppIconFetcher] rasterizes an app icon to,
 * or `null` when the request has no fixed size and the drawable's intrinsic size decides.
 *
 * [PkgIconKeyer] resolves the same value so a 40dp list icon and a 56dp grid icon of the same
 * package never share a memory cache entry.
 */
internal fun Options.iconRasterSize(): IconRasterSize? {
    val requestedWidth = size.width.pxOrElse { -1 }
    val requestedHeight = size.height.pxOrElse { -1 }
    if (requestedWidth <= 0 || requestedHeight <= 0) return null
    return IconRasterSize(requestedWidth.sanitizeIconSize(), requestedHeight.sanitizeIconSize())
}

internal fun Int.sanitizeIconSize(): Int = if (this <= 0) MAX_ICON_PX else coerceAtMost(MAX_ICON_PX)
