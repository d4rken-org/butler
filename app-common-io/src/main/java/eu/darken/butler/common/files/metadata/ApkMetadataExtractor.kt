package eu.darken.butler.common.files.metadata

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.pkgs.apk.ApkArchiveParser
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts metadata from APK (Android Package) files.
 *
 * Uses Android's PackageManager to parse APK files and extract:
 * - Package name
 * - Version code/name
 * - SDK requirements
 * - Application label
 * - Permissions
 *
 * Only supports local file paths; the parsing itself is [ApkArchiveParser]'s.
 */
@Singleton
class ApkMetadataExtractor @Inject constructor(
    private val apkArchiveParser: ApkArchiveParser,
    private val dispatcherProvider: DispatcherProvider,
) : MetadataExtractor<ApkMetadata> {

    private val tag = logTag("Metadata", "Extractor", "Apk")

    override fun canHandle(lookup: APathLookup<*>): Boolean {
        val name = lookup.lookedUp.name
        return name.endsWith(".apk", ignoreCase = true) || name.endsWith(".aab", ignoreCase = true)
    }

    override suspend fun extract(lookup: APathLookup<*>): Result<ApkMetadata> = runCatching {
        withContext(dispatcherProvider.IO) {
            val path = lookup.lookedUp as? LocalPath
                ?: throw UnsupportedOperationException("APK extraction only supports local paths, got: ${lookup.lookedUp::class.simpleName}")

            log(tag) { "Extracting APK metadata from: ${path.path}" }

            // No icon: the list/metadata consumers render none, and rasterizing one is not free.
            val info = apkArchiveParser.parseFile(path, includeIcon = false)
                ?: throw IllegalStateException("Failed to parse APK: ${path.path}")

            ApkMetadata(
                packageName = info.id.name,
                versionCode = info.versionCode,
                versionName = info.versionName ?: "Unknown",
                minSdk = info.minSdk ?: 1,
                targetSdk = info.targetSdk ?: 1,
                applicationLabel = info.label,
                permissions = info.requestedPermissions,
            )
        }
    }
}
