package eu.darken.butler.common.trash

import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.Existence
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.extensions.lookup
import eu.darken.butler.common.trash.db.TrashDao
import eu.darken.butler.common.trash.db.TrashDatabase
import eu.darken.butler.common.trash.db.TrashEntity
import kotlinx.coroutines.CancellationException
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
class TrashRepo @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val gatewaySwitch: GatewaySwitch,
    private val database: TrashDatabase,
) {

    private val dao: TrashDao
        get() = database.trashDao()

    init {
        appScope.launch(dispatcherProvider.IO) {
            try {
                syncWithFileSystem()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, ERROR) { "Initial sync failed: ${e.asLog()}" }
            }
        }
    }

    fun getAllItems(): Flow<List<TrashItem>> = dao.getAll()
        .map { entities ->
            entities.map { entity -> entity.toDomainModel() }
        }
        .flowOn(dispatcherProvider.IO)


    suspend fun getById(id: Uuid): TrashItem? = withContext(dispatcherProvider.IO) {
        dao.getById(id)?.toDomainModel()
    }

    suspend fun getOlderThan(cutoffTime: Instant): List<TrashItem> = withContext(dispatcherProvider.IO) {
        dao.getOlderThan(cutoffTime).map { it.toDomainModel() }
    }

    suspend fun getItemCount(): Int = withContext(dispatcherProvider.IO) {
        dao.getItemCount()
    }

    suspend fun getTotalSize(): Long = withContext(dispatcherProvider.IO) {
        dao.getTotalSize() ?: 0L
    }

    suspend fun insert(item: TrashItem) = withContext(dispatcherProvider.IO) {
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
        log(TAG, INFO) { "Syncing trash database with file system" }

        val allItems = dao.getAll().first()
        var removedCount = 0

        for (entity in allItems) {
            try {
                // A row is only dropped for a definitive "not there". An unmounted volume or a dead
                // document provider answers UNKNOWN for every row on it, and deleting those would
                // orphan the trashed files with no way back.
                when (gatewaySwitch.existsStrict(entity.trashPath)) {
                    Existence.ABSENT -> {
                        dao.delete(entity.id)
                        removedCount++
                        log(TAG, DEBUG) { "Removed non-existent item from database: ${entity.id}" }
                    }

                    Existence.PRESENT -> {}
                    Existence.UNKNOWN -> log(TAG, WARN) {
                        "Could not verify ${entity.trashPath}, keeping ${entity.id}"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(TAG, WARN) { "Error checking existence of ${entity.id}: ${e.asLog()}" }
            }
        }

        log(TAG, INFO) { "Sync complete. Removed $removedCount non-existent items." }
    }

    private fun TrashItem.toEntity(): TrashEntity = TrashEntity(
        id = id,
        originalPath = originalLookup.lookedUp,
        originalLookup = originalLookup,
        trashPath = trashPath,
        deletedAt = deletedAt,
        size = size,
    )

    private suspend fun TrashEntity.toDomainModel(): TrashItem {
        val trashLookup = try {
            this.trashPath.lookup(gatewaySwitch, LookupOptions.BASE)
        } catch (e: Exception) {
            log(TAG, WARN) { "trashLookup failed on ${this.trashPath}: $e" }
            null
        }

        return TrashItem(
            id = this.id,
            deletedAt = this.deletedAt,
            originalLookup = this.originalLookup,
            trashPath = this.trashPath,
            trashLookup = trashLookup,
            size = this.size,
        )
    }

    data class TrashItem(
        val id: Uuid,
        val originalLookup: APathLookup<*>,
        val trashPath: APath<*>,
        val trashLookup: APathLookup<*>?,
        val deletedAt: Instant = Clock.System.now(),
        val size: Long,
    ) {
        val originalPath: APath<*> get() = originalLookup.lookedUp
    }

    companion object {
        private val TAG = logTag("Trash", "Repo")
    }
}
