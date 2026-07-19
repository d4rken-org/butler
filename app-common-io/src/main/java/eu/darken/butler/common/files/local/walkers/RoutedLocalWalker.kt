package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.isSymlink
import eu.darken.butler.common.files.extensions.matches
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.local.ipc.FileOpsClient
import eu.darken.butler.common.files.local.routing.AccessIntent
import eu.darken.butler.common.files.local.routing.AccessMode
import eu.darken.butler.common.files.local.routing.CapabilitySnapshot
import eu.darken.butler.common.files.local.routing.LocalPathRoutingPolicy
import eu.darken.butler.common.files.local.routing.ModeSessionFactory
import eu.darken.butler.common.files.local.routing.ModeSessionRegistry
import eu.darken.butler.common.files.local.routing.Route
import eu.darken.butler.common.files.local.routing.StaticLocalRouteRouter
import eu.darken.butler.common.ipc.ServiceConnectionLostException
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Route-aware walker for AUTO mode: walks each subtree in the access mode the routing policy
 * assigns to it, escalating mid-walk at known boundaries (e.g. `Android/data` on API 30+) and
 * on runtime failures (a policy-DIRECT directory that turns out to need root).
 *
 * Mode handling:
 * - Children inherit their parent directory's route without consulting the router — the router is
 *   only consulted at the start path, at known boundary children, for symlink targets, and after
 *   failures. This keeps per-directory overhead and route-cache size near zero on full-device walks.
 * - Escalated subtrees run through sticky [eu.darken.butler.common.files.local.routing.ModeSession]s
 *   (one privileged connection per mode for the whole walk).
 * - Known boundary children get their own route whether the OS hides them from the parent listing
 *   (spliced in) or shows them (visible but listing empty through the parent's mode); each is
 *   walked exactly once.
 * - Symlink targets are routed by their canonical target path, and a followed symlink directory is
 *   traversed through the target's route — a link from public storage into protected territory
 *   escalates like any other boundary.
 * - An escalation-worthy failure is retried once per untried escalation mode (ROOT, then ADB)
 *   before reaching [onError] with the ORIGINAL exception. This covers plain listings, boundary
 *   entry, and delegated subtree streams (streams only retry when nothing was emitted yet, so
 *   consumers never see duplicates; a partially-streamed failure is reported via [onError]).
 *
 * Ordering note: delegated ISOLATED subtrees stream their ordinary content first and excluded
 * boundary subtrees after, which deviates from strict in-process LIFO interleaving. This is a
 * documented trade-off of wholesale host-side streaming.
 */
class RoutedLocalWalker(
    private val routingPolicy: LocalPathRoutingPolicy,
    private val sessionFactory: ModeSessionFactory,
    private val caps: CapabilitySnapshot,
    private val start: LocalPath,
    private val lookupOptions: LookupOptions,
    private val pathDoesNotContain: Set<String>? = null,
    private val onFilter: suspend (LocalPathLookup) -> Boolean = { true },
    private val onError: suspend (LocalPathLookup, Exception) -> Boolean = { _, _ -> true },
    private val followSymlinks: Boolean = false,
    /**
     * True when the caller passed no traversal-pruning filter (WalkOptions.isStreamable):
     * escalated subtrees are then delegated wholesale to a host-side streaming walk (one IPC
     * call per subtree) instead of per-directory listing through the session.
     */
    private val streamingEligible: Boolean = false,
) : AbstractFlow<LocalPathLookup>() {
    private val tag = "$TAG#${hashCode()}"

    override suspend fun collectSafely(collector: FlowCollector<LocalPathLookup>) {
        val registry = ModeSessionRegistry(sessionFactory)
        try {
            val router = StaticLocalRouteRouter(
                policy = routingPolicy,
                caps = caps,
                sessions = registry,
            )
            LocalWalkerCore(
                strategy = Strategy(router),
                start = start,
                onFilter = onFilter,
                onError = onError,
                followSymlinks = followSymlinks,
                tag = tag,
            ).walk(collector)
        } finally {
            registry.close()
        }
    }

    private inner class Strategy(
        private val router: StaticLocalRouteRouter,
    ) : WalkerStrategy {
        // Route context for directories the core has enqueued, keyed by absolute path.
        // Entries are removed when the directory is listed, so this stays bounded by queue size.
        private val contexts = HashMap<String, Route>()

        // Route of the directory currently being processed; children inherit it via onEnqueue.
        private var currentRoute: Route? = null

        // Boundary roots already scheduled, so each is walked exactly once.
        private val scheduledBoundaries = HashSet<String>()

        // Boundary children of the current directory that need their own route instead of
        // inheriting (both hidden/spliced and visible-but-unreadable-through-parent ones).
        private val pendingBoundaryRoutes = HashMap<String, Route>()

        // Route of the most recently resolved symlink target; consumed by onEnqueue so a
        // followed symlink directory is listed through the target's route, not the parent's.
        private var pendingSymlinkTargetRoute: Route? = null

        override suspend fun lookupStart(start: LocalPath): LocalPathLookup {
            val route = router.routeFor(start, AccessIntent.Read)
            currentRoute = route
            contexts[start.path] = route
            return runEscalating(start, route, { contexts[start.path] = it }) { activeRoute ->
                activeRoute.ops.lookup(start, lookupOptions)
            }
        }

        override suspend fun list(dir: LocalPathLookup): WalkerStrategy.Listing {
            val dirPath = dir.lookedUp
            val route = contexts.remove(dirPath.path) ?: router.routeFor(dirPath, AccessIntent.Read)
            currentRoute = route
            pendingBoundaryRoutes.clear()

            if (streamingEligible && route.mode != AccessMode.DIRECT) {
                delegatedListing(dirPath, route)?.let { return it }
            }

            val children = runEscalating(dirPath, route, { currentRoute = it }) { activeRoute ->
                activeRoute.ops.lookupFiles(dirPath, lookupOptions)
            }.filterNot(::isExcluded)

            val boundaries = router.knownRouteBoundariesUnder(dirPath)
                .filter { boundary -> boundary.parent?.matches(dirPath) == true }
            if (boundaries.isEmpty()) return WalkerStrategy.Listing.Children(children)

            val spliced = children.toMutableList()
            for (boundary in boundaries) {
                if (isExcluded(boundary)) continue
                if (!scheduledBoundaries.add(boundary.path)) continue
                val visibleChild = children.any { it.lookedUp.matches(boundary) }
                try {
                    val boundaryRoute = router.routeFor(boundary, AccessIntent.Read)
                    if (visibleChild) {
                        // Present in the parent's listing, but its CONTENT needs the boundary
                        // route (a DIRECT listing of Android/data is just silently empty).
                        pendingBoundaryRoutes[boundary.path] = boundaryRoute
                    } else {
                        val boundaryLookup = runEscalating(
                            boundary,
                            boundaryRoute,
                            { pendingBoundaryRoutes[boundary.path] = it },
                        ) { activeRoute ->
                            activeRoute.ops.lookup(boundary, lookupOptions)
                        }
                        pendingBoundaryRoutes.putIfAbsent(boundary.path, boundaryRoute)
                        spliced.add(boundaryLookup)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // No mechanism to enter this boundary (e.g. no root/ADB) — report it like a
                    // failed subtree instead of silently omitting it, then keep walking the rest.
                    log(tag, WARN) { "Cannot enter boundary $boundary: $e" }
                    if (!onError(LocalPathLookup.unknown(boundary, e.message), e)) throw e
                }
            }
            return WalkerStrategy.Listing.Children(spliced)
        }

        /**
         * Delegates the whole subtree under [dirPath] to a single host-side streaming walk via
         * the escalated session's [FileOpsClient]. Returns null when the session's ops aren't
         * IPC-backed (e.g. ISOLATED fell back to DIRECT) — the caller then lists per-directory.
         *
         * ROOT/ADB hosts see at least as much as any deeper mode, so wholesale delegation is
         * safe there (ADB is only ever chosen when ROOT is unavailable). An ISOLATED host has
         * plain app privileges though: known deeper boundaries (`Android/data` on a removable
         * volume) are excluded from the stream and walked through their own stronger route.
         */
        private suspend fun delegatedListing(dirPath: LocalPath, route: Route): WalkerStrategy.Listing? {
            if (route.ops !is FileOpsClient) return null

            val excludedBoundaries = if (route.mode == AccessMode.ISOLATED) {
                router.knownRouteBoundariesUnder(dirPath)
                    .filterNot(::isExcluded)
                    .filter { scheduledBoundaries.add(it.path) }
            } else {
                emptyList()
            }

            return WalkerStrategy.Listing.Delegated(flow {
                emitSubtree(dirPath, route, excludedBoundaries.takeIf { it.isNotEmpty() })
                for (boundary in excludedBoundaries) {
                    try {
                        var boundaryRoute = router.routeFor(boundary, AccessIntent.Read)
                        val boundaryLookup = runEscalating(boundary, boundaryRoute, { boundaryRoute = it }) {
                            it.ops.lookup(boundary, lookupOptions)
                        }
                        if (isExcluded(boundaryLookup)) continue
                        emit(boundaryLookup)
                        emitSubtree(boundary, boundaryRoute)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log(tag, WARN) { "Cannot enter boundary $boundary: $e" }
                        if (!onError(LocalPathLookup.unknown(boundary, e.message), e)) throw e
                    }
                }
            })
        }

        /**
         * Streams one subtree through [route], retrying once per untried escalation mode when the
         * stream fails before emitting anything. A stream that fails after items were emitted is
         * NOT retried (that would duplicate them) — the failure goes to [onError] instead.
         */
        private suspend fun FlowCollector<LocalPathLookup>.emitSubtree(
            path: LocalPath,
            route: Route,
            excludeSubtrees: List<LocalPath>? = null,
        ) {
            var emittedAny = false
            try {
                subtreeFlowVia(route, path, excludeSubtrees).collect {
                    emittedAny = true
                    emit(it)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fallback = if (!emittedAny && e.isEscalationWorthy()) {
                    router.routeAfterFailure(path, AccessIntent.Read, route.mode)
                } else {
                    null
                }
                if (fallback == null) {
                    log(tag, WARN) { "Subtree stream failed for $path: $e" }
                    if (!onError(LocalPathLookup.unknown(path, e.message), e)) throw e
                    return
                }
                log(tag, INFO) { "Escalating subtree $path from ${route.mode} to ${fallback.mode} after: $e" }
                try {
                    emitAll(subtreeFlowVia(fallback, path, excludeSubtrees))
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    e.addSuppressed(e2)
                    log(tag, WARN) { "Subtree stream retry failed for $path: $e" }
                    if (!onError(LocalPathLookup.unknown(path, e.message), e)) throw e
                }
            }
        }

        private fun subtreeFlowVia(route: Route, path: LocalPath, excludeSubtrees: List<LocalPath>?) =
            when (val client = route.ops as? FileOpsClient) {
                null -> DirectLocalWalker(
                    fileSystemOps = route.ops,
                    start = path,
                    lookupOptions = lookupOptions,
                    onFilter = { !isExcluded(it) },
                    onError = onError,
                    followSymlinks = followSymlinks,
                )
                else -> client.walk(
                    path = path,
                    lookupOptions = lookupOptions,
                    walkOptions = APathGateway.WalkOptions(
                        pathDoesNotContain = pathDoesNotContain,
                        onError = onError,
                        followSymlinks = followSymlinks,
                    ),
                    excludeSubtrees = excludeSubtrees,
                )
            }

        override suspend fun onEnqueue(child: LocalPathLookup) {
            val childPath = child.lookedUp.path
            val symlinkTargetRoute = pendingSymlinkTargetRoute.also { pendingSymlinkTargetRoute = null }
            val route = pendingBoundaryRoutes.remove(childPath)
                ?: symlinkTargetRoute.takeIf { child.isSymlink }
                ?: currentRoute
                ?: return
            contexts[childPath] = route
        }

        override suspend fun canonicalize(path: LocalPath): LocalPath {
            pendingSymlinkTargetRoute = null
            val route = currentRoute ?: router.routeFor(path, AccessIntent.Read)
            return runEscalating(path, route, {}) { activeRoute ->
                activeRoute.ops.canonicalize(path)
            }
        }

        override suspend fun lookup(path: LocalPath): LocalPathLookup {
            // Used for symlink targets, which can point anywhere — route by the target path and
            // remember the route so traversal of the followed link uses it too.
            val route = router.routeFor(path, AccessIntent.Read)
            pendingSymlinkTargetRoute = route
            return runEscalating(path, route, { pendingSymlinkTargetRoute = it }) { activeRoute ->
                activeRoute.ops.lookup(path, lookupOptions)
            }
        }

        private fun isExcluded(lookup: LocalPathLookup): Boolean = isExcluded(lookup.lookedUp)

        private fun isExcluded(path: LocalPath): Boolean =
            pathDoesNotContain?.any { path.path.contains(it) } == true

        /**
         * Runs [block] with [route]; on an escalation-worthy failure, retries once per untried
         * escalation mode via [StaticLocalRouteRouter.routeAfterFailure]. The ORIGINAL exception
         * is thrown when no mode is left, with retry failures attached as suppressed.
         */
        private suspend fun <T> runEscalating(
            path: LocalPath,
            route: Route,
            onRouteChanged: (Route) -> Unit,
            block: suspend (Route) -> T,
        ): T {
            var activeRoute = route
            var original: Exception? = null
            while (true) {
                try {
                    return block(activeRoute)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (original == null) {
                        original = e
                    } else {
                        original.addSuppressed(e)
                    }
                    if (!e.isEscalationWorthy()) throw original
                    val fallback = router.routeAfterFailure(path, AccessIntent.Read, activeRoute.mode)
                        ?: throw original
                    log(tag, INFO) { "Escalating $path from ${activeRoute.mode} to ${fallback.mode} after: $e" }
                    activeRoute = fallback
                    onRouteChanged(fallback)
                }
            }
        }

        private fun Exception.isEscalationWorthy(): Boolean =
            this is IOException || this is ServiceConnectionLostException
    }

    companion object {
        private val TAG = logTag("Gateway", "Local", "Walker", "Routed")
    }
}
