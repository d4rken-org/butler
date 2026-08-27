package eu.darken.butler.permissions.core

import eu.darken.butler.common.ApiLevel
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.SAFPath
import eu.darken.butler.common.files.SmbPath
import eu.darken.butler.common.files.ArchivePath
import eu.darken.butler.common.files.extensions.filterDistinctRoots
import eu.darken.butler.common.files.extensions.isDescendantOfOrSelf
import eu.darken.butler.common.files.local.accessibility.LocalPathAccessChecker
import eu.darken.butler.common.files.saf.location.SAFLocationManager
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.storage.saf.AndroidDataAccessChecker
import eu.darken.butler.common.storage.saf.SAFPickerIntentBuilder
import eu.darken.butler.setup.core.SetupModule
import eu.darken.butler.setup.core.SetupStateProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    ): PathRequirements {
        val determined = determineModuleRequirements(path)

        // If alternative path or SAF picker available, return immediately
        if (determined.alternativePath != null || determined.safPickerGrant != null) {
            return determined
        }

        // No modules needed - all good
        if (determined.combos.isEmpty()) return PathRequirements()

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
        is ArchivePath -> false
        is SmbPath -> false
    }

    private suspend fun determineModuleRequirements(path: APath<*>): PathRequirements {
        // Archive entries inherit their container's access requirements (e.g. root-only storage).
        if (path is ArchivePath) {
            val containerRequirements = determineModuleRequirements(path.container)
            return when (val alternative = containerRequirements.alternativePath) {
                // Keep pointing INTO the archive when the container has a SAF alternative.
                null -> containerRequirements
                else -> containerRequirements.copy(alternativePath = ArchivePath(alternative, path.segments))
            }
        }

        // Only LocalPath from here on
        val localPath = path as? LocalPath ?: return PathRequirements()

        when {
            // Our folder is always accessible
            isOurDirectory(localPath) -> return PathRequirements()

            // Doesn't need anything?
            localPath.isDescendantOfOrSelf(storageEnvironment.systemDir) -> return PathRequirements()

            localPath.isDescendantOfOrSelf(storageEnvironment.dataDir) -> return PathRequirements(
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
                return PathRequirements(alternativePath = safPath)
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
                    PathRequirements(
                        combos = setOfNotNull(
                            if (isRootAvailable) setOf(SetupModule.Type.ROOT) else null,
                            if (isShizukuAvailable) setOf(SetupModule.Type.SHIZUKU) else null,
                        )
                    )
                }
                apiLevel.has(30) -> {
                    // Android 11-12: Check if SAF picker works
                    PathRequirements(
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
                    PathRequirements(combos = setOf(setOf(SetupModule.Type.STORAGE)))
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
            return PathRequirements(combos = combos)
        }

        return if (needsEscalation) {
            PathRequirements(
                combos = setOf(
                    setOf(SetupModule.Type.STORAGE, SetupModule.Type.SHIZUKU),
                    setOf(SetupModule.Type.STORAGE, SetupModule.Type.ROOT),
                    setOf(SetupModule.Type.ROOT),
                )
            )
        } else {
            PathRequirements()
        }
    }

    /**
     * Aggregated setup requirements for a set of observed paths (e.g. entries a directory walk
     * could not read). Paths are reduced to their distinct roots first — descendants share their
     * ancestor's requirements — and capped at [MAX_BATCH_MONITOR_PATHS] monitored roots.
     *
     * Semantics differ from the single-path [monitor] in two deliberate ways:
     * - Per-path alternatives (existing SAF grant, SAF picker) are not aggregatable: a path with
     *   an existing SAF grant contributes nothing (it is accessible), a picker-eligible path
     *   still contributes its setup combos with live tracking (the picker itself is the caller's
     *   concern, and combos must keep updating as setup completes).
     * - Combos are combined as a conjunction across paths, not a union: a resulting combo is one
     *   that unlocks EVERY path. Otherwise "Android/data (root OR Shizuku)" plus "/data (root
     *   only)" would wrongly advertise Shizuku alone as sufficient.
     */
    fun monitor(paths: Collection<APath<*>>): Flow<PathRequirements> {
        val roots = paths.filterDistinctRoots()
        if (roots.isEmpty()) return flowOf(PathRequirements())
        val monitored = roots.take(MAX_BATCH_MONITOR_PATHS)
        if (monitored.size < roots.size) {
            log(TAG, WARN) { "Batch monitor capped: ${roots.size} distinct roots, monitoring ${monitored.size}" }
        }
        return combine(monitored.map { monitorSetupOnly(it) }) { requirements ->
            val active = requirements.filter { it.combos.isNotEmpty() }
            PathRequirements(
                combos = active.map { it.combos }.combineAsConjunction(),
                complete = active.flatMap { it.complete }.toSet(),
                shizukuInstalled = requirements.any { it.shizukuInstalled },
                rootInstalled = requirements.any { it.rootInstalled },
            )
        }.distinctUntilChanged()
    }

    /**
     * Single-path monitoring restricted to setup-based requirements: unlike [monitor], a viable
     * SAF picker does not short-circuit into a single completed emission — the setup combos keep
     * live-tracking so batch aggregation observes later grants (Android 11-12 would otherwise
     * freeze on the picker emission and never see root/Shizuku completion).
     */
    private fun monitorSetupOnly(path: APath<*>): Flow<PathRequirements> = flow {
        val determined = determineModuleRequirements(path)
        val combos = when {
            // Accessible right now through an existing SAF grant — nothing to set up
            determined.alternativePath != null -> emptySet()
            else -> determined.combos
        }
        if (combos.isEmpty()) {
            emit(PathRequirements())
            return@flow
        }
        trackSetupState(
            relevantTypes = combos.flatten().toSet(),
            build = { moduleStates, installState ->
                PathRequirements(
                    combos = combos,
                    complete = moduleStates.filterValues { it }.keys,
                    shizukuInstalled = installState.shizuku,
                    rootInstalled = installState.root,
                )
            },
        ).collect { emit(it) }
    }

    // Conjunction with antichain pruning: every step keeps only minimal combos, so the result
    // stays tiny (bounded by antichains over the small SetupModule.Type domain).
    private fun List<Set<Set<SetupModule.Type>>>.combineAsConjunction(): Set<Set<SetupModule.Type>> {
        if (isEmpty()) return emptySet()
        return fold(setOf(emptySet<SetupModule.Type>())) { acc, pathCombos ->
            acc.flatMap { globalCombo -> pathCombos.map { combo -> globalCombo + combo } }
                .toSet()
                .let { combos ->
                    combos.filter { combo ->
                        combos.none { other -> other != combo && combo.containsAll(other) }
                    }.toSet()
                }
        }
    }

    fun monitor(path: APath<*>): Flow<PathRequirements> {
        return flow {
            val requirements = check(path, emptyMap())

            // If alternative path or SAF picker available, emit immediately and don't monitor setup
            if (requirements.alternativePath != null || requirements.safPickerGrant != null) {
                emit(requirements)
                return@flow
            }

            // For setup-based requirements, monitor setup state
            trackSetupState(
                relevantTypes = requirements.relevantTypes,
                build = { moduleStates, installState ->
                    check(path, moduleStates).copy(
                        shizukuInstalled = installState.shizuku,
                        rootInstalled = installState.root,
                    )
                },
            )
                .onEach { setupState ->
                    log(TAG) { "Setup state for $path: $setupState" }
                }
                .collect { emit(it) }
        }
    }

    private fun trackSetupState(
        relevantTypes: Set<SetupModule.Type>,
        build: suspend (Map<SetupModule.Type, Boolean>, InstallState) -> PathRequirements,
    ): Flow<PathRequirements> = setupStateProvider.state
        .map { providerState ->
            val relevantModules = providerState.modules.values
                .filterIsInstance<SetupModule.State.Current>()
                .filter { module -> module.type in relevantTypes }

            val moduleStates = relevantModules.associate { it.type to it.isComplete }

            val hasAllModules = relevantTypes.all { type ->
                moduleStates.containsKey(type)
            }

            // Derived per-emission so install state tracks modules resolving from Loading.
            val installState = InstallState(
                shizuku = (providerState.modules[SetupModule.Type.SHIZUKU] as? SetupModule.State.Current)?.isInstalled == true,
                root = (providerState.modules[SetupModule.Type.ROOT] as? SetupModule.State.Current)?.isInstalled == true,
            )

            Triple(moduleStates, hasAllModules, installState)
        }
        .distinctUntilChanged()
        .filter { it.second }
        .onEach { (moduleStates, _, _) ->
            log(TAG, VERBOSE) { "Relevant setup state: $moduleStates" }
        }
        .map { (moduleStates, _, installState) -> build(moduleStates, installState) }
        .distinctUntilChanged()

    private data class InstallState(val shizuku: Boolean, val root: Boolean)

    companion object {
        private val TAG = logTag("Permission", "PathChecker")

        /**
         * Upper bound on concurrently monitored roots in the batch overload. Distinct-root
         * reduction collapses the common case (many entries under one restricted dir) to a
         * handful; this cap only sheds load for pathological inputs. Truncation can at worst
         * omit a suggestion, never offer an invalid one.
         */
        private const val MAX_BATCH_MONITOR_PATHS = 24
    }
}
