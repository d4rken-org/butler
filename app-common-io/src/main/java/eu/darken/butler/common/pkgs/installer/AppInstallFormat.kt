package eu.darken.butler.common.pkgs.installer

import java.util.Locale

/**
 * App-install containers Butler can install.
 *
 * Detection is by file name, not by MIME type: every bundle format is a plain zip and reports
 * `application/zip`, which says nothing about whether it holds an app. This is the single source of
 * truth for "is this installable".
 */
enum class AppInstallFormat(val extension: String) {
    APK("apk"),
    APKS("apks"),
    XAPK("xapk"),
    APKM("apkm"),
    ;

    /** A container of APKs rather than a single APK. */
    val isBundle: Boolean get() = this != APK

    companion object {
        fun fromFileName(fileName: String): AppInstallFormat? {
            val lower = fileName.lowercase(Locale.ROOT)
            return entries.firstOrNull { lower.endsWith(".${it.extension}") }
        }
    }
}
