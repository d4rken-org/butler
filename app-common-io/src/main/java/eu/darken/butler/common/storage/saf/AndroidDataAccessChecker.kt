package eu.darken.butler.common.storage.saf

import androidx.core.content.pm.PackageInfoCompat
import dagger.Reusable
import eu.darken.butler.common.ApiLevel
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.pkgs.pkgops.PkgOps
import eu.darken.butler.common.pkgs.toPkgId
import eu.darken.butler.common.user.UserHandle2
import javax.inject.Inject

/**
 * Checks if Storage Access Framework can be used to grant access to Android/data and Android/obb directories.
 *
 * This uses a trick in the SAF picker on Android 11-12:
 * When the picker is pre-navigated to `Android/data`, it will show the folder+content and allow selection.
 * Selecting `Android/data` directly (not `Android/`) grants proper access.
 *
 * On Android 13+ this trick no longer works.
 * Some devices with newer DocumentsUI versions also restrict this on Android 11-12.
 */
@Reusable
class AndroidDataAccessChecker @Inject constructor(
    private val pkgOps: PkgOps,
    private val apiLevel: ApiLevel,
) {

    /**
     * @return true if SAF picker can grant access to Android/data and Android/obb on this device
     */
    suspend fun canUseSAFForAndroidData(): Boolean {
        // Only works on Android 11-12 (API 30-32)
        if (!apiLevel.has(30)) return false
        if (apiLevel.has(33)) return false

        // Check if DocumentsUI version is restricted
        val isRestricted = pkgOps.queryPkg(
            pkgName = "com.google.android.documentsui".toPkgId(),
            flags = 0,
            userHandle = UserHandle2()
        )?.let { pkg ->
            log(TAG, VERBOSE) { "DocumentsUI package: $pkg" }
            log(TAG, VERBOSE) { "DocumentsUI targetSdkVersion=${pkg.applicationInfo?.targetSdkVersion}" }
            log(TAG, VERBOSE) { "DocumentsUI versionName=${pkg.versionName}" }

            val versionCode = PackageInfoCompat.getLongVersionCode(pkg)
            log(TAG, VERBOSE) { "DocumentsUI versionCode=$versionCode" }

            // Commit 901f1d6044aade190bb943ccc18d26244132648e with changes first seen in tag 'aml_doc_331120000'
            // https://cs.android.com/android/platform/superproject/main/+/main:packages/apps/DocumentsUI/src/com/android/documentsui/picker/ActionHandler.java;l=84;bpv=1;bpt=0;drc=901f1d6044aade190bb943ccc18d26244132648e
            val isTooNew = versionCode >= 331120000L
            val hasKnownMarker = (pkg.applicationInfo?.targetSdkVersion ?: 0) >= 34

            hasKnownMarker || isTooNew
        } ?: true  // If we can't query the package, assume it's restricted

        log(TAG) { "SAF Android/data access available: ${!isRestricted}" }
        return !isRestricted
    }

    companion object {
        private val TAG = logTag("SAF", "AndroidDataChecker")
    }
}
