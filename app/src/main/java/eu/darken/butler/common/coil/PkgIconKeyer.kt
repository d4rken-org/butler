package eu.darken.butler.common.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.darken.butler.common.pkgs.features.Installed
import javax.inject.Inject

/**
 * Cache keyer for installed packages, so app icons can be memory cached.
 *
 * The key is typed to [Installed] rather than `Pkg` so that stubs, archives and known-pkg
 * references sharing a package name are never aliased onto a real installation.
 *
 * It carries a revision (version code + last update time) because [eu.darken.butler.common.pkgs.PkgRepo]
 * refreshes in-process on package events: an app update, an uninstall/reinstall or an
 * archived/normal transition must not keep serving the previously cached bitmap.
 *
 * It also carries the raster size [eu.darken.butler.common.coil.fetchers.AppIconFetcher] will produce,
 * because Coil hands out a cached bitmap without comparing its dimensions: without this a small list
 * icon and a larger grid icon would share one entry and whichever loaded first would win.
 */
class PkgIconKeyer @Inject constructor() : Keyer<Installed> {

    override fun key(data: Installed, options: Options): String = buildString {
        append("pkg-icon-")
        append(data.id.name)
        append("-u")
        append(data.userHandle.handleId)
        append("-")
        append(data.javaClass.name)
        append("-")
        append(data.versionCode)
        append("-")
        append(data.packageInfo.lastUpdateTime)
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
