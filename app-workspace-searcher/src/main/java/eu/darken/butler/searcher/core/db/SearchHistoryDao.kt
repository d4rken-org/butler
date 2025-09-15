package eu.darken.butler.searcher.core.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {
    
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC")
    fun getAllSearches(): Flow<List<SearchHistoryEntity>>
    
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun getRecentSearches(limit: Int = 50): Flow<List<SearchHistoryEntity>>
    
    @Query("SELECT * FROM search_history WHERE id = :id")
    suspend fun getById(id: String): SearchHistoryEntity?
    
    @Insert
    suspend fun insert(item: SearchHistoryEntity): Long
    
    @Update
    suspend fun update(item: SearchHistoryEntity)
    
    @Query("UPDATE search_history SET resultCount = :count WHERE id = :id")
    suspend fun updateResultCount(id: String, count: Int)
    
    @Delete
    suspend fun delete(item: SearchHistoryEntity)
    
    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("DELETE FROM search_history")
    suspend fun deleteAll()
    
    @Query("SELECT COUNT(*) FROM search_history")
    suspend fun getCount(): Int
    
    @Query("DELETE FROM search_history WHERE id IN (SELECT id FROM search_history ORDER BY searchedAt ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)

    @Query("SELECT * FROM search_history WHERE baseQuery = :query ORDER BY searchedAt DESC LIMIT 1")
    suspend fun getLatestByQuery(query: String): SearchHistoryEntity?

    @Query("UPDATE search_history SET searchedAt = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: String, timestamp: kotlin.time.Instant)
}