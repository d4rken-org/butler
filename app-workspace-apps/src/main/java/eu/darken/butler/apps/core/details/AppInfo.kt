package eu.darken.butler.apps.core.details

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.InstallDetails
import eu.darken.butler.common.pkgs.features.InstallId
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.features.InstallerInfo
import eu.darken.butler.common.pkgs.isEnabled
import eu.darken.butler.common.pkgs.isSystemApp
import kotlin.time.Instant

data class AppInfo(
    val install: Installed,
    val appSize: Long? = null,
    val dataSize: Long? = null,
    val cacheSize: Long? = null,
) {
    val id: Pkg.Id
        get() = install.id
    val installId: InstallId
        get() = install.installId

    val label: CaString
        get() = install.label ?: install.id.name.toCaString()
    val packageName: String
        get() = install.packageName

    val isEnabled: Boolean
        get() = install.isEnabled

    val versionName: String?
        get() = install.versionName
    val versionCode: Long
        get() = install.versionCode

    val installedAt: Instant?
        get() = (install as? InstallDetails)?.installedAt
    val updatedAt: Instant?
        get() = (install as? InstallDetails)?.updatedAt

    val isSystemApp: Boolean
        get() = install.isSystemApp

    val targetSdk: Int?
        get() = install.applicationInfo?.targetSdkVersion
    val minSdk: Int?
        get() = install.applicationInfo?.minSdkVersion

    val installerInfo: InstallerInfo?
        get() = (install as? InstallDetails)?.installerInfo
}
