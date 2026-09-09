package eu.darken.butler.apps.core

import eu.darken.butler.apps.core.details.normalizedAppLabel
import eu.darken.butler.common.files.sanitizeForFileName

/**
 * Name an exported APK is written under, e.g. `Butler_eu.darken.butler_0.1.0-beta0_1000000.apk`.
 * Not localized: this is a file name, not UI text.
 *
 * The label is dropped when the app reports none — both AppItem and AppInfo substitute the package
 * name then — and so is a version name the app does not report. The version code always stays: it
 * is the one field that is guaranteed, and it tells two builds carrying the same version string
 * apart.
 */
internal fun apkExportFileName(
    label: String?,
    packageName: String,
    versionName: String?,
    versionCode: Long,
): String {
    // normalizedAppLabel runs before sanitizing: a package name past MAX_LABEL_LENGTH would survive
    // truncation as a shortened string that no longer equals packageName, giving
    // "<truncated pkg>_<pkg>_…".
    var labelPart = normalizedAppLabel(label, packageName)
        ?.let { sanitizeForFileName(it, MAX_LABEL_LENGTH) }
        ?.takeIf { it.isNotBlank() }
    var versionPart = versionName
        ?.let { sanitizeForFileName(it, MAX_VERSION_LENGTH) }
        ?.takeIf { it.isNotBlank() }

    fun assemble(): String = listOfNotNull(labelPart, packageName, versionPart, versionCode.toString())
        .joinToString("_")
        .plus(".apk")

    // Code-point caps alone do not bound a name: 48 CJK label characters plus a 32 character CJK
    // version name are 240 UTF-8 bytes before the package name is even added. Shrink the two
    // descriptive parts until the name fits; "<packageName>_<versionCode>.apk" is never shortened,
    // a truncated package name would name the wrong app.
    while (labelPart != null && assemble().utf8Size() > MAX_NAME_BYTES) {
        labelPart = labelPart.dropLastCodePoint()
    }
    while (versionPart != null && assemble().utf8Size() > MAX_NAME_BYTES) {
        versionPart = versionPart.dropLastCodePoint()
    }

    return assemble()
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

/** Null once nothing is left, so a shrinking part drops out of the name instead of going empty. */
private fun String?.dropLastCodePoint(): String? {
    val value = this ?: return null
    if (value.isEmpty()) return null
    return value.substring(0, value.offsetByCodePoints(value.length, -1)).takeIf { it.isNotEmpty() }
}

private const val MAX_LABEL_LENGTH = 48
private const val MAX_VERSION_LENGTH = 32

/** Filesystem cap on a path component, minus room for SaveFilesOperation's " (999)" suffix. */
private const val MAX_NAME_BYTES = 247
