package eu.darken.butler.common.files.local.routing

import eu.darken.butler.common.adb.AdbUnavailableException
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.extensions.isDescendantOfOrSelf
import eu.darken.butler.common.files.local.service.IsolatedServiceClient.ServiceBindException
import eu.darken.butler.common.root.RootUnavailableException
import java.util.concurrent.ConcurrentHashMap

class StaticLocalRouteRouter(
    private val policy: LocalPathRoutingPolicy,
    private val caps: CapabilitySnapshot,
    private val sessions: ModeSessionRegistry,
) {
    private data class RouteKey(
        val path: String,
        val intent: AccessIntent,
    )

    private data class AliasKey(
        val path: String,
        val intent: AccessIntent,
    )

    private val routes = ConcurrentHashMap<RouteKey, Route>()
    private val aliases = ConcurrentHashMap<AliasKey, LocalPath>()
    private val failedModes = ConcurrentHashMap<RouteKey, MutableSet<AccessMode>>()

    suspend fun routeFor(path: LocalPath, intent: AccessIntent): Route {
        val routedPath = resolveAlias(path, intent)
        val key = RouteKey(routedPath.path, intent)
        routes[key]?.let { return it }

        val requestedMode = when (val decision = policy.classify(routedPath, intent, caps)) {
            is RouteDecision.Allowed -> decision.mode
            RouteDecision.Denied -> throw RouteUnavailableException(path, intent)
        }

        val session = try {
            sessions.getOrOpen(requestedMode)
        } catch (e: RootUnavailableException) {
            throw routeUnavailable(path, intent, e)
        } catch (e: AdbUnavailableException) {
            throw routeUnavailable(path, intent, e)
        } catch (e: ServiceBindException) {
            throw routeUnavailable(path, intent, e)
        }
        return Route(
            mode = session.mode,
            ops = session.ops,
            batch = session.batch,
            session = session,
        ).also { routes[key] = it }
    }

    suspend fun ensurePlanned(path: LocalPath, intent: AccessIntent) {
        routeFor(path, intent)
    }

    /**
     * Re-routes [path] after an operation through [failedMode] failed at runtime (e.g. a
     * policy-DIRECT directory that turns out to need elevation). Unlike [routeFor], this bypasses
     * the cached route and picks the next untried escalation mode (ROOT, then ADB).
     *
     * Attempted modes are tracked per (path, intent), so repeated calls walk down the candidate
     * list and never loop. Returns null when no untried mode remains — the caller should surface
     * the ORIGINAL failure, not a routing error.
     */
    suspend fun routeAfterFailure(path: LocalPath, intent: AccessIntent, failedMode: AccessMode): Route? {
        val routedPath = resolveAlias(path, intent)
        val key = RouteKey(routedPath.path, intent)
        val attempted = failedModes.getOrPut(key) { ConcurrentHashMap.newKeySet() }
        attempted.add(failedMode)

        for (candidate in ESCALATION_ORDER) {
            if (!attempted.add(candidate)) continue

            val available = when (candidate) {
                AccessMode.ROOT -> caps.hasRoot()
                AccessMode.ADB -> caps.hasAdb()
                else -> false
            }
            if (!available) continue

            val session = try {
                sessions.getOrOpen(candidate)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            return Route(
                mode = session.mode,
                ops = session.ops,
                batch = session.batch,
                session = session,
            ).also { routes[key] = it }
        }
        return null
    }

    fun proactiveChildren(parent: LocalPath): Set<LocalPath> = policy.proactiveChildren(parent)

    fun knownRouteBoundariesUnder(parent: LocalPath): Set<LocalPath> = policy.knownRouteBoundariesUnder(parent)

    fun isPublicOrRemovableDestination(path: LocalPath): Boolean = policy.isPublicOrRemovableDestination(path)

    suspend fun installLogicalAlias(alias: LocalPath, resolved: LocalPath, intent: AccessIntent) {
        aliases[AliasKey(alias.path, intent)] = resolved
        routes[RouteKey(alias.path, intent)] = routeFor(resolved, intent)
    }

    suspend fun batchEligibility(request: BatchEligibilityRequest): BatchEligibility =
        policy.batchEligibility(request)

    private fun resolveAlias(path: LocalPath, intent: AccessIntent): LocalPath {
        val alias = aliases
            .filterKeys { it.intent == intent }
            .mapKeys { LocalPath.build(it.key.path) }
            .filterKeys { path.isDescendantOfOrSelf(it) }
            .maxByOrNull { it.key.path.length }
            ?: return path

        val suffix = path.path
            .removePrefix(alias.key.path)
            .trim('/')
            .split('/')
            .filter { it.isNotEmpty() }

        return if (suffix.isEmpty()) alias.value else alias.value.child(*suffix.toTypedArray())
    }

    private fun routeUnavailable(path: LocalPath, intent: AccessIntent, cause: Exception): RouteUnavailableException =
        RouteUnavailableException(path, intent).apply { initCause(cause) }

    companion object {
        private val ESCALATION_ORDER = listOf(AccessMode.ROOT, AccessMode.ADB)
    }
}
