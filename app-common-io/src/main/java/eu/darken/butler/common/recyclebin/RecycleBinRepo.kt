package eu.darken.butler.common.recyclebin

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.BuildConfigWrap
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.common.files.room.APathConverter
import eu.darken.butler.common.files.room.APathLookupConverter
import eu.darken.butler.common.recyclebin.db.RecycleBinDao
import eu.darken.butler.common.recyclebin.db.RecycleBinDatabase
import eu.darken.butler.common.recyclebin.db.RecycleBinEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Singleton
class RecycleBinRepo @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val gatewaySwitch: GatewaySwitch,
    private val aPathConverter: APathConverter,
    private val aPathLookupConverter: APathLookupConverter,
) {

    private val database by lazy {
        Room.databaseBuilder(
            context,
            RecycleBinDatabase::class.java,
            "recycle_bin.db"
        ).apply {
            if (BuildConfigWrap.DEBUG) {
                log(TAG) { "Debug mode: Enabling destructive migration for recycle bin database" }
                fallbackToDestructiveMigration()
            }
//        addTypeConverter(InstantConverter())
//        addTypeConverter(UuidConverter())
            addTypeConverter(aPathConverter)
            addTypeConverter(aPathLookupConverter)
        }.build()
    }

    private val dao: RecycleBinDao
        get() = database.recycleBinDao()

    init {
        appScope.launch(dispatcherProvider.IO) {
            try {
                syncWithFileSystem()
            } catch (e: Exception) {
                log(TAG, ERROR) { "Initial sync failed: ${e.asLog()}" }
            }
        }
    }

    fun getAllItems(): Flow<List<RecycleBinItem>> = dao.getAll()
        .map { entities ->
            entities.map { entity -> entity.toDomainModel() }
        }
        .flowOn(dispatcherProvider.IO)


    suspend fun getById(id: Uuid): RecycleBinItem? = withContext(dispatcherProvider.IO) {
        dao.getById(id)?.toDomainModel()
    }

    suspend fun getOlderThan(cutoffTime: Instant): List<RecycleBinItem> = withContext(dispatcherProvider.IO) {
        dao.getOlderThan(cutoffTime).map { it.toDomainModel() }
    }

    suspend fun getItemCount(): Int = withContext(dispatcherProvider.IO) {
        dao.getItemCount()
    }

    suspend fun getTotalSize(): Long = withContext(dispatcherProvider.IO) {
        dao.getTotalSize() ?: 0L
    }

    suspend fun insert(item: RecycleBinItem) = withContext(dispatcherProvider.IO) {
        val entity = item.toEntity()
        dao.insert(entity)
    }

    suspend fun deleteById(id: Uuid) = withContext(dispatcherProvider.IO) {
        dao.delete(id)
    }

    suspend fun delete(items: List<Uuid>) = withContext(dispatcherProvider.IO) {
        dao.delete(items)
    }

    suspend fun deleteAll() = withContext(dispatcherProvider.IO) {
        dao.deleteAll()
    }

    suspend fun syncWithFileSystem() = withContext(dispatcherProvider.IO) {
        log(TAG, INFO) { "Syncing recycle bin database with file system" }

        val allItems = dao.getAll().first()
        var removedCount = 0

        for (entity in allItems) {
            try {
                if (!gatewaySwitch.exists(entity.recycleBinPath)) {
                    // File no longer exists in recycle bin, remove from database
                    dao.delete(entity.id)
                    removedCount++
                    log(TAG, DEBUG) { "Removed non-existent item from database: ${entity.id}" }
                }
            } catch (e: Exception) {
                log(TAG, WARN) { "Error checking existence of ${entity.id}: ${e.asLog()}" }
            }
        }

        log(TAG, INFO) { "Sync complete. Removed $removedCount non-existent items." }
    }

    private fun RecycleBinItem.toEntity(): RecycleBinEntity = RecycleBinEntity(
        id = id,
        originalPath = originalLookup.lookedUp,
        originalLookup = originalLookup,
        recycleBinPath = recycleBinPath,
        deletedAt = deletedAt,
        size = size,
    )

    private suspend fun RecycleBinEntity.toDomainModel(): RecycleBinItem {
        val recycleBinLookup = try {
            this.recycleBinPath.lookup(gatewaySwitch, LookupOptions.BASE)
        } catch (e: Exception) {
            log(TAG, WARN) { "recycleBinLookup failed on ${this.recycleBinPath}: $e" }
            null
        }

        return RecycleBinItem(
            id = this.id,
            deletedAt = this.deletedAt,
            originalLookup = this.originalLookup,
            recycleBinPath = this.recycleBinPath,
            recycleBinLookup = recycleBinLookup,
            size = this.size,
        )
    }

    data class RecycleBinItem(
        val id: Uuid,
        val originalLookup: APathLookup<*>,
        val recycleBinPath: APath<*>,
        val recycleBinLookup: APathLookup<*>?,
        val deletedAt: Instant = Clock.System.now(),
        val size: Long,
    ) {
        val originalPath: APath<*> get() = originalLookup.lookedUp
    }

    companion object {
        private val TAG = logTag("RecycleBin", "Repo")
    }
}
