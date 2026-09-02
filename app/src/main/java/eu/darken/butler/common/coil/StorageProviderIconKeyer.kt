package eu.darken.butler.common.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.darken.butler.common.storage.saf.StorageProviderApp
import javax.inject.Inject

/**
 * Cache keyer for the apps behind SAF storage locations, so their icons are memory cached and
 * a list does not flash the fallback on every recomposition.
 *
 * Kept apart from [PkgIconKeyer] so such an app never aliases a real installation's entry. The
 * update time keeps an icon cached before an app update from being served after it.
 */
class StorageProviderIconKeyer @Inject constructor() : Keyer<StorageProviderApp> {

    override fun key(data: StorageProviderApp, options: Options): String = buildString {
        append("storage-provider-icon-")
        append(data.packageName)
        append("-")
        append(data.lastUpdateTime)
        append("-")
        val rasterSize = options.iconRasterSize()
        if (rasterSize != null) {
            append(rasterSize.width)
            append("x")
            append(rasterSize.height)
        } else {
            append("intrinsic")
        }
    }
}
