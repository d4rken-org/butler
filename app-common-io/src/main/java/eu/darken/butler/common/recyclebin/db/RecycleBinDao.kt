package eu.darken.butler.common.recyclebin.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Dao
interface RecycleBinDao {

    @Query("SELECT * FROM recycle_bin_items ORDER BY deletedAt DESC")
    fun getAll(): Flow<List<RecycleBinEntity>>

    @Query("SELECT * FROM recycle_bin_items WHERE id = :id")
    suspend fun getById(id: Uuid): RecycleBinEntity?

    @Query("SELECT * FROM recycle_bin_items WHERE deletedAt < :cutoffTime")
    suspend fun getOlderThan(cutoffTime: Instant): List<RecycleBinEntity>

    @Query("SELECT COUNT(*) FROM recycle_bin_items")
    suspend fun getItemCount(): Int

    @Query("SELECT SUM(size) FROM recycle_bin_items")
    suspend fun getTotalSize(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecycleBinEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<RecycleBinEntity>)

    @Update
    suspend fun update(entity: RecycleBinEntity)

    @Delete
    suspend fun deleteAll(entities: List<RecycleBinEntity>)

    @Query("DELETE FROM recycle_bin_items WHERE id = :id")
    suspend fun delete(id: Uuid)

    @Query("DELETE FROM recycle_bin_items WHERE id IN (:ids)")
    suspend fun delete(ids: List<Uuid>)

    @Query("DELETE FROM recycle_bin_items")
    suspend fun deleteAll()
}