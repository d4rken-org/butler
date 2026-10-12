package eu.darken.butler.apps.core

import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.contracts.saver.SaverArguments

/**
 * One app's APK export, shaped for [SaverArguments.Default]: [uri] and [name] go into `sourceUris`
 * and `sourceNames`, [bundle] into `sourceBundles` (empty for an app without splits).
 */
internal data class ApkExport(
    val uri: String,
    val name: String,
    val bundle: List<SaverArguments.BundleMember>,
)

/**
 * An app with split APKs exports as one `.apks` archive, e.g.
 * ```
 * Foo_com.foo_1.0_42.apks
 *   base.apk                  <- /system/app/Foo/Foo.apk
 *   split_config.de.apk
 *   split_config.xxhdpi.apk
 * ```
 * The base is always stored as `base.apk`, whatever its on-device name, so it can be told apart from
 * the splits.
 *
 * Null when there is nothing to export: no [sourceDir], or two members that would collide on one
 * archive entry name.
 */
internal fun apkExport(
    sourceDir: APath<*>?,
    splitSources: Set<APath<*>>?,
    label: String?,
    packageName: String,
    versionName: String?,
    versionCode: Long,
): ApkExport? {
    if (sourceDir == null) return null
    val baseUri = sourceDir.asFileUri()

    val bundle = if (splitSources.isNullOrEmpty()) {
        emptyList()
    } else {
        val members = listOf(SaverArguments.BundleMember(uri = baseUri, entryName = BASE_ENTRY_NAME)) +
            splitSources
                .sortedBy { it.name }
                .map { SaverArguments.BundleMember(uri = it.asFileUri(), entryName = it.name) }
        if (members.distinctBy { it.entryName }.size != members.size) {
            log(TAG, WARN) { "Duplicate entry names in split APKs of $packageName: $members" }
            return null
        }
        members
    }

    return ApkExport(
        uri = baseUri,
        name = apkExportFileName(
            label = label,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            bundle = bundle.isNotEmpty(),
        ),
        bundle = bundle,
    )
}

private fun APath<*>.asFileUri(): String = "file://$path"

private const val BASE_ENTRY_NAME = "base.apk"

private val TAG = logTag("Apps", "ApkExport")
