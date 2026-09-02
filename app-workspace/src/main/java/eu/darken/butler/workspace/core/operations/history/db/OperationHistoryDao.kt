package eu.darken.butler.workspace.core.operations.history.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationHistoryDao {

    // ─── inserts (atomic + retention) ─────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: OperationHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPaths(paths: List<OperationHistoryPathEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScopePaths(paths: List<OperationHistoryScopeEntity>)

    /**
     * Atomic write: insert one operation row + all its path and scope rows + enforce the retention
     * cap. If [maxItems] is exceeded, deletes the oldest by `completedAt`.
     */
    @Transaction
    suspend fun insertWithPathsAndTrim(
        entry: OperationHistoryEntity,
        paths: List<OperationHistoryPathEntity>,
        scopePaths: List<OperationHistoryScopeEntity>,
        maxItems: Int,
    ) {
        insertEntry(entry)
        if (paths.isNotEmpty()) insertPaths(paths)
        if (scopePaths.isNotEmpty()) insertScopePaths(scopePaths)
        val count = getCount()
        if (count > maxItems) deleteOldest(count - maxItems)
    }

    // ─── two-phase filtered query ─────────────────────────────────────────────────

    /**
     * Phase 1: fetch matching operation IDs sorted newest-first with a proper LIMIT
     * (so we don't accidentally LIMIT joined path rows). [outcomes] and [kinds] are enum
     * names; pass the FULL set when "no filter" is wanted (empty IN clauses are invalid SQL).
     * [pathScope] is a fully-escaped (`%`, `_`, `\` → `\%`, `\_`, `\\`) directory path.
     */
    @Query(
        """
        SELECT id FROM operation_history
        WHERE outcome IN (:outcomes)
          AND kind IN (:kinds)
        ORDER BY completedAt DESC
        LIMIT :limit
        """
    )
    fun observeIds(
        outcomes: Collection<String>,
        kinds: Collection<String>,
        limit: Int,
    ): Flow<List<String>>

    /**
     * Phase 1 with dynamic OR-joined path scopes, matched against the scope index. The repo builds
     * the full SQL via [androidx.sqlite.db.SimpleSQLiteQuery] and binds N path scopes (each
     * contributing two placeholders for exact/descendant). Single query so ORDER BY + LIMIT apply
     * globally across all scopes — no client-side union, no bind-arg explosion.
     *
     * Observed entities ensure the Flow re-emits when any of the tables change.
     */
    @RawQuery(
        observedEntities = [
            OperationHistoryEntity::class,
            OperationHistoryPathEntity::class,
            OperationHistoryScopeEntity::class,
        ],
    )
    fun observeIdsRaw(query: SupportSQLiteQuery): Flow<List<String>>

    /** Phase 2: load the full operations + their paths for the given IDs, ordered newest-first. */
    @Transaction
    @Query("SELECT * FROM operation_history WHERE id IN (:ids) ORDER BY completedAt DESC")
    fun loadByIds(ids: List<String>): Flow<List<OperationHistoryWithPaths>>

    @Transaction
    @Query("SELECT * FROM operation_history WHERE id = :id")
    suspend fun getById(id: String): OperationHistoryWithPaths?

    /**
     * Emits null until the row exists. Needed because an operation is persisted asynchronously
     * after it completes, so a UI that navigates to an operation's entry can arrive before the
     * write does.
     */
    @Transaction
    @Query("SELECT * FROM operation_history WHERE id = :id")
    fun observeById(id: String): Flow<OperationHistoryWithPaths?>

    /**
     * On-demand load of one operation's scope index, bounded by [limit]. Deliberately not a
     * `@Relation` on [OperationHistoryWithPaths]: that projection powers the whole history list (up
     * to 2000 rows), so attaching the index would materialize hundreds of thousands of rows per list
     * emission. The limit is a display bound only - the table itself stays uncapped so filtering
     * still sees every path.
     */
    @Query(
        """
        SELECT * FROM operation_history_scope
        WHERE operationHistoryId = :operationHistoryId
        ORDER BY sortIndex ASC
        LIMIT :limit
        """
    )
    suspend fun getScopePathsPreview(operationHistoryId: String, limit: Int): List<OperationHistoryScopeEntity>

    @Query("SELECT COUNT(*) FROM operation_history_scope WHERE operationHistoryId = :operationHistoryId")
    suspend fun getScopePathCount(operationHistoryId: String): Int

    // ─── retention ─────────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM operation_history")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM operation_history")
    fun observeCount(): Flow<Int>

    /**
     * Delete the [count] oldest entries by completedAt. Cascades to paths via FK.
     */
    @Query(
        """
        DELETE FROM operation_history
        WHERE id IN (SELECT id FROM operation_history ORDER BY completedAt ASC LIMIT :count)
        """
    )
    suspend fun deleteOldest(count: Int)

    @Transaction
    suspend fun trimToMax(maxItems: Int) {
        val count = getCount()
        if (count > maxItems) deleteOldest(count - maxItems)
    }

    @Query("DELETE FROM operation_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM operation_history")
    suspend fun deleteAll()
}
