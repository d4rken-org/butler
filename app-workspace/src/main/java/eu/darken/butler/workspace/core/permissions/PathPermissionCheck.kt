package eu.darken.butler.workspace.core.permissions

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.extensions.isDescendantOfOrSelf
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.core.setup.SetupStateProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PathPermissionCheck @Inject constructor(
    private val setupStateProvider: SetupStateProvider,
    private val accessChecker: LocalPathAccessChecker,
    private val storageEnvironment: StorageEnvironment,
) {

    private fun check(
        path: APath<*>,
        moduleStates: Map<SetupModule.Type, Boolean>
    ): WorkspaceRequirements {
        val satisfyingCombos = determineModuleRequirements(path)

        // No modules needed - all good
        if (satisfyingCombos.isEmpty()) return WorkspaceRequirements()

        return WorkspaceRequirements(
            combos = satisfyingCombos,
            complete = moduleStates.filterValues { it }.keys,
        )
    }

    private fun isOurDirectory(path: APath<*>): Boolean = when (path) {
        is LocalPath -> {
            storageEnvironment.ourPrivateDirs.any { path.isDescendantOfOrSelf(it) } ||
                storageEnvironment.ourPublicDirs.any { path.isDescendantOfOrSelf(it) }
        }
        is SAFPath -> false
    }

    private fun determineModuleRequirements(path: APath<*>): Set<Set<SetupModule.Type>> {
        // Only LocalPath from here on
        val localPath = path as? LocalPath ?: return emptySet()

        // App-specific directories don't need modules
        if (isOurDirectory(localPath)) return emptySet()

        // Special case: Android/data and Android/obb
        if (storageEnvironment.publicDataDirs.any { path.isDescendantOfOrSelf(it) }) {
            val combos = mutableSetOf<Set<SetupModule.Type>>()
            when {
                hasApiLevel(33) -> {
                    combos.add(setOf(SetupModule.Type.STORAGE, SetupModule.Type.ROOT))
                    combos.add(setOf(SetupModule.Type.STORAGE, SetupModule.Type.SHIZUKU))
                }
                hasApiLevel(30) -> {
                    combos.add(setOf(SetupModule.Type.STORAGE, SetupModule.Type.SAF))
                }
                else -> {
                    combos.add(setOf(SetupModule.Type.STORAGE))
                }
            }
            return combos
        }

        // Special case: Android/obb
        if (storageEnvironment.publicObbDirs.any { path.isDescendantOfOrSelf(it) }) {
            val combos = mutableSetOf<Set<SetupModule.Type>>()
            when {
                hasApiLevel(33) -> {
                    combos.add(setOf(SetupModule.Type.STORAGE, SetupModule.Type.ROOT))
                    combos.add(setOf(SetupModule.Type.STORAGE, SetupModule.Type.SHIZUKU))
                }
                hasApiLevel(30) -> {
                    combos.add(setOf(SetupModule.Type.STORAGE, SetupModule.Type.SAF))
                }
                else -> {
                    combos.add(setOf(SetupModule.Type.STORAGE))
                }
            }
            return combos
        }

        val needsEscalation = !accessChecker.shouldTryNormalAccess(
            path = localPath,
            forWriting = false // Conservative: assume reading
        )

        // Public storage, /storage/emulated/0 or an external storage
        if (storageEnvironment.publicStorages.any { path.isDescendantOfOrSelf(it) }) {
            val combos = mutableSetOf<Set<SetupModule.Type>>()
            if (needsEscalation) {
                combos.add(setOf(SetupModule.Type.STORAGE, SetupModule.Type.SHIZUKU))
                combos.add(setOf(SetupModule.Type.STORAGE, SetupModule.Type.ROOT))
            } else {
                combos.add(setOf(SetupModule.Type.STORAGE))
            }
            return combos
        }

        // Other paths (like /data, /system, etc.)
        return if (needsEscalation) {
            setOf(
                setOf(SetupModule.Type.STORAGE, SetupModule.Type.SHIZUKU),
                setOf(SetupModule.Type.STORAGE, SetupModule.Type.ROOT),
            )
        } else {
            emptySet()
        }
    }

    fun monitor(path: APath<*>): Flow<WorkspaceRequirements> {
        val requirements = check(path, emptyMap())

        // Wait only for required modules
        return setupStateProvider.state
            .map { providerState ->
                // Only get modules we actually need
                val relevantModules = providerState.modules.values
                    .filterIsInstance<SetupModule.State.Current>()
                    .filter { module -> module.type in requirements.relevantTypes }

                // Create a map of module states
                val moduleStates = relevantModules.associate { it.type to it.isComplete }

                // Check if we have all required modules (not all possible modules)
                val hasAllModules = requirements.relevantTypes.all { type ->
                    moduleStates.containsKey(type)
                }

                Pair(moduleStates, hasAllModules)
            }
            .distinctUntilChanged()
            .filter { pair -> pair.second } // Only emit when we have required modules
            .onEach { pair ->
                log(TAG, VERBOSE) { "Relevant setup state for $path: ${pair.first}" }
            }
            .map { (moduleStates, _) -> check(path, moduleStates) }
            .distinctUntilChanged()
            .onEach { setupState ->
                log(TAG, INFO) { "Setup state for $path: $setupState" }
            }
    }

    companion object {
        private val TAG = logTag("Permission", "PathChecker")
    }
}