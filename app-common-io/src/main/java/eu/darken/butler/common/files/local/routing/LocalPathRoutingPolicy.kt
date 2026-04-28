package eu.darken.butler.common.files.local.routing

import android.annotation.SuppressLint
import eu.darken.butler.common.files.FileSystemOps
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.isAncestorOf
import eu.darken.butler.common.files.extensions.isDescendantOfOrSelf
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.hasApiLevel
import eu.darken.butler.common.storage.StorageEnvironment
import eu.darken.butler.common.storage.StorageManager2
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("SdCardPath")
@Singleton
class LocalPathRoutingPolicy @Inject constructor(
    private val storageEnvironment: StorageEnvironment,
    private val storageManager: StorageManager2,
) {

    suspend fun classify(path: LocalPath, intent: AccessIntent, caps: CapabilitySnapshot): RouteDecision {
        if (matchesAnyAlias(path, ownDirs())) return RouteDecision.Allowed(AccessMode.DIRECT)

        return when (intent) {
            AccessIntent.Read -> classifyReadOrWrite(path, forWriting = false, caps)
            AccessIntent.Write -> classifyReadOrWrite(path, forWriting = true, caps)
            AccessIntent.Delete -> classifyDelete(path, caps)
        }
    }

    fun aliasesOf(path: LocalPath): Set<LocalPath> = buildSet {
        add(path)

        val primaryAliases = listOf(
            LocalPath.build("/sdcard"),
            LocalPath.build("/storage/self/primary"),
            LocalPath.build("/storage/emulated/0"),
        )

        primaryAliases.forEach { from ->
            if (path.matches(from) || path.isDescendantOfOrSelf(from)) {
                val suffix = suffixAfter(path, from)
                primaryAliases.forEach { to ->
                    add(withSuffix(to, suffix))
                }
            }
        }
    }

    fun proactiveChildren(parent: LocalPath): Set<LocalPath> {
        if (!hasApiLevel(30)) return emptySet()
        return knownRouteBoundariesUnder(parent)
    }

    fun knownRouteBoundariesUnder(parent: LocalPath): Set<LocalPath> {
        if (!hasApiLevel(30)) return emptySet()

        val parentAliases = aliasesOf(parent)
        return restrictedPublicRoots()
            .flatMap { aliasesOf(it) }
            .mapNotNull { boundary ->
                parentAliases.firstNotNullOfOrNull { parentAlias ->
                    if (boundary.matches(parentAlias)) {
                        null
                    } else if (boundary.isDescendantOfOrSelf(parentAlias)) {
                        withSuffix(parent, suffixAfter(boundary, parentAlias))
                    } else {
                        null
                    }
                }
            }
            .toSet()
    }

    fun isPublicOrRemovableDestination(path: LocalPath): Boolean {
        if (matchesAnyAlias(path, publicStorageRoots())) return true

        return storageManager.storageVolumes.any { volume ->
            val root = volume.directory?.let { LocalPath.build(it) } ?: volume.path?.let { LocalPath.build(it) }
            root != null && volume.isRemovable && path.isDescendantOfOrSelf(root)
        }
    }

    suspend fun nearestExistingDestinationOwner(
        path: LocalPath,
        ops: FileSystemOps<LocalPath, LocalPathLookup>,
    ): Ownership? {
        var current: LocalPath? = path
        while (current != null) {
            val lookup = try {
                ops.lookup(current, LookupOptions.MAX.copy(fallbackToUnknown = true))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (lookup != null && lookup.fileType != FileType.UNKNOWN) return lookup.ownership
            current = current.parent
        }
        return null
    }

    suspend fun batchEligibility(request: BatchEligibilityRequest): BatchEligibility {
        if (request.sourceRoute.mode !in IPC_BACKED_MODES) {
            return BatchEligibility.Ineligible("source route is not IPC backed")
        }
        if (request.sourceRoute.batch == null) {
            return BatchEligibility.Ineligible("source route has no batch API")
        }
        if (knownRouteBoundariesUnder(request.sourceRoot).isNotEmpty()) {
            return BatchEligibility.Ineligible("known route boundary below source")
        }
        if (publicStorageRoots().any { request.sourceRoot.matches(it) }) {
            return BatchEligibility.Ineligible("source is a broad public root")
        }

        if (request.operation == BatchOperation.DELETE) {
            return BatchEligibility.Eligible(
                mode = request.sourceRoute.mode,
                destinationModeOverride = null,
                ownershipFixup = OwnershipFixup.None,
            )
        }

        val destinationRoot = request.destinationRoot
            ?: return BatchEligibility.Ineligible("missing destination")
        val destinationRoute = request.destinationRoute
            ?: return BatchEligibility.Ineligible("missing destination route")

        val destinationExists = try {
            destinationRoute.ops.lookup(destinationRoot, LookupOptions.BASE.copy(fallbackToUnknown = true)).fileType !=
                FileType.UNKNOWN
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }
        if (destinationExists) {
            return BatchEligibility.Ineligible("destination exact semantics require a non-existing destination")
        }

        if (destinationRoute.mode != request.sourceRoute.mode) {
            if (!isPublicOrRemovableDestination(destinationRoot)) {
                return BatchEligibility.Ineligible("destination route differs")
            }

            val owner = nearestExistingDestinationOwner(destinationRoot, destinationRoute.ops)
                ?: return BatchEligibility.Ineligible("destination owner unavailable")

            return BatchEligibility.Eligible(
                mode = request.sourceRoute.mode,
                destinationModeOverride = request.sourceRoute.mode,
                ownershipFixup = OwnershipFixup.InheritNearestExistingDestinationOwner(owner),
            )
        }

        if (destinationRoute.batch == null) {
            return BatchEligibility.Ineligible("destination route has no batch API")
        }

        return BatchEligibility.Eligible(
            mode = request.sourceRoute.mode,
            destinationModeOverride = null,
            ownershipFixup = OwnershipFixup.None,
        )
    }

    private suspend fun classifyDelete(path: LocalPath, caps: CapabilitySnapshot): RouteDecision {
        val targetRead = classifyReadOrWrite(path, forWriting = false, caps)
        val parentWrite = path.parent
            ?.let { classifyReadOrWrite(it, forWriting = true, caps) }
            ?: RouteDecision.Denied

        return if (targetRead is RouteDecision.Allowed &&
            parentWrite is RouteDecision.Allowed &&
            targetRead.mode == AccessMode.DIRECT &&
            parentWrite.mode == AccessMode.DIRECT
        ) {
            RouteDecision.Allowed(AccessMode.DIRECT)
        } else {
            elevatedOrDenied(caps)
        }
    }

    private suspend fun classifyReadOrWrite(
        path: LocalPath,
        forWriting: Boolean,
        caps: CapabilitySnapshot,
    ): RouteDecision {
        if (path.path == "/") return elevatedOrDenied(caps)

        if (matchesAnyAlias(path, ownDirs())) return RouteDecision.Allowed(AccessMode.DIRECT)

        if (hasApiLevel(30) && matchesAnyAlias(path, restrictedPublicRoots())) return elevatedOrDenied(caps)

        if (matchesAny(path, systemReadOnlyRoots())) {
            return if (forWriting) elevatedOrDenied(caps) else RouteDecision.Allowed(AccessMode.DIRECT)
        }

        if (matchesAnyAlias(path, publicStorageRoots())) {
            return if (isOnRemovableStorage(path)) {
                RouteDecision.Allowed(AccessMode.ISOLATED)
            } else {
                RouteDecision.Allowed(AccessMode.DIRECT)
            }
        }

        if (matchesAny(path, systemBlockedRoots())) return elevatedOrDenied(caps)

        return RouteDecision.Allowed(AccessMode.DIRECT)
    }

    private suspend fun elevatedOrDenied(caps: CapabilitySnapshot): RouteDecision = when {
        caps.hasRoot() -> RouteDecision.Allowed(AccessMode.ROOT)
        caps.hasAdb() -> RouteDecision.Allowed(AccessMode.ADB)
        else -> RouteDecision.Denied
    }

    private fun matchesAnyAlias(path: LocalPath, roots: Collection<LocalPath>): Boolean =
        aliasesOf(path).any { alias -> matchesAny(alias, roots) }

    private fun matchesAny(path: LocalPath, roots: Collection<LocalPath>): Boolean =
        roots.any { root -> path.matches(root) || path.isDescendantOfOrSelf(root) }

    private fun isOnRemovableStorage(path: LocalPath): Boolean =
        storageManager.storageVolumes.any { volume ->
            val root = volume.directory?.let { LocalPath.build(it) } ?: volume.path?.let { LocalPath.build(it) }
            root != null && volume.isRemovable && path.isDescendantOfOrSelf(root)
        }

    private fun publicStorageRoots(): Set<LocalPath> = buildSet {
        add(LocalPath.build("/sdcard"))
        add(LocalPath.build("/storage/self/primary"))
        add(LocalPath.build("/storage/emulated/0"))
        addAll(storageEnvironment.publicStorages)
    }

    private fun restrictedPublicRoots(): Set<LocalPath> = buildSet {
        addAll(storageEnvironment.publicDataDirs)
        addAll(storageEnvironment.publicObbDirs)
    }

    private fun ownDirs(): Set<LocalPath> = buildSet {
        addAll(storageEnvironment.ourPublicDirs)
        addAll(storageEnvironment.ourPrivateDirs)
        addAll(
            storageEnvironment.ourPrivateDirs
                .map { it.path.replace("/data/user/0", "/data/data") }
                .map { LocalPath.build(it) }
        )
    }.flatMap { aliasesOf(it) }.toSet()

    private fun systemBlockedRoots(): Set<LocalPath> = setOf(
        LocalPath.build("/dev"),
        LocalPath.build("/cache"),
        LocalPath.build("/storage"),
        LocalPath.build("/data"),
        LocalPath.build("/sys"),
    )

    private fun systemReadOnlyRoots(): Set<LocalPath> = setOf(
        LocalPath.build("/system"),
        LocalPath.build("/proc"),
        LocalPath.build("/vendor"),
        LocalPath.build("/product"),
        LocalPath.build("/system_ext"),
        LocalPath.build("/apex"),
    )

    private fun suffixAfter(path: LocalPath, root: LocalPath): List<String> {
        if (path.matches(root)) return emptyList()
        require(root.isAncestorOf(path)) { "$root is not an ancestor of $path" }
        return path.path
            .removePrefix(root.path)
            .trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
    }

    private fun withSuffix(root: LocalPath, suffix: List<String>): LocalPath =
        if (suffix.isEmpty()) root else root.child(*suffix.toTypedArray())

    companion object {
        private val IPC_BACKED_MODES = setOf(AccessMode.ISOLATED, AccessMode.ROOT, AccessMode.ADB)
    }
}
