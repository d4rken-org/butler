package eu.darken.butler.workspace.core.operations.history.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query(
        """
        SELECT id FROM operation_history
        WHERE outcome IN (:outcomes)
          AND kind IN (:kinds)
          AND EXISTS (
            SELECT 1 FROM operation_history_paths p
            WHERE p.operationHistoryId = operation_history.id
              AND (p.path = :pathScope OR p.path LIKE :pathScope || '/%' ESCAPE '\')
          )
        ORDER BY completedAt DESC
        LIMIT :limit
        """
    )
    fun observeIdsWithPathScope(
        outcomes: Collection<String>,
        kinds: Collection<String>,
        pathScope: String,
        limit: Int,
    ): Flow<List<String>>

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
