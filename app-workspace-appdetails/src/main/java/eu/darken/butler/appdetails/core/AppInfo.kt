package eu.darken.butler.appdetails.core

import eu.darken.butler.common.ca.CaDrawable
import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.pkgs.Pkg
import kotlin.time.Instant

/**
 * Simplified app information for display in app details workspace
 */
data class AppInfo(
    val packageName: String,
    val label: CaString,
    val icon: CaDrawable?,
    val versionName: String?,
    val versionCode: Long,
    val appSize: Long?,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
    val pkgId: Pkg.Id,
)
