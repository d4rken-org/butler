package eu.darken.butler.common.coil.fetchers

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.toFile
import javax.inject.Inject


class ApkPreviewGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val pacMan: PackageManager
        get() = context.packageManager

    suspend fun generate(
        lookup: LocalPath,
    ): Drawable? {
        log(TAG) { "Generating preview for: ${lookup.path}" }
        val file = lookup.toFile()

        val iconDrawable = file
            .takeIf { it.canRead() }
            ?.let { pacMan.getPackageArchiveInfo(it.path, PackageManager.GET_META_DATA) }
            ?.let {
                (it.applicationInfo ?: ApplicationInfo()).apply {
                    sourceDir = file.path
                    publicSourceDir = file.path
                }
            }
            ?.let { pacMan.getApplicationIcon(it) }

        return iconDrawable
    }

    companion object {
        private val TAG = logTag("Coil", "Fetcher", "Path", "Apk", "Generator")
    }
}