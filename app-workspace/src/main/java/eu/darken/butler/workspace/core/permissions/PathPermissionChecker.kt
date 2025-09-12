package eu.darken.butler.workspace.core.permissions

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.permissions.Permission
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PathPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun check(path: APath): PermissionState {
        val pathString = when (path) {
            is LocalPath -> path.path
            else -> path.path
        }

        // Check if this is internal storage that requires permissions
        val internalStoragePath = Environment.getExternalStorageDirectory().absolutePath
        val requiresStoragePermission = pathString.startsWith(internalStoragePath) || 
                                       pathString.startsWith("/storage/emulated/") ||
                                       pathString.startsWith("/sdcard")

        if (!requiresStoragePermission) {
            // App-specific directories or other paths that don't need special permissions
            return PermissionState(
                requirements = emptyList(),
                hasSufficientPermissions = true,
                missingCritical = emptyList(),
            )
        }

        // Determine which storage permission is needed based on API level
        val requiredPermission = when {
            hasApiLevel(30) -> Permission.MANAGE_EXTERNAL_STORAGE
            else -> Permission.WRITE_EXTERNAL_STORAGE
        }

        val isGranted = requiredPermission.isGranted(context)

        val requirement = PermissionRequirement(
            permission = requiredPermission,
            isRequired = true,
            reason = eu.darken.butler.common.R.string.common_permission_storage_manage_description.toCaString(),
            alternativeAccess = null,
        )

        return PermissionState(
            requirements = listOf(requirement),
            hasSufficientPermissions = isGranted,
            missingCritical = if (!isGranted) listOf(requiredPermission) else emptyList(),
        )
    }
}