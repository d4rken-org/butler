package eu.darken.butler.workspace.core.operations.history

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.operations.CompletedOperationSnapshot
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryDao
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryPathEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryWithPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

@Singleton
class OperationHistoryRepo @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val operationsManager: OperationsManager,
    private val dao: OperationHistoryDao,
    private val historySettings: HistorySettings,
) {

    init {
        log(TAG, INFO) { "init(): subscribing to OperationsManager.completedOperations" }

        operationsManager.completedOperations
            .onEach { snapshot ->
                // try/catch is critical: an unhandled exception cancels the collector and every
                // future op completion is lost until app restart.
                try {
                    if (!historySettings.saveHistory.value()) return@onEach
                    if (snapshot.metadata.kind == null) return@onEach
                    persist(snapshot)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    log(TAG, ERROR) { "Failed to persist op ${snapshot.id}: ${e.asLog()}" }
                }
            }
            .launchIn(appScope)

        // Re-trim if the user shrinks the cap.
        historySettings.maxHistoryItems.flow
            .onEach { cap ->
                runCatching { dao.trimToMax(cap) }
                    .onFailure { log(TAG, ERROR) { "trimToMax($cap) failed: ${it.asLog()}" } }
            }
            .launchIn(appScope)
    }

    // ─── ingest ───────────────────────────────────────────────────────────────────

    private suspend fun persist(snapshot: CompletedOperationSnapshot) {
        val metadata = snapshot.metadata
        val kind = metadata.kind ?: return
        val state = snapshot.state

        val outcome = when {
            state.error is CancellationException -> HistoryOutcome.CANCELLED
            state.error != null -> HistoryOutcome.FAILED
            (state.report?.partialErrorCount ?: 0) > 0 -> HistoryOutcome.PARTIAL
            else -> HistoryOutcome.COMPLETED
        }

        val originType = when (metadata.origin) {
            is Operation.Metadata.Origin.Explorer -> HistoryEntry.OriginType.EXPLORER
            is Operation.Metadata.Origin.Searcher -> HistoryEntry.OriginType.SEARCHER
            is Operation.Metadata.Origin.Saver -> HistoryEntry.OriginType.SAVER
            is Operation.Metadata.Origin.Developer -> HistoryEntry.OriginType.DEVELOPER
            is Operation.Metadata.Origin.Viewer -> HistoryEntry.OriginType.VIEWER
        }

        val pathChanges = collectPathChanges(metadata, state)
        val capped = pathChanges.take(MAX_PATHS_PER_OP)

        val rowId = Uuid.random().toString()
        val pathEntities = capped.mapIndexed { index, change ->
            OperationHistoryPathEntity(
                operationHistoryId = rowId,
                path = change.path,
                previousPath = change.previousPath,
                change = change.change.name,
                sortIndex = index,
            )
        }

        val entry = OperationHistoryEntity(
            id = rowId,
            kind = kind.name,
            intent = metadata.intent?.name,
            originType = originType.name,
            originWorkspaceId = metadata.origin.workspaceId.longTag,
            title = metadata.title.get(context),
            description = metadata.description.get(context),
            summary = state.summary.get(context).takeIf { it.isNotBlank() },
            startedAt = state.startedAt,
            completedAt = state.completedAt,
            durationMs = (state.completedAt - state.startedAt).inWholeMilliseconds.coerceAtLeast(0),
            outcome = outcome.name,
            errorMessage = state.error?.message,
            errorClass = state.error?.javaClass?.name,
            affectedPathsCount = pathChanges.size,
            partialErrorCount = state.report?.partialErrorCount ?: 0,
            pathsTruncated = pathChanges.size > MAX_PATHS_PER_OP,
        )

        dao.insertWithPathsAndTrim(
            entry = entry,
            paths = pathEntities,
            maxItems = historySettings.maxHistoryItems.value(),
        )
    }

    /**
     * Collect the union of `report.affectedPaths` and `metadata.intendedPaths`, deduplicated by
     * the resolved path string. Affected paths come first (preserve change-type semantics); intended
     * paths fill in for failed/cancelled ops where the report is null/empty.
     */
    private fun collectPathChanges(
        metadata: Operation.Metadata,
        state: Operation.State.Completed,
    ): List<HistoryEntry.PathChange> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<HistoryEntry.PathChange>()

        state.report?.affectedPaths?.forEach { change ->
            val pathStr = change.path.userReadablePath.get(context)
            if (seen.add(pathStr)) {
                out += HistoryEntry.PathChange(
                    path = pathStr,
                    previousPath = change.previousPath?.userReadablePath?.get(context),
                    change = change.change,
                )
            }
        }

        // Fill from intendedPaths so failed/cancelled ops still have something queryable.
        metadata.intendedPaths?.forEach { intended ->
            val pathStr = intended.userReadablePath.get(context)
            if (seen.add(pathStr)) {
                // Synthetic change kind: inferred from operation kind (best-effort label).
                val change = when (metadata.kind) {
                    Operation.Metadata.Kind.DELETE -> Operation.Report.PathChange.Change.REMOVED
                    Operation.Metadata.Kind.MOVE -> Operation.Report.PathChange.Change.MOVED
                    Operation.Metadata.Kind.COPY,
                    Operation.Metadata.Kind.SAVE,
                    Operation.Metadata.Kind.CREATE_FILE,
                    Operation.Metadata.Kind.CREATE_FOLDER,
                    Operation.Metadata.Kind.COMPRESS,
                    Operation.Metadata.Kind.EXTRACT,
                    Operation.Metadata.Kind.RESTORE,
                    null -> Operation.Report.PathChange.Change.ADDED
                }
                out += HistoryEntry.PathChange(
                    path = pathStr,
                    previousPath = null,
                    change = change,
                )
            }
        }

        return out
    }

    // ─── query ────────────────────────────────────────────────────────────────────

    /**
     * Observe history entries matching [filter], ordered newest-first, capped at [limit].
     * Two-phase query inside: filter+limit by operation IDs first, then load with paths via @Relation.
     *
     * Multi-path scopes (`pathScopes.size > 1`) are joined with OR at SQL level via a built
     * [SimpleSQLiteQuery]. Single query → ORDER BY + LIMIT apply globally and we never fan out N
     * sub-queries (which would risk bind-arg explosion at the IN(:ids) stage).
     *
     * Path-scope LIKE wildcards (`%`, `_`, `\`) in the user-provided paths are escaped before
     * binding. Both `path` and `previousPath` columns are matched, so a move/rename OUT of a
     * scoped folder still appears under that scope.
     */
    fun query(filter: HistoryFilter, limit: Int): Flow<List<HistoryEntry>> {
        // "No filter" for an enum dimension means "all values". Empty IN clauses are invalid SQL.
        val outcomes = (filter.outcomes.takeIf { it.isNotEmpty() } ?: HistoryOutcome.entries.toSet())
            .map { it.name }
        val kinds = (filter.kinds.takeIf { it.isNotEmpty() } ?: Operation.Metadata.Kind.entries.toSet())
            .map { it.name }

        val idsFlow: Flow<List<String>> = if (filter.pathScopes.isEmpty()) {
            dao.observeIds(outcomes, kinds, limit)
        } else {
            dao.observeIdsRaw(buildScopedIdsQuery(outcomes, kinds, filter.pathScopes, limit))
        }

        return idsFlow.flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList())
            else dao.loadByIds(ids).map { rows -> rows.map { it.toDomain() } }
        }
    }

    private fun buildScopedIdsQuery(
        outcomes: List<String>,
        kinds: List<String>,
        pathScopes: Collection<String>,
        limit: Int,
    ): SimpleSQLiteQuery = buildScopedIdsQueryStatic(outcomes, kinds, pathScopes, limit)

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun delete(id: String) {
        log(TAG, INFO) { "delete(): $id" }
        runCatching { dao.deleteById(id) }
            .onFailure { log(TAG, ERROR) { "delete($id) failed: ${it.asLog()}" } }
    }

    suspend fun clearAll() {
        log(TAG, INFO) { "clearAll()" }
        dao.deleteAll()
    }

    // ─── helpers ──────────────────────────────────────────────────────────────────

    internal fun escapeLikePattern(input: String): String = escapeLikePatternStatic(input)

    private fun OperationHistoryWithPaths.toDomain(): HistoryEntry = HistoryEntry(
        id = entry.id,
        kind = Operation.Metadata.Kind.valueOf(entry.kind),
        intent = entry.intent?.let { Operation.Metadata.Intent.valueOf(it) },
        originType = HistoryEntry.OriginType.valueOf(entry.originType),
        originWorkspaceId = entry.originWorkspaceId,
        title = entry.title,
        description = entry.description,
        summary = entry.summary,
        startedAt = entry.startedAt,
        completedAt = entry.completedAt,
        duration = HistoryEntry.durationOf(entry.durationMs),
        outcome = HistoryOutcome.valueOf(entry.outcome),
        errorMessage = entry.errorMessage,
        errorClass = entry.errorClass,
        affectedPathsCount = entry.affectedPathsCount,
        partialErrorCount = entry.partialErrorCount,
        pathsTruncated = entry.pathsTruncated,
        paths = paths.sortedBy { it.sortIndex }.map { p ->
            HistoryEntry.PathChange(
                path = p.path,
                previousPath = p.previousPath,
                change = Operation.Report.PathChange.Change.valueOf(p.change),
            )
        },
    )

    companion object {
        private val TAG = logTag("Workspace", "Operations", "History", "Repo")
        const val MAX_PATHS_PER_OP = 200

        /**
         * Trim, drop trailing slashes (except a lone `/` root), null on blank. De-dup is the
         * caller's responsibility (use a Set). Exposed at companion level for unit testing.
         */
        fun normalizePathScope(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed == "/") return "/"
            return trimmed.trimEnd('/').takeIf { it.isNotEmpty() }
        }

        /**
         * Escape SQL LIKE wildcards so user path components containing literal `%` or `_` are
         * matched verbatim. The DAO binds with `ESCAPE '\'`, so we replace `\` first to avoid
         * double-escaping. Exposed at companion level for unit testing.
         */
        internal fun escapeLikePatternStatic(input: String): String =
            input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /**
         * Build the dynamic SQL for the multi-scope path filter. Each scope contributes 4 bind
         * placeholders (path/previousPath × exact/descendant). All scopes joined with OR. A single
         * SQL query keeps ORDER BY + LIMIT global across scopes — no client-side union, no
         * bind-arg explosion via `IN (:ids)`. Exposed at companion level for unit testing.
         */
        internal fun buildScopedIdsQueryStatic(
            outcomes: List<String>,
            kinds: List<String>,
            pathScopes: Collection<String>,
            limit: Int,
        ): SimpleSQLiteQuery {
            val outcomesPh = List(outcomes.size) { "?" }.joinToString(",")
            val kindsPh = List(kinds.size) { "?" }.joinToString(",")
            val scopePredicate = pathScopes.joinToString(" OR ") {
                "(p.path = ? OR p.path LIKE ? || '/%' ESCAPE '\\') " +
                    "OR (p.previousPath = ? OR p.previousPath LIKE ? || '/%' ESCAPE '\\')"
            }
            val sql = """
                SELECT id FROM operation_history
                WHERE outcome IN ($outcomesPh)
                  AND kind IN ($kindsPh)
                  AND EXISTS (
                    SELECT 1 FROM operation_history_paths p
                    WHERE p.operationHistoryId = operation_history.id
                      AND ($scopePredicate)
                  )
                ORDER BY completedAt DESC
                LIMIT ?
            """.trimIndent()
            val args = buildList<Any> {
                addAll(outcomes)
                addAll(kinds)
                for (scope in pathScopes) {
                    val escaped = escapeLikePatternStatic(scope)
                    add(escaped); add(escaped); add(escaped); add(escaped)
                }
                add(limit)
            }
            return SimpleSQLiteQuery(sql, args.toTypedArray())
        }
    }
}
