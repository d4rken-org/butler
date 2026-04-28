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
}
