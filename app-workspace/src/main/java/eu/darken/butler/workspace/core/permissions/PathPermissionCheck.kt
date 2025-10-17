package eu.darken.butler.workspace.core.permissions

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.extensions.isDescendantOfOrSelf
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.permissions.Permission
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.core.setup.SetupStateProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PathPermissionCheck @Inject constructor(
    @ApplicationContext private val context: Context,
    private val setupStateProvider: SetupStateProvider,
    private val accessChecker: LocalPathAccessChecker,
    private val storageEnvironment: StorageEnvironment,
) {

    private fun check(path: APath<*>): PermissionState {
        // App-specific directories don't need special permissions
        if (isOurDirectory(path)) {
            return PermissionState(
                requirements = emptyList(),
                hasSufficientPermissions = true,
                missingCritical = emptyList(),
            )
        }

        // Other paths that don't need special permissions
        if (!isPublicStorage(path)) {
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
        )

        return PermissionState(
            requirements = listOf(requirement),
            hasSufficientPermissions = isGranted,
            missingCritical = if (!isGranted) listOf(requiredPermission) else emptyList(),
        )
    }

    private fun isOurDirectory(path: APath<*>): Boolean = when (path) {
        is LocalPath -> {
            storageEnvironment.ourPrivateDirs.any { path.isDescendantOfOrSelf(it) } ||
                storageEnvironment.ourPublicDirs.any { path.isDescendantOfOrSelf(it) }
        }
        is SAFPath -> false
    }

    private fun isPublicStorage(path: APath<*>): Boolean = when (path) {
        is LocalPath -> storageEnvironment.publicStorages.any { path.isDescendantOfOrSelf(it) }
        is SAFPath -> false
    }

    private fun determineRequiredModules(path: APath<*>): Set<SetupModule.Type> {
        // Only LocalPath from here on
        val localPath = path as? LocalPath ?: return emptySet()

        // App-specific directories don't need modules
        if (isOurDirectory(localPath)) return emptySet()

        val needsEscalation = !accessChecker.shouldTryNormalAccess(
            path = localPath,
            forWriting = false // Conservative: assume reading
        )

        // Public storage paths
        if (isPublicStorage(localPath)) {
            val modules = mutableSetOf(SetupModule.Type.STORAGE)
            if (needsEscalation) {
                modules.add(SetupModule.Type.ROOT)
                modules.add(SetupModule.Type.SHIZUKU)
            }
            return modules
        }

        // Other paths (like /data, /system, etc.)
        return if (needsEscalation) {
            setOf(SetupModule.Type.ROOT, SetupModule.Type.SHIZUKU)
        } else {
            emptySet()
        }
    }

    fun monitor(path: APath<*>): Flow<PermissionState> {
        val requiredModuleTypes = determineRequiredModules(path)

        log(TAG, VERBOSE) { "Required modules for $path: $requiredModuleTypes" }

        // Fast path: No modules needed
        if (requiredModuleTypes.isEmpty()) {
            return flowOf(
                PermissionState(
                    requirements = emptyList(),
                    hasSufficientPermissions = true,
                    missingCritical = emptyList(),
                )
            ).onEach { permissionState ->
                log(TAG, INFO) { "Permission state for $path: $permissionState (no modules required)" }
            }
        }

        // Wait only for required modules
        return setupStateProvider.state
            .map { providerState ->
                // Only get modules we actually need
                val relevantModules = providerState.modules.values
                    .filterIsInstance<SetupModule.State.Current>()
                    .filter { module -> module.type in requiredModuleTypes }

                // Create a map of module states
                val moduleStates = relevantModules.associate { it.type to it.isComplete }

                // Check if we have all required modules (not all possible modules)
                val hasAllModules = requiredModuleTypes.all { type ->
                    moduleStates.containsKey(type)
                }

                Pair(moduleStates, hasAllModules)
            }
            .distinctUntilChanged()
            .filter { pair -> pair.second } // Only emit when we have required modules
            .onEach { pair ->
                log(TAG, VERBOSE) { "Relevant setup state for $path: ${pair.first}" }
            }
            .map { check(path) }
            .distinctUntilChanged()
            .onEach { permissionState ->
                log(TAG, INFO) { "Permission state for $path: $permissionState" }
            }
    }

    companion object {
        private val TAG = logTag("Permission", "PathChecker")
    }
}