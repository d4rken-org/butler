package eu.darken.butler.setup.core.shizuku

import eu.darken.butler.common.adb.shizuku.AdbBackend

/**
 * Where a user without any ADB access manager is sent to get one.
 *
 * Flavor bound: the FOSS build cannot point at Google Play, the Play build cannot point users at a
 * sideload page.
 */
interface AdbManagerInstallGuide {
    /** The manager this flavor recommends; its product name labels the install action. */
    val backend: AdbBackend

    val url: String
}
