package eu.darken.butler.explorer.core.sorting.rules.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderSortRuleDao {

    /** The candidates for one folder: its own key plus every ancestor key, resolved by the caller. */
    @Query("SELECT * FROM folder_sort_rules WHERE pathKey IN (:keys)")
    fun observeForKeys(keys: List<String>): Flow<List<FolderSortRuleEntity>>

    @Query("SELECT * FROM folder_sort_rules ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<FolderSortRuleEntity>>

    @Query("SELECT COUNT(*) FROM folder_sort_rules")
    fun observeCount(): Flow<Int>

    @Upsert
    suspend fun upsert(rule: FolderSortRuleEntity)

    @Query("DELETE FROM folder_sort_rules WHERE pathKey = :pathKey")
    suspend fun delete(pathKey: String)

    @Query("DELETE FROM folder_sort_rules")
    suspend fun deleteAll()
}
