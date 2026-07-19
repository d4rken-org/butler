package eu.darken.butler.searcher.core.engine.backend

import eu.darken.butler.common.files.APath
import eu.darken.butler.permissions.core.PathRequirements
import eu.darken.butler.searcher.core.SearchItem
import eu.darken.butler.searcher.core.SearchQuery
import eu.darken.butler.workspace.contracts.searcher.FilterCondition
import eu.darken.butler.workspace.contracts.searcher.SearchTarget
import eu.darken.butler.workspace.core.Workspace
import kotlinx.coroutines.flow.Flow

/**
 * A source the search engine can scan for one [SearchTarget].
 *
 * Backends are multibound singletons (see SearchBackendModule); the engine dispatches each target
 * to the highest-[priority] backend whose [canHandle] accepts it. Scope note: this seam is
 * dispatch-level for path-like backends (filesystem now, MediaStore later) — progress, results
 * ([SearchItem]) and requirements are still path-shaped. A non-path backend (network) will need
 * those models generalized when it is actually designed.
 */
interface SearchBackend {

    /** Higher wins when multiple backends can handle a target. */
    val priority: Int

    fun canHandle(target: SearchTarget): Boolean

    /** Whether this backend can evaluate the given filter condition during a scan. */
    fun supports(condition: FilterCondition): Boolean

    /** Setup/permission requirements to scan [target]; empty flow value when none. */
    fun monitorRequirements(target: SearchTarget): Flow<PathRequirements>

    /** Streams matches for the session's target. Cancellation-transparent, cold per collection. */
    suspend fun scan(session: ScanSession): Flow<SearchItem>

    data class ScanSession(
        val workspaceId: Workspace.Id,
        val target: SearchTarget,
        val query: SearchQuery,
        val includeBinaries: Boolean,
        val onProgress: (ScanProgress) -> Unit,
    )

    data class ScanProgress(
        val currentPath: APath<*>?,
        val itemsScanned: Int,
        val resultsFound: Int,
        val errorCount: Int = 0,
        val firstErrorPath: APath<*>? = null,
    )
}
