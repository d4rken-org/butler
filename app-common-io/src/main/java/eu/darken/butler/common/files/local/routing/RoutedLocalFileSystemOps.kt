package eu.darken.butler.common.files.local.routing

import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.MoveOutcome
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileSystem
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.files.metadata.Ownership
import eu.darken.butler.common.files.metadata.Permissions
import kotlinx.coroutines.CancellationException
import okio.FileHandle
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Instant

internal class RoutedLocalFileSystemOps(
    private val router: StaticLocalRouteRouter,
    private val defaultLookupIntent: AccessIntent,
) : IntentAwareFileSystemOps<LocalPath, LocalPathLookup> {

    override suspend fun lookup(path: LocalPath, options: LookupOptions): LocalPathLookup =
        lookup(path, defaultLookupIntent, options)

    override suspend fun lookup(
        path: LocalPath,
        intent: AccessIntent,
        options: LookupOptions,
    ): LocalPathLookup {
        val route = router.routeFor(path, intent)
        return route.ops.lookup(path, options)
    }

    override suspend fun lookupFiles(path: LocalPath, options: LookupOptions): List<LocalPathLookup> =
        lookupFiles(path, defaultLookupIntent, options)

    override suspend fun lookupFiles(
        path: LocalPath,
        intent: AccessIntent,
        options: LookupOptions,
    ): List<LocalPathLookup> {
        val route = router.routeFor(path, intent)
        val lookups = route.ops.lookupFiles(path, options)
        val known = lookups.map { it.lookedUp.path }.toSet()
        val proactive = proactiveChildren(path)
            .filter { it.path !in known }
            .mapNotNull {
                try {
                    lookup(it, intent, options)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RouteUnavailableException) {
                    unknownLookup(it, e)
                }
            }
        return lookups + proactive
    }

    override suspend fun listFiles(path: LocalPath): List<LocalPath> {
        val route = router.routeFor(path, defaultLookupIntent)
        val listed = route.ops.listFiles(path)
        val known = listed.map { it.path }.toSet()
        return listed + proactiveChildren(path).filter { it.path !in known }
    }

    override suspend fun ensurePlanned(path: LocalPath, intent: AccessIntent) {
        router.ensurePlanned(path, intent)
    }

    override suspend fun modeOf(path: LocalPath, intent: AccessIntent): AccessMode =
        router.routeFor(path, intent).mode

    override fun proactiveChildren(parent: LocalPath): Set<LocalPath> = router.proactiveChildren(parent)

    override suspend fun installLogicalAlias(alias: LocalPath, resolved: LocalPath, intent: AccessIntent) {
        router.installLogicalAlias(alias, resolved, intent)
    }

    override fun unknownLookup(path: LocalPath, error: Exception): LocalPathLookup =
        LocalPathLookup.unknown(path, error.message)

    override suspend fun exists(path: LocalPath): Boolean =
        lookup(path, defaultLookupIntent, LookupOptions.BASE.copy(fallbackToUnknown = true)).fileType != FileType.UNKNOWN

    /**
     * Asks the route's ops directly instead of going through a fallback lookup like [exists] does:
     * that lookup reports [FileType.UNKNOWN] for an entry it could not read, which is neither of
     * the two answers a strict probe may give.
     */
    override suspend fun existsStrict(path: LocalPath): Existence = try {
        router.routeFor(path, AccessIntent.Read).ops.existsStrict(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RouteUnavailableException) {
        Existence.UNKNOWN
    }

    override suspend fun delete(path: LocalPath, recursive: Boolean): Boolean {
        val route = router.routeFor(path, AccessIntent.Delete)
        return route.ops.delete(path, recursive)
    }

    override suspend fun createDir(path: LocalPath, createParents: Boolean) {
        val route = router.routeFor(path, AccessIntent.Write)
        route.ops.createDir(path, createParents)
    }

    override suspend fun createFile(path: LocalPath, createParents: Boolean) {
        val route = router.routeFor(path, AccessIntent.Write)
        route.ops.createFile(path, createParents)
    }

    override suspend fun createSymlink(linkPath: LocalPath, targetPath: LocalPath): Boolean {
        val route = router.routeFor(linkPath, AccessIntent.Write)
        return route.ops.createSymlink(linkPath, targetPath)
    }

    override suspend fun readSymbolicLink(linkPath: LocalPath): LocalPath {
        val route = router.routeFor(linkPath, defaultLookupIntent)
        return route.ops.readSymbolicLink(linkPath)
    }

    override suspend fun canonicalize(path: LocalPath): LocalPath {
        val route = router.routeFor(path, defaultLookupIntent)
        return route.ops.canonicalize(path)
    }

    override suspend fun move(source: LocalPath, destination: LocalPath): MoveOutcome {
        val sourceRoute = router.routeFor(source, AccessIntent.Delete)
        val destinationRoute = router.routeFor(destination, AccessIntent.Write)
        if (sourceRoute.mode != destinationRoute.mode) {
            return MoveOutcome.NotSupported(
                "Routed source and destination modes differ: ${sourceRoute.mode} != ${destinationRoute.mode}"
            )
        }
        return sourceRoute.ops.move(source, destination)
    }

    override suspend fun openInputStream(path: LocalPath): InputStream {
        val route = router.routeFor(path, defaultLookupIntent)
        return route.session?.retainLeaseFor(route.ops.openInputStream(path))
            ?: route.ops.openInputStream(path)
    }

    override suspend fun openOutputStream(path: LocalPath, append: Boolean): OutputStream {
        val route = router.routeFor(path, AccessIntent.Write)
        return route.session?.retainLeaseFor(route.ops.openOutputStream(path, append))
            ?: route.ops.openOutputStream(path, append)
    }

    override suspend fun file(path: LocalPath, readWrite: Boolean): FileHandle {
        val route = router.routeFor(path, if (readWrite) AccessIntent.Write else defaultLookupIntent)
        return route.session?.retainLeaseFor(route.ops.file(path, readWrite))
            ?: route.ops.file(path, readWrite)
    }

    override suspend fun setModifiedAt(path: LocalPath, modifiedAt: Instant): Boolean {
        val route = router.routeFor(path, AccessIntent.Write)
        return route.ops.setModifiedAt(path, modifiedAt)
    }

    override suspend fun setPermissions(path: LocalPath, permissions: Permissions): Boolean {
        val route = router.routeFor(path, AccessIntent.Write)
        return route.ops.setPermissions(path, permissions)
    }

    override suspend fun setOwnership(path: LocalPath, ownership: Ownership): Boolean {
        val route = router.routeFor(path, AccessIntent.Write)
        return route.ops.setOwnership(path, ownership)
    }

    override suspend fun canRead(path: LocalPath): Boolean = try {
        val route = router.routeFor(path, defaultLookupIntent)
        route.ops.canRead(path)
    } catch (_: RouteUnavailableException) {
        false
    }

    override suspend fun canWrite(path: LocalPath): Boolean = try {
        val route = router.routeFor(path, AccessIntent.Write)
        route.ops.canWrite(path)
    } catch (_: RouteUnavailableException) {
        false
    }

    override suspend fun getFileSystem(path: LocalPath): FileSystem {
        val route = router.routeFor(path, defaultLookupIntent)
        return route.ops.getFileSystem(path)
    }

    suspend fun routeFor(path: LocalPath, intent: AccessIntent): Route = router.routeFor(path, intent)

    suspend fun batchEligibility(request: BatchEligibilityRequest): BatchEligibility =
        router.batchEligibility(request)
}
