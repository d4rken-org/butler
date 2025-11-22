package eu.darken.butler.common.recyclebin

import eu.darken.butler.common.coroutine.DispatcherProvider
import eu.darken.butler.common.datastore.value
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.common.files.GatewaySwitch
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.LookupOptions
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.extensions.delete
import eu.darken.butler.common.files.extensions.move
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.storage.StorageEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Singleton
class RecycleBinManager @Inject constructor(
    private val repository: RecycleBinRepo,
    private val storageEnv: StorageEnvironment,
    private val gatewaySwitch: GatewaySwitch,
    private val settings: RecycleBinSettings,
    private val dispatcherProvider: DispatcherProvider,
) {
    data class RecycleBinMoveReport(
        val movedToRecycleBin: Set<APathLookup<*>>,
        val failedToMove: Set<APathLookup<*>>,
        val bytesMoved: Long,
        val recycleBinPath: APath<*>? = null,
        val canUndo: Boolean = true,
    )

    data class RecycleBinRestoreReport(
        val restored: Set<APathLookup<*>>,
        val failed: Set<APathLookup<*>>,
        val conflicts: Set<APathLookup<*>>,
    )

    data class RecycleBinStats(
        val totalItems: Int,
        val totalSize: Long,
        val oldestItem: Instant?,
    )

    val isEnabled: Flow<Boolean> = settings.enabled.flow

    suspend fun moveToRecycleBin(
        paths: List<APath<*>>,
    ): RecycleBinMoveReport = withContext(dispatcherProvider.IO) {
        log(TAG, INFO) { "Moving ${paths.size} items to recycle bin" }

        // Filter supported path types (LocalPath only for now)
        val (supported, unsupported) = paths.partition { it is LocalPath }

        if (unsupported.isNotEmpty()) {
            log(TAG, WARN) { "${unsupported.size} paths are not supported for recycle bin" }
        }

        val movedItems = mutableSetOf<APathLookup<*>>()
        val failedItems = mutableSetOf<APathLookup<*>>()
        var totalBytes = 0L

        for (path in supported) {
            try {
                val localPath = path as LocalPath

                // Look up the item to get full metadata
                val lookup = gatewaySwitch.lookup(localPath, LookupOptions.MAX)
                if (lookup.fileType == FileType.UNKNOWN) {
                    log(TAG, WARN) { "Path does not exist: $localPath" }
                    failedItems.add(lookup)
                    continue
                }

                // Get recycle bin path for this item
                val recycleBinPath = getRecycleBinPath(localPath)

                // Ensure recycle bin directory exists
                ensureRecycleBinDirectory(recycleBinPath.parent!!)

                // Move the file/directory
                val moveState = localPath.move(
                    gateway = gatewaySwitch,
                    destination = recycleBinPath,
                ).last()

                if (moveState is MoveAction.State.Completed<*, *, *, *>) {
                    // Record in repository with full lookup data
                    val item = RecycleBinRepo.RecycleBinItem(
                        id = Uuid.random(),
                        originalLookup = lookup,
                        recycleBinPath = recycleBinPath,
                        recycleBinLookup = moveState.movedFiles.first().second,
                        size = lookup.size!!,
                    )

                    repository.insert(item)

                    movedItems.add(lookup)
                    totalBytes += lookup.size ?: 0L

                    log(TAG, DEBUG) { "Successfully moved to recycle bin: $localPath -> $recycleBinPath" }
                } else {
                    failedItems.add(lookup)
                    log(TAG, ERROR) { "Failed to move to recycle bin: $localPath" }
                }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Error moving $path to recycle bin: ${e.asLog()}" }
                val lookup = gatewaySwitch.lookup(path, LookupOptions(fallbackToUnknown = true))
                failedItems.add(lookup)
            }
        }

        // Add unsupported items to failed list
        unsupported.forEach {
            val lookup = gatewaySwitch.lookup(it, LookupOptions(fallbackToUnknown = true))
            failedItems.add(lookup)
        }

        val recycleBinRoot = if (supported.isNotEmpty()) {
            getRecycleBinRoot(supported.first() as LocalPath)
        } else null

        return@withContext RecycleBinMoveReport(
            movedToRecycleBin = movedItems,
            failedToMove = failedItems,
            bytesMoved = totalBytes,
            recycleBinPath = recycleBinRoot,
        )
    }

    suspend fun restore(
        items: List<RecycleBinRepo.RecycleBinItem>,
    ): RecycleBinRestoreReport = withContext(dispatcherProvider.IO) {
        log(TAG, INFO) { "Restoring ${items.size} items from recycle bin" }

        val restoredItems = mutableSetOf<APathLookup<*>>()
        val failedItems = mutableSetOf<APathLookup<*>>()
        val conflicts = mutableSetOf<APathLookup<*>>()

        for (item in items) {
            try {
                // TODO
                val recycleBinPath = item.recycleBinLookup as LocalPath
                val originalPath = item.originalPath as LocalPath

                // Check if original path already exists
                val existingLookup = gatewaySwitch.lookup(originalPath, LookupOptions(fallbackToUnknown = true))
                if (existingLookup.fileType != FileType.UNKNOWN) {
                    log(TAG, WARN) { "Original path already exists: $originalPath" }
                    conflicts.add(existingLookup)
                    continue
                }

                // Restore the file
                val restoreState = recycleBinPath.move(
                    gateway = gatewaySwitch,
                    destination = originalPath,
                ).last()

                if (restoreState is MoveAction.State.Completed<*, *, *, *>) {
                    // Remove from repository
                    repository.deleteById(item.id)

                    // TODO: Restore ownership/permissions from item.originalLookup if possible
                    val lookup = gatewaySwitch.lookup(originalPath, LookupOptions(fetchSize = true))
                    restoredItems.add(lookup)

                    log(TAG, DEBUG) { "Successfully restored: $recycleBinPath -> $originalPath" }
                } else {
                    val lookup = gatewaySwitch.lookup(recycleBinPath, LookupOptions(fallbackToUnknown = true))
                    failedItems.add(lookup)
                    log(TAG, ERROR) { "Failed to restore: $recycleBinPath" }
                }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Error restoring item ${item.id}: ${e.asLog()}" }
                try {
                    val path = item.recycleBinLookup as LocalPath
                    val lookup = gatewaySwitch.lookup(path, LookupOptions(fallbackToUnknown = true))
                    failedItems.add(lookup)
                } catch (e2: Exception) {
                    log(TAG, ERROR) { "Failed to create lookup for error item: ${e2.asLog()}" }
                }
            }
        }

        return@withContext RecycleBinRestoreReport(
            restored = restoredItems,
            failed = failedItems,
            conflicts = conflicts,
        )
    }

    suspend fun deletePermanently(
        items: List<RecycleBinRepo.RecycleBinItem>,
    ): Int = withContext(dispatcherProvider.IO) {
        log(TAG, INFO) { "Permanently deleting ${items.size} items from recycle bin" }

        var deletedCount = 0
        for (item in items) {
            try {
                val recycleBinPath = item.recycleBinLookup as LocalPath

                // Delete the actual file/directory
                val deleteState = recycleBinPath.delete(
                    gateway = gatewaySwitch,
                    options = DeleteAction.Options(),
                ).last()

                if (deleteState is DeleteAction.State.Completed<*, *>) {
                    // Remove from repository
                    repository.deleteById(item.id)
                    deletedCount++

                    log(TAG, DEBUG) { "Permanently deleted: $recycleBinPath" }
                }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Error permanently deleting item ${item.id}: ${e.asLog()}" }
            }
        }

        return@withContext deletedCount
    }

    suspend fun emptyRecycleBin(): Int = withContext(dispatcherProvider.IO) {
        log(TAG, INFO) { "Emptying recycle bin" }
        val items = repository.getAllItems().first()
        return@withContext deletePermanently(items)
    }

    suspend fun cleanupExpired(): Int = withContext(dispatcherProvider.IO) {
        val retention = settings.expiresAfter.value()
        val cutoffTime = Clock.System.now() - retention

        log(TAG, INFO) { "Cleaning up items older than $retention (before $cutoffTime)" }

        val expiredItems = repository.getOlderThan(cutoffTime)

        if (expiredItems.isEmpty()) {
            log(TAG, DEBUG) { "No expired items to clean up" }
            return@withContext 0
        }

        return@withContext deletePermanently(expiredItems)
    }

    fun getStats(): Flow<RecycleBinStats> = repository.getAllItems()
        .map { items ->
            RecycleBinStats(
                totalItems = items.size,
                totalSize = items.sumOf { it.originalLookup.size ?: 0L },
                oldestItem = items.minByOrNull { it.deletedAt }?.deletedAt,
            )
        }

    private suspend fun getRecycleBinRoot(path: APath<*>): APath<*> {
        // Find the appropriate cache directory for this storage
        val cacheDir = storageEnv.ourPublicDirs.firstOrNull { cache ->
            // Check if path is on the same storage volume
            path.path.startsWith(cache.parent!!.parent!!.path)
        } ?: storageEnv.ourPublicDirs.first()

        return cacheDir.child(".recyclebin")
    }

    private suspend fun getRecycleBinPath(originalPath: APath<*>): APath<*> {
        val recycleBinRoot = getRecycleBinRoot(originalPath)

        // Generate unique filename to avoid collisions
        val hash = originalPath.path.hashCode().toString(16).take(8)
        val safeName = "${originalPath.name}_$hash"

        return recycleBinRoot.child(safeName)
    }

    private suspend fun ensureRecycleBinDirectory(dir: APath<*>) {
        try {
            if (!gatewaySwitch.exists(dir)) {
                gatewaySwitch.createDir(dir)
                log(TAG, DEBUG) { "Created recycle bin directory: $dir" }
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to create recycle bin directory: ${e.asLog()}" }
            throw e
        }
    }

    companion object {
        private val TAG = logTag("RecycleBin", "Manager")
    }
}
