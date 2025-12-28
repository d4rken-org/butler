package eu.darken.butler.apps.core.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.pkgs.AKnownPkg
import eu.darken.butler.common.pkgs.Pkg
import eu.darken.butler.common.pkgs.features.InstallDetails
import eu.darken.butler.common.pkgs.features.Installed
import eu.darken.butler.common.pkgs.features.InstallerInfo
import eu.darken.butler.common.pkgs.features.SourceAvailable
import eu.darken.butler.common.user.UserProfile2
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
    val isSplitApk: Boolean,
    val isDebuggable: Boolean,
    val userProfile: UserProfile2,
) {
    val id: Pkg.Id = pkg.id

    val isSideloaded: Boolean
        get() {
            if (isSystemApp) return false
            val installers = installerInfo?.allInstallers ?: return true
            return installers.none { it.id == AKnownPkg.GooglePlay.id }
        }

    val tags: List<AppTag>
        get() = buildList {
            if (userProfile.handle.handleId != 0) {
                add(AppTag.User(userProfile.handle.handleId, userProfile.label))
            }
            if (!isEnabled) add(AppTag.Disabled)
            if (isSystemApp) add(AppTag.System)
            if (isSideloaded) add(AppTag.Sideloaded)
            if (isUpdatedSystemApp) add(AppTag.UpdatedSystem)
            if (isDebuggable) add(AppTag.Debug)
            if (isSplitApk) add(AppTag.SplitApk)
        }.sortedBy { it.priority }

    companion object {
        fun from(pkg: Installed, userProfile: UserProfile2, appSize: Long? = null): AppItem {
            val installDetails = pkg as? InstallDetails
            val sourceAvailable = pkg as? SourceAvailable
            val pkgInfo = pkg.packageInfo
            val appInfo = pkgInfo.applicationInfo

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
                    it.isSystemApp && (appInfo?.flags ?: 0) and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                } ?: false,
                installedAt = installDetails?.installedAt,
                updatedAt = installDetails?.updatedAt,
                installerInfo = installDetails?.installerInfo,
                isSplitApk = sourceAvailable?.splitSources?.isNotEmpty() == true,
                isDebuggable = appInfo?.let { it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 } ?: false,
                userProfile = userProfile,
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
