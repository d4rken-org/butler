package eu.darken.butler.workspace.core.permissions

import eu.darken.butler.common.ApiLevel
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.extensions.isDescendantOfOrSelf
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.storage.saf.AndroidDataAccessChecker
import eu.darken.butler.common.storage.saf.SAFPickerIntentBuilder
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.workspace.core.setup.SetupStateProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PathPermissionCheck @Inject constructor(
    private val setupStateProvider: SetupStateProvider,
    private val accessChecker: LocalPathAccessChecker,
    private val storageEnvironment: StorageEnvironment,
    private val safLocationManager: SAFLocationManager,
    private val androidDataAccessChecker: AndroidDataAccessChecker,
    private val safPickerIntentBuilder: SAFPickerIntentBuilder,
    private val apiLevel: ApiLevel,
) {

    private suspend fun check(
        path: APath<*>,
        moduleStates: Map<SetupModule.Type, Boolean>
    ): WorkspaceRequirements {
        val determined = determineModuleRequirements(path)

        // If alternative path or SAF picker available, return immediately
        if (determined.alternativePath != null || determined.safPickerGrant != null) {
            return determined
        }

        // No modules needed - all good
        if (determined.combos.isEmpty()) return WorkspaceRequirements()

        return determined.copy(
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

    private suspend fun determineModuleRequirements(path: APath<*>): WorkspaceRequirements {
        val localPath = path as? LocalPath ?: return WorkspaceRequirements()

        when {
            // Our folder is always accessible
            isOurDirectory(localPath) -> return WorkspaceRequirements()

            // Doesn't need anything?
            localPath.isDescendantOfOrSelf(storageEnvironment.systemDir) -> return WorkspaceRequirements()

            localPath.isDescendantOfOrSelf(storageEnvironment.dataDir) -> return WorkspaceRequirements(
                combos = setOf(setOf(SetupModule.Type.ROOT))
            )
        }

        // Special case: Android/data and Android/obb
        val isRestrictedPath = storageEnvironment.publicDataDirs.any { path.isDescendantOfOrSelf(it) } ||
            storageEnvironment.publicObbDirs.any { path.isDescendantOfOrSelf(it) }

        if (isRestrictedPath) {
            // PHASE 1: Check if SAF path already available (permission exists)
            val safPath = safLocationManager.toSAFPath(localPath)
            if (safPath != null) {
                // toSAFPath returns non-null ONLY when permission exists, so we can use it directly
                log(TAG) { "Alternative SAF path with permission for $localPath: $safPath" }
                return WorkspaceRequirements(alternativePath = safPath)
            } else {
                log(TAG) { "No SAF permission available for $localPath" }
            }

            val setupModules = setupStateProvider.state.first()
            val isRootAvailable = setupModules.modules[SetupModule.Type.ROOT]
                ?.let { it as? SetupModule.State.Current }?.isAvailable == true
            log(TAG) { "ROOT maybe available? $isRootAvailable" }
            val isShizukuAvailable = setupModules.modules[SetupModule.Type.SHIZUKU]
                ?.let { it as? SetupModule.State.Current }?.isAvailable == true
            log(TAG) { "SHIZUKU maybe available? $isShizukuAvailable" }

            // PHASE 2: Determine access method
            return when {
                apiLevel.has(33) -> {
                    // Android 13+: SAF trick broken, Root/Shizuku only
                    log(TAG) { "Android 13+ detected, SAF not available for $localPath" }
                    WorkspaceRequirements(
                        combos = setOfNotNull(
                            if (isRootAvailable) setOf(SetupModule.Type.ROOT) else null,
                            if (isShizukuAvailable) setOf(SetupModule.Type.SHIZUKU) else null,
                        )
                    )
                }
                apiLevel.has(30) -> {
                    // Android 11-12: Check if SAF picker works
                    WorkspaceRequirements(
                        safPickerGrant = safPickerIntentBuilder.buildPickerIntent(localPath)
                            ?.takeIf {
                                androidDataAccessChecker.canUseSAFForAndroidData().also {
                                    if (!it) log(TAG) { "DocumentsUI restricted, SAF not available for $localPath" }
                                }
                            }
                            ?.let {
                                log(TAG) { "SAF picker available for $localPath" }
                                SAFPickerGrant(it, localPath)
                            },
                        combos = setOfNotNull(
                            if (isRootAvailable) setOf(SetupModule.Type.ROOT) else null,
                            if (isShizukuAvailable) setOf(SetupModule.Type.SHIZUKU) else null,
                        )
                    )
                }
                else -> {
                    // Android <11: Just storage permission
                    WorkspaceRequirements(combos = setOf(setOf(SetupModule.Type.STORAGE)))
                }
            }
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
            return WorkspaceRequirements(combos = combos)
        }

        return if (needsEscalation) {
            WorkspaceRequirements(
                combos = setOf(
                    setOf(SetupModule.Type.STORAGE, SetupModule.Type.SHIZUKU),
                    setOf(SetupModule.Type.STORAGE, SetupModule.Type.ROOT),
                    setOf(SetupModule.Type.ROOT),
                )
            )
        } else {
            WorkspaceRequirements()
        }
    }

    fun monitor(path: APath<*>): Flow<WorkspaceRequirements> {
        return flow {
            // Get initial setup state to check app installation
            // TODO: Extract isInstalled from module state when interface is updated
            // For now, always false since isInstalled is not in SetupModule.State.Current interface
            val shizukuInstalled = false
            val rootInstalled = false

            val requirements = check(path, emptyMap()).copy(
                shizukuInstalled = shizukuInstalled,
                rootInstalled = rootInstalled
            )

            // If alternative path or SAF picker available, emit immediately and don't monitor setup
            if (requirements.alternativePath != null || requirements.safPickerGrant != null) {
                emit(requirements)
                return@flow
            }

            // For setup-based requirements, monitor setup state
            setupStateProvider.state
                .map { providerState ->
                    val relevantModules = providerState.modules.values
                        .filterIsInstance<SetupModule.State.Current>()
                        .filter { module -> module.type in requirements.relevantTypes }

                    val moduleStates = relevantModules.associate { it.type to it.isComplete }

                    val hasAllModules = requirements.relevantTypes.all { type ->
                        moduleStates.containsKey(type)
                    }

                    Pair(moduleStates, hasAllModules)
                }
                .distinctUntilChanged()
                .filter { pair -> pair.second }
                .onEach { pair ->
                    log(TAG, VERBOSE) { "Relevant setup state for $path: ${pair.first}" }
                }
                .map { (moduleStates, _) ->
                    check(path, moduleStates).copy(
                        shizukuInstalled = shizukuInstalled,
                        rootInstalled = rootInstalled
                    )
                }
                .distinctUntilChanged()
                .onEach { setupState ->
                    log(TAG) { "Setup state for $path: $setupState" }
                }
                .collect { emit(it) }
        }
    }

    companion object {
        private val TAG = logTag("Permission", "PathChecker")
    }
}