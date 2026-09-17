package eu.darken.butler.setup.core.shizuku

import androidx.annotation.StringRes

/**
 * Where a user without any ADB access manager is sent to get one.
 *
 * Flavor bound: the FOSS build cannot point at Google Play, the Play build cannot point users at a
 * sideload page.
 */
interface AdbManagerInstallGuide {
    /** Name of the manager app this flavor recommends. */
    @get:StringRes val labelRes: Int

    val url: String
}
