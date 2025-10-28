package eu.darken.butler.apps.core.engine

import android.content.Context
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.InstallDetails
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.features.InstallerInfo
import kotlin.time.Instant

data class AppItem(
    val pkg: Installed,
    val label: CaString,
    val icon: CaDrawable?,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val appSize: Long?,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
    val isUpdatedSystemApp: Boolean,
    val installedAt: Instant?,
    val updatedAt: Instant?,
    val installerInfo: InstallerInfo?,
) {
    val id: Pkg.Id = pkg.id

    companion object {
        fun from(pkg: Installed, appSize: Long? = null): AppItem {
            val installDetails = pkg as? InstallDetails
            val pkgInfo = pkg.packageInfo

            return AppItem(
                pkg = pkg,
                label = pkg.label ?: pkg.id.name.toCaString(),
                icon = pkg.icon,
                packageName = pkg.id.name,
                versionName = pkgInfo.versionName,
                versionCode = pkgInfo.longVersionCode,
                appSize = appSize,
                isSystemApp = installDetails?.isSystemApp == true,
                isEnabled = installDetails?.isEnabled ?: true,
                isUpdatedSystemApp = installDetails?.let {
                    it.isSystemApp && (pkgInfo.applicationInfo?.flags
                        ?: 0) and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                } ?: false,
                installedAt = installDetails?.installedAt,
                updatedAt = installDetails?.updatedAt,
                installerInfo = installDetails?.installerInfo,
            )
        }
    }
}

fun AppItem.matchesSearch(context: Context, query: String): Boolean {
    if (query.isBlank()) return true
    val normalizedQuery = query.trim().lowercase()
    return label.get(context).lowercase().contains(normalizedQuery) ||
        packageName.lowercase().contains(normalizedQuery)
}

fun Collection<AppItem>.sortedBy(context: Context, settings: SortSettings): List<AppItem> {
    val sorted = when (settings.mode) {
        SortSettings.Mode.NAME -> sortedBy { it.label.get(context).lowercase() }
        SortSettings.Mode.SIZE -> sortedBy { it.appSize ?: 0L }
        SortSettings.Mode.INSTALL_DATE -> sortedBy { it.installedAt }
        SortSettings.Mode.UPDATE_DATE -> sortedBy { it.updatedAt }
        SortSettings.Mode.PACKAGE_NAME -> sortedBy { it.packageName }
    }
    return if (settings.reversed) sorted.reversed() else sorted
}
