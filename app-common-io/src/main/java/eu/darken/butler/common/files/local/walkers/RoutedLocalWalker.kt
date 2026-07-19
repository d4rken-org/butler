package eu.darken.butler.common.files.local.walkers

import eu.darken.butler.common.debug.logging.Logging.Priority.INFO
import eu.darken.butler.common.debug.logging.Logging.Priority.WARN
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathGateway
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
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
 *   only consulted at the start path, at known boundary children, and after failures. This keeps
 *   per-directory overhead and route-cache size near zero on full-device walks.
 * - Escalated subtrees run through sticky [eu.darken.butler.common.files.local.routing.ModeSession]s
 *   (one privileged connection per mode for the whole walk).
 * - Boundary children that the OS hides from directory listings are spliced in and each walked
 *   exactly once, in their own route.
 * - A directory listing that fails with an IO error in a non-escalated mode is retried once per
 *   untried escalation mode (ROOT, then ADB) before the failure reaches [onError] — with the
 *   ORIGINAL exception.
 *
 * Symlink targets are routed by their canonical target path, so following a link from public
 * storage into protected territory escalates like any other boundary.
 */
class RoutedLocalWalker(
    private val routingPolicy: LocalPathRoutingPolicy,
    private val sessionFactory: ModeSessionFactory,
    private val caps: CapabilitySnapshot,
    private val start: LocalPath,
    private val lookupOptions: LookupOptions,
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

        // Boundary roots already spliced, so each is walked exactly once even if the parent
        // directory is listed again through another overlapping path.
        private val scheduledBoundaries = HashSet<String>()

        // Boundary children discovered while listing the current directory (own route, not inherited).
        private val pendingBoundaryRoutes = HashMap<String, Route>()

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
            }

            val hiddenBoundaries = router.knownRouteBoundariesUnder(dirPath)
                .filter { boundary -> boundary.parent?.matches(dirPath) == true }
                .filter { boundary -> children.none { it.lookedUp.matches(boundary) } }
            if (hiddenBoundaries.isEmpty()) return WalkerStrategy.Listing.Children(children)

            val spliced = children.toMutableList()
            for (boundary in hiddenBoundaries) {
                if (!scheduledBoundaries.add(boundary.path)) continue
                try {
                    val boundaryRoute = router.routeFor(boundary, AccessIntent.Read)
                    val boundaryLookup = boundaryRoute.ops.lookup(boundary, lookupOptions)
                    pendingBoundaryRoutes[boundary.path] = boundaryRoute
                    spliced.add(boundaryLookup)
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
         * volume) are excluded from the stream and scheduled through their own stronger route.
         */
        private suspend fun delegatedListing(dirPath: LocalPath, route: Route): WalkerStrategy.Listing? {
            val client = route.ops as? FileOpsClient ?: return null

            val excludedBoundaries = if (route.mode == AccessMode.ISOLATED) {
                router.knownRouteBoundariesUnder(dirPath).filter { scheduledBoundaries.add(it.path) }
            } else {
                emptyList()
            }

            val subtree = client.walk(
                path = dirPath,
                lookupOptions = lookupOptions,
                walkOptions = APathGateway.WalkOptions(
                    onError = onError,
                    followSymlinks = followSymlinks,
                ),
                excludeSubtrees = excludedBoundaries.takeIf { it.isNotEmpty() },
            )

            return WalkerStrategy.Listing.Delegated(flow {
                emitAll(subtree)
                for (boundary in excludedBoundaries) {
                    try {
                        val boundaryRoute = router.routeFor(boundary, AccessIntent.Read)
                        val boundaryClient = boundaryRoute.ops as? FileOpsClient
                        emit(boundaryRoute.ops.lookup(boundary, lookupOptions))
                        if (boundaryClient != null) {
                            emitAll(
                                boundaryClient.walk(
                                    path = boundary,
                                    lookupOptions = lookupOptions,
                                    walkOptions = APathGateway.WalkOptions(
                                        onError = onError,
                                        followSymlinks = followSymlinks,
                                    ),
                                )
                            )
                        } else {
                            emitAll(
                                DirectLocalWalker(
                                    fileSystemOps = boundaryRoute.ops,
                                    start = boundary,
                                    lookupOptions = lookupOptions,
                                    onError = onError,
                                    followSymlinks = followSymlinks,
                                )
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log(tag, WARN) { "Cannot enter boundary $boundary: $e" }
                        if (!onError(LocalPathLookup.unknown(boundary, e.message), e)) throw e
                    }
                }
            })
        }

        override suspend fun onEnqueue(child: LocalPathLookup) {
            val childPath = child.lookedUp.path
            val route = pendingBoundaryRoutes.remove(childPath) ?: currentRoute ?: return
            contexts[childPath] = route
        }

        override suspend fun canonicalize(path: LocalPath): LocalPath {
            val route = currentRoute ?: router.routeFor(path, AccessIntent.Read)
            return runEscalating(path, route, {}) { activeRoute ->
                activeRoute.ops.canonicalize(path)
            }
        }

        override suspend fun lookup(path: LocalPath): LocalPathLookup {
            // Used for symlink targets, which can point anywhere — route by the target path.
            val route = router.routeFor(path, AccessIntent.Read)
            return runEscalating(path, route, {}) { activeRoute ->
                activeRoute.ops.lookup(path, lookupOptions)
            }
        }

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
