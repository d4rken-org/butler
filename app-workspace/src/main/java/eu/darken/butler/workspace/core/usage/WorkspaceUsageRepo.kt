package eu.darken.butler.workspace.core.usage

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

@Singleton
class WorkspaceUsageRepo @Inject constructor(
    private val settings: WorkspaceUsageSettings,
) {

    /**
     * Workspace types the user reaches for most, ordered by use count first and last use second.
     * Persisted names that no longer map to a [Workspace.Type] are dropped.
     */
    val rankedTypes: Flow<List<Workspace.Type>> = settings.usageData.flow.map { data ->
        data.entries
            .sortedWith(
                compareByDescending<WorkspaceTypeUsage> { it.useCount }
                    .thenByDescending { it.lastUsed }
            )
            .mapNotNull { entry -> Workspace.Type.entries.firstOrNull { it.name == entry.type } }
    }

    /**
     * Increment-or-insert the counter for [type]. [usedAt] is supplied by the caller so the
     * timestamp reflects the moment of creation, not when this coroutine happened to run.
     *
     * Uses an atomic `update` because tracking is fired concurrently — a read-then-write would let
     * two creations read the same count and overwrite each other. Failures are swallowed: usage
     * bookkeeping must never break workspace creation.
     */
    suspend fun track(type: Workspace.Type, usedAt: Instant) {
        try {
            settings.usageData.update { current ->
                val existing = current.entries.firstOrNull { it.type == type.name }
                val updated = existing?.copy(
                    useCount = existing.useCount + 1,
                    lastUsed = usedAt,
                ) ?: WorkspaceTypeUsage(
                    type = type.name,
                    lastUsed = usedAt,
                )
                val entries = (listOf(updated) + current.entries.filter { it.type != type.name })
                    .take(TRACKING_LIMIT)
                current.copy(entries = entries)
            }
            log(TAG) { "Tracked usage of $type at $usedAt" }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to track usage of $type: ${e.asLog()}" }
        }
    }

    companion object {
        private val TAG = logTag("Workspace", "Usage", "Repo")
        private const val TRACKING_LIMIT = 20
    }
}
