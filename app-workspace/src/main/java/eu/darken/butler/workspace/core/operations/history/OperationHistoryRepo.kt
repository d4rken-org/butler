package eu.darken.butler.workspace.core.operations.history

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.operations.CompletedOperationSnapshot
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryDao
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryDatabase
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryPathEntity
import eu.darken.butler.workspace.core.operations.history.db.OperationHistoryScopeEntity
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

@Singleton
class OperationHistoryRepo @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    private val operationsManager: OperationsManager,
    private val database: OperationHistoryDatabase,
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

        val reportedChanges = collectReportedChanges(state)
        val scopePaths = collectScopePaths(metadata, state)

        // The row is keyed by the operation it records, so UI holding an Operation.Id (the
        // operation details sheet) can address this entry directly. Pinned by
        // OperationHistoryPersistTest.
        val rowId = snapshot.id.longTag
        val pathEntities = reportedChanges.take(MAX_PATHS_PER_OP).mapIndexed { index, change ->
            OperationHistoryPathEntity(
                operationHistoryId = rowId,
                path = change.path,
                previousPath = change.previousPath,
                change = change.change.name,
                sortIndex = index,
            )
        }
        val scopeEntities = scopePaths.mapIndexed { index, path ->
            OperationHistoryScopeEntity(
                operationHistoryId = rowId,
                path = path,
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
            affectedPathsCount = reportedChanges.size,
            partialErrorCount = state.report?.partialErrorCount ?: 0,
            pathsTruncated = reportedChanges.size > MAX_PATHS_PER_OP,
            primaryPath = state.report?.subjectPath?.userReadablePath?.get(context)
                ?: metadata.pathPlan?.representativePath?.userReadablePath?.get(context),
        )

        dao.insertWithPathsAndTrim(
            entry = entry,
            paths = pathEntities,
            scopePaths = scopeEntities,
            maxItems = historySettings.maxHistoryItems.value(),
        )

        trimToMaxBytes()
    }

    /**
     * Size-based retention. The item cap bounds how many operations are kept, not how much they
     * weigh: the scope index is uncapped per operation, so a single delete over 100k files writes
     * megabytes of rows. Drops the oldest operations in 10% steps until the used bytes fit
     * [limitBytes], then reclaims the freed pages.
     *
     * Never empties the table: an operation that on its own exceeds the limit is kept, otherwise
     * history would permanently show nothing on a device that does such operations routinely.
     *
     * Failure is logged and swallowed - a trim problem must not lose the write that triggered it.
     */
    internal suspend fun trimToMaxBytes(limitBytes: Long = MAX_DB_SIZE_BYTES) {
        runCatching {
            var deletedAny = false
            val db = database.openHelper.writableDatabase
            while (true) {
                val used =
                    (db.pragmaLong("page_count") - db.pragmaLong("freelist_count")) * db.pragmaLong("page_size")
                if (used <= limitBytes) break
                val count = dao.getCount()
                if (count <= 1) {
                    log(TAG, WARN) { "trimToMaxBytes(): $used bytes held by a single operation, keeping it" }
                    break
                }
                log(TAG, INFO) { "trimToMaxBytes(): $used bytes over $limitBytes, trimming $count entries" }
                dao.deleteOldest((count / 10).coerceAtLeast(1))
                deletedAny = true
            }
            // VACUUM can't run inside a transaction, hence out here instead of in a @Transaction DAO
            // method: deleting rows only moves pages onto the freelist, the file itself never shrinks.
            // The trigger is stateless on purpose: a VACUUM that fails (SQLITE_BUSY, low free disk)
            // leaves the file over the ceiling with reclaimable pages on the freelist, so the next
            // persist retries it without any pending flag. It terminates because the loop above
            // already pushed the used bytes to the limit, so a successful VACUUM brings the file
            // itself to at most the limit and the condition goes false. The free-page term is what
            // stops a pointless retry loop when the count <= 1 stop deliberately keeps a single
            // operation that outweighs the whole limit.
            val totalBytes = db.pragmaLong("page_count") * db.pragmaLong("page_size")
            val freeBytes = db.pragmaLong("freelist_count") * db.pragmaLong("page_size")
            val overPhysicalLimitWithFreePages = totalBytes > limitBytes && freeBytes > 0
            val heavilyFragmented = totalBytes > 0 && freeBytes * 4 > totalBytes
            if (deletedAny || overPhysicalLimitWithFreePages || heavilyFragmented) db.execSQL("VACUUM")
        }.onFailure { log(TAG, ERROR) { "trimToMaxBytes($limitBytes) failed: ${it.asLog()}" } }
    }

    /**
     * Room parses `@Query` SQL with its own grammar, which rejects the `pragma_*()` table-valued
     * function form, so the pragmas are read straight off the database handle instead.
     */
    private fun SupportSQLiteDatabase.pragmaLong(pragma: String): Long =
        query("PRAGMA $pragma").use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    /**
     * The audit record: what the operation actually reported changing, deduplicated by the resolved
     * path string. Paths the operation merely read or intended to touch are NOT included - they'd be
     * displayed as changes they never were (a single-file copy would claim to have added the source
     * file and the destination folder). They go into the scope index instead.
     */
    private fun collectReportedChanges(
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

        return out
    }

    /**
     * The search index that backs the path-scope filter: the operation's planned scope paths,
     * reported paths and move sources, plus every one of their parent directories. Never displayed
     * as a change.
     *
     * Uncapped: a scope only matches a row exactly or as its ancestor prefix, so dropping any path
     * would silently make that path unfilterable - an ancestor row can't stand in for it. The total
     * database size is bounded by [trimToMaxBytes] instead of by a per-operation row cap.
     *
     * Parents come first so the collapsed folders lead the sortIndex order the attempted-paths sheet
     * renders in, ahead of the potentially thousands of individual files below them.
     */
    private fun collectScopePaths(
        metadata: Operation.Metadata,
        state: Operation.State.Completed,
    ): List<String> {
        val candidates = buildList<APath<*>> {
            metadata.pathPlan?.let { addAll(it.allPaths) }
            state.report?.affectedPaths?.forEach { change ->
                add(change.path)
                change.previousPath?.let { add(it) }
            }
        }

        val parents = candidates.mapNotNull { it.parent?.userReadablePath?.get(context) }
        val exact = candidates.map { it.userReadablePath.get(context) }

        return (parents + exact).distinct()
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
     * Scopes are matched against `operation_history_scope`, which holds every path the operation
     * touched or intended to touch plus their parent directories - so a move/rename OUT of a scoped
     * folder still appears under that scope.
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

    /**
     * Observe one entry by its id, which is the id of the operation it records. Emits null while no
     * such row exists, so a caller that navigates to an operation right as it finishes can wait for
     * the asynchronous ingest instead of missing it.
     */
    fun observeEntry(id: String): Flow<HistoryEntry?> = dao.observeById(id).map { it?.toDomain() }

    /**
     * Paths the operation touched or intended to touch, loaded on demand. Shown for entries that
     * reported no changes at all, where there'd otherwise be nothing to display.
     *
     * The scope index is uncapped per operation, so only the first [MAX_PATHS_PER_OP] rows are
     * loaded - the same bound the audit table has. [AttemptedPaths.totalCount] carries the real
     * size so the sheet can say what it isn't showing.
     */
    suspend fun getAttemptedPaths(id: String): AttemptedPaths = AttemptedPaths(
        paths = dao.getScopePathsPreview(id, MAX_PATHS_PER_OP).map { it.path },
        totalCount = dao.getScopePathCount(id),
    )

    data class AttemptedPaths(
        val paths: List<String>,
        val totalCount: Int,
    )

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
        primaryPath = entry.primaryPath,
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
         * Hard ceiling on the whole history database, enforced by [trimToMaxBytes]. Deliberately not
         * a setting: it's a safety net against pathological operations, not a user-facing knob.
         */
        internal const val MAX_DB_SIZE_BYTES = 32L * 1024 * 1024

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
         * LIKE pattern matching everything below [scope]. Built in Kotlin instead of concatenating
         * `|| '/%'` in SQL, because the root scope would otherwise become `//%` and match nothing.
         */
        internal fun descendantPatternStatic(scope: String): String =
            if (scope == "/") "/%" else escapeLikePatternStatic(scope) + "/%"

        /**
         * Build the dynamic SQL for the multi-scope path filter. Each scope contributes 2 bind
         * placeholders (exact + descendant). All scopes joined with OR. A single SQL query keeps
         * ORDER BY + LIMIT global across scopes — no client-side union, no bind-arg explosion via
         * `IN (:ids)`. Exposed at companion level for unit testing.
         *
         * The exact placeholder is bound to the RAW scope: it's an equality comparison, so binding
         * the LIKE-escaped value there would make a scope containing `%`, `_` or `\` never match.
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
                "(s.path = ? OR s.path LIKE ? ESCAPE '\\')"
            }
            val sql = """
                SELECT id FROM operation_history
                WHERE outcome IN ($outcomesPh)
                  AND kind IN ($kindsPh)
                  AND EXISTS (
                    SELECT 1 FROM operation_history_scope s
                    WHERE s.operationHistoryId = operation_history.id
                      AND ($scopePredicate)
                  )
                ORDER BY completedAt DESC
                LIMIT ?
            """.trimIndent()
            val args = buildList<Any> {
                addAll(outcomes)
                addAll(kinds)
                for (scope in pathScopes) {
                    add(scope)
                    add(descendantPatternStatic(scope))
                }
                add(limit)
            }
            return SimpleSQLiteQuery(sql, args.toTypedArray())
        }
    }
}
