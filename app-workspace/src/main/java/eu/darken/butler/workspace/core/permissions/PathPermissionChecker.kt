package eu.darken.butler.workspace.core.permissions

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.core.setup.SetupStateProvider
import eu.darken.butler.workspace.core.setup.module
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PathPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupStateProvider: SetupStateProvider? = null,
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

        val requirement = SetupRequirement(
            permission = requiredPermission,
            isRequired = true,
            description = eu.darken.butler.common.R.string.common_permission_storage_manage_description.toCaString(),
        )

        return PermissionState(
            requirements = listOf(requirement),
            hasSufficientPermissions = isGranted,
            missingCritical = if (!isGranted) listOf(requiredPermission) else emptyList(),
        )
    }

    fun observePermissionState(path: APath): Flow<PermissionState> {
        return setupStateProvider?.module(SetupModule.Type.STORAGE)
            ?.map {
                // When storage setup state changes, re-evaluate permissions for this path
                check(path)
            }
            ?.distinctUntilChanged() // Only emit when permission state actually changes
            ?: flowOf(check(path)) // Fallback to static check if no provider
    }
}