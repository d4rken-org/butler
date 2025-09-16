package eu.darken.butler.workspace.core.permissions

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.core.setup.SetupStateProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PathPermissionCheck @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupStateProvider: SetupStateProvider,
) {

    private fun check(path: APath): PermissionState {
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

    fun monitor(path: APath): Flow<PermissionState> = setupStateProvider.state
        .map { providerState ->
          providerState.modules.values.filterIsInstance<SetupModule.State.Current>().map { it.type to it.isComplete }
        }
        .distinctUntilChanged()
        .onEach { log(TAG, VERBOSE) { "Setup state changed: $it" } }
        .map { check(path) }
        .distinctUntilChanged()
        .onEach { log(TAG, INFO) { "Permission state for $path: $it" } }

    companion object {
        private val TAG = logTag("Permission", "PathChecker")
    }
}