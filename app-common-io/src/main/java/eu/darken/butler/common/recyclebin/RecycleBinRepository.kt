package eu.darken.butler.common.recyclebin

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
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
import kotlin.time.Instant

private val tag = logTag("RecycleBin", "Repository")

@Singleton
class RecycleBinRepository @Inject constructor(
    private val database: RecycleBinDatabase,
    private val gatewaySwitch: GatewaySwitch,
    private val dispatcherProvider: DispatcherProvider,
    @AppScope private val appScope: CoroutineScope,
) {
    private val dao: RecycleBinDao
        get() = database.recycleBinDao()

    data class RecycleBinItem(
        val id: String,
        val originalPath: APath<*>,
        val recycleBinPath: APath<*>,
        val deletedAt: Instant,
        val size: Long,
        val isAvailable: Boolean = true,
    )

    fun getAllItems(): Flow<List<RecycleBinItem>> = dao.getAll()
        .map { entities ->
            entities.mapNotNull { entity ->
                entity.toDomainModel().also {
                    if (it == null) {
                        log(tag, WARN) { "Failed to convert entity to domain model: ${entity.id}" }
                    }
                }
            }
        }
        .map { items ->
            items.map { item ->
                item.withAvailabilityCheck()
            }
        }
        .flowOn(dispatcherProvider.IO)


    suspend fun getById(id: String): RecycleBinItem? = withContext(dispatcherProvider.IO) {
        dao.getById(id)?.toDomainModel()
    }

    suspend fun getOlderThan(cutoffTime: Instant): List<RecycleBinItem> = withContext(dispatcherProvider.IO) {
        dao.getOlderThan(cutoffTime).mapNotNull { it.toDomainModel() }
    }

    suspend fun getItemCount(): Int = withContext(dispatcherProvider.IO) {
        dao.getItemCount()
    }

    suspend fun getTotalSize(): Long = withContext(dispatcherProvider.IO) {
        dao.getTotalSize() ?: 0L
    }

    suspend fun delete(items: List<RecycleBinItem>) = withContext(dispatcherProvider.IO) {
        val entities = items.mapNotNull { item ->
            try {
                RecycleBinEntity(
                    id = item.id,
                    originalPath = item.originalPath.toString(),
                    recycleBinPath = item.recycleBinPath.toString(),
                    deletedAt = item.deletedAt,
                    size = item.size,
                )
            } catch (e: Exception) {
                log(tag, ERROR) { "Failed to convert item to entity: ${e.asLog()}" }
                null
            }
        }
        dao.deleteAll(entities)
    }

    suspend fun deleteById(id: String) = withContext(dispatcherProvider.IO) {
        dao.deleteById(id)
    }

    suspend fun deleteAll() = withContext(dispatcherProvider.IO) {
        dao.deleteAll()
    }

    suspend fun syncWithFileSystem() = withContext(dispatcherProvider.IO) {
        log(tag, INFO) { "Syncing recycle bin database with file system" }

        val allItems = dao.getAll().first()
        var removedCount = 0

        for (entity in allItems) {
            try {
                val recycleBinPath = parseAPath(entity.recycleBinPath)
                if (recycleBinPath != null && !gatewaySwitch.exists(recycleBinPath)) {
                    // File no longer exists in recycle bin, remove from database
                    dao.delete(entity)
                    removedCount++
                    log(tag, DEBUG) { "Removed non-existent item from database: ${entity.id}" }
                }
            } catch (e: Exception) {
                log(tag, WARN) { "Error checking existence of ${entity.id}: ${e.asLog()}" }
            }
        }

        log(tag, INFO) { "Sync complete. Removed $removedCount non-existent items." }
    }

    private fun RecycleBinEntity.toDomainModel(): RecycleBinItem? {
        return try {
            val originalPath = parseAPath(this.originalPath) ?: return null
            val recycleBinPath = parseAPath(this.recycleBinPath) ?: return null

            RecycleBinItem(
                id = this.id,
                originalPath = originalPath,
                recycleBinPath = recycleBinPath,
                deletedAt = this.deletedAt,
                size = this.size,
                isAvailable = true,
            )
        } catch (e: Exception) {
            log(tag, ERROR) { "Error converting entity to domain model: ${e.asLog()}" }
            null
        }
    }

    private suspend fun RecycleBinItem.withAvailabilityCheck(): RecycleBinItem {
        return try {
            val exists = gatewaySwitch.exists(recycleBinPath)
            copy(isAvailable = exists)
        } catch (e: Exception) {
            log(tag, WARN) { "Error checking availability for item $id: ${e.asLog()}" }
            this
        }
    }

    private fun parseAPath(pathString: String): APath<*>? {
        return try {
            // For now, we only support LocalPath
            // In the future, we can add JSON deserialization for polymorphic paths
            LocalPath.build(pathString)
        } catch (e: Exception) {
            log(tag, ERROR) { "Failed to parse path: $pathString - ${e.asLog()}" }
            null
        }
    }

    init {
        // Schedule periodic sync
        appScope.launch(dispatcherProvider.IO) {
            try {
                syncWithFileSystem()
            } catch (e: Exception) {
                log(tag, ERROR) { "Initial sync failed: ${e.asLog()}" }
            }
        }
    }
}