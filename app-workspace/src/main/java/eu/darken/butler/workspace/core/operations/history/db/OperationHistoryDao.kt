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

    /**
     * Atomic write: insert one operation row + all its path rows + enforce the retention cap.
     * If [maxItems] is exceeded, deletes the oldest by `completedAt`.
     */
    @Transaction
    suspend fun insertWithPathsAndTrim(
        entry: OperationHistoryEntity,
        paths: List<OperationHistoryPathEntity>,
        maxItems: Int,
    ) {
        insertEntry(entry)
        if (paths.isNotEmpty()) insertPaths(paths)
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
     * Phase 1 with dynamic OR-joined path scopes. The repo builds the full SQL via
     * [androidx.sqlite.db.SimpleSQLiteQuery] and binds N path scopes (each contributing four
     * placeholders for path/previousPath × exact/descendant). Single query so ORDER BY + LIMIT
     * apply globally across all scopes — no client-side union, no bind-arg explosion.
     *
     * Observed entities ensure the Flow re-emits when either table changes.
     */
    @RawQuery(
        observedEntities = [OperationHistoryEntity::class, OperationHistoryPathEntity::class],
    )
    fun observeIdsRaw(query: SupportSQLiteQuery): Flow<List<String>>

    /** Phase 2: load the full operations + their paths for the given IDs, ordered newest-first. */
    @Transaction
    @Query("SELECT * FROM operation_history WHERE id IN (:ids) ORDER BY completedAt DESC")
    fun loadByIds(ids: List<String>): Flow<List<OperationHistoryWithPaths>>

    @Transaction
    @Query("SELECT * FROM operation_history WHERE id = :id")
    suspend fun getById(id: String): OperationHistoryWithPaths?

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
