package eu.darken.butler.common.files.metadata

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.LocalPath
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
 * Only supports local file paths.
 */
@Singleton
class ApkMetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider
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

            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageArchiveInfo(
                path.path,
                PackageManager.GET_PERMISSIONS
            ) ?: throw IllegalStateException("Failed to parse APK: ${path.path}")

            // Need to set applicationInfo.sourceDir for loadLabel() to work
            packageInfo.applicationInfo?.sourceDir = path.path
            packageInfo.applicationInfo?.publicSourceDir = path.path

            ApkMetadata(
                packageName = packageInfo.packageName,
                versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                versionName = packageInfo.versionName ?: "Unknown",
                minSdk = packageInfo.applicationInfo?.minSdkVersion ?: 1,
                targetSdk = packageInfo.applicationInfo?.targetSdkVersion ?: 1,
                applicationLabel = try {
                    packageInfo.applicationInfo?.loadLabel(packageManager)?.toString()
                } catch (e: Exception) {
                    log(tag, WARN) { "Failed to load application label: ${e.asLog()}" }
                    null
                },
                permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()
            )
        }
    }
}
