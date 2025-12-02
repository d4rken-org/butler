package eu.darken.butler.common.trash.db

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
interface TrashDao {

    @Query("SELECT * FROM trash_items ORDER BY deletedAt DESC")
    fun getAll(): Flow<List<TrashEntity>>

    @Query("SELECT * FROM trash_items WHERE id = :id")
    suspend fun getById(id: Uuid): TrashEntity?

    @Query("SELECT * FROM trash_items WHERE deletedAt < :cutoffTime")
    suspend fun getOlderThan(cutoffTime: Instant): List<TrashEntity>

    @Query("SELECT COUNT(*) FROM trash_items")
    suspend fun getItemCount(): Int

    @Query("SELECT SUM(size) FROM trash_items")
    suspend fun getTotalSize(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TrashEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TrashEntity>)

    @Update
    suspend fun update(entity: TrashEntity)

    @Delete
    suspend fun deleteAll(entities: List<TrashEntity>)

    @Query("DELETE FROM trash_items WHERE id = :id")
    suspend fun delete(id: Uuid)

    @Query("DELETE FROM trash_items WHERE id IN (:ids)")
    suspend fun delete(ids: List<Uuid>)

    @Query("DELETE FROM trash_items")
    suspend fun deleteAll()
}