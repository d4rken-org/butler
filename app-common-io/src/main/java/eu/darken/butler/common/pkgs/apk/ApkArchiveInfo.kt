package eu.darken.butler.common.pkgs.apk

import android.graphics.Bitmap
import eu.darken.butler.common.pkgs.Pkg

/**
 * Everything an APK archive tells us about itself. Produced by [ApkArchiveParser], either from a
 * file on disk or from an installed package's [android.content.pm.PackageInfo].
 */
data class ApkArchiveInfo(
    val id: Pkg.Id,
    val label: String? = null,
    val icon: Bitmap? = null,
    val versionName: String? = null,
    val versionCode: Long = 0L,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val requestedPermissions: List<String> = emptyList(),
    val signatures: List<ApkSignature> = emptyList(),
)

/** A signing certificate of an APK: who it claims to be, and the fingerprint that proves it. */
data class ApkSignature(
    val subjectDn: String?,
    val sha256: String,
)

/** `name (code)` where a version name exists, otherwise just the code - never `null (123)`. */
fun apkVersionText(versionName: String?, versionCode: Long): String =
    if (versionName.isNullOrBlank()) "$versionCode" else "$versionName ($versionCode)"
