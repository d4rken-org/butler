package eu.darken.butler.common.trash

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
class TrashManager @Inject constructor(
    private val repository: TrashRepo,
    private val storageEnv: StorageEnvironment,
    private val gatewaySwitch: GatewaySwitch,
    private val settings: TrashSettings,
    private val dispatcherProvider: DispatcherProvider,
) {
    data class TrashMoveReport(
        val movedToTrash: Set<APathLookup<*>>,
        val failedToMove: Set<APathLookup<*>>,
        val bytesMoved: Long,
        val trashPath: APath<*>? = null,
        val canUndo: Boolean = true,
    )

    data class TrashRestoreReport(
        val restored: Set<APath<*>>,
        val failed: Set<APath<*>>,
        val conflicts: Set<APath<*>>,
    )

    data class TrashStats(
        val totalItems: Int,
        val totalSize: Long,
        val oldestItem: Instant?,
    )

    val isEnabled: Flow<Boolean> = settings.enabled.flow

    suspend fun moveToTrash(
        paths: List<APath<*>>,
    ): TrashMoveReport = withContext(dispatcherProvider.IO) {
        log(TAG, INFO) { "Moving ${paths.size} items to trash" }

        // Filter supported path types (LocalPath only for now)
        val (supported, unsupported) = paths.partition { it is LocalPath }

        if (unsupported.isNotEmpty()) {
            log(TAG, WARN) { "${unsupported.size} paths are not supported for trash" }
        }

        val movedItems = mutableSetOf<APathLookup<*>>()
        val failedItems = mutableSetOf<APathLookup<*>>()
        var totalBytes = 0L

        for (path in supported) {
            try {
                val localPath = path

                // Look up the item to get full metadata
                val lookup = gatewaySwitch.lookup(localPath, LookupOptions.MAX)
                if (lookup.fileType == FileType.UNKNOWN) {
                    log(TAG, WARN) { "Path does not exist: $localPath" }
                    failedItems.add(lookup)
                    continue
                }

                // Get trash path for this item
                val trashPath = getTrashPath(localPath)

                // Ensure trash directory exists
                ensureTrashDirectory(trashPath.parent!!)

                // Move the file/directory
                val moveState = localPath.move(
                    gateway = gatewaySwitch,
                    destination = trashPath,
                ).last()

                if (moveState is MoveAction.State.Completed<*, *, *, *>) {
                    // Record in repository with full lookup data
                    val item = TrashRepo.TrashItem(
                        id = Uuid.random(),
                        originalLookup = lookup,
                        trashPath = trashPath,
                        trashLookup = moveState.movedFiles.first().second,
                        size = lookup.size!!,
                    )

                    repository.insert(item)

                    movedItems.add(lookup)
                    totalBytes += lookup.size ?: 0L

                    log(TAG, DEBUG) { "Successfully moved to trash: $localPath -> $trashPath" }
                } else {
                    failedItems.add(lookup)
                    log(TAG, ERROR) { "Failed to move to trash: $localPath" }
                }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Error moving $path to trash: ${e.asLog()}" }
                val lookup = gatewaySwitch.lookup(path, LookupOptions(fallbackToUnknown = true))
                failedItems.add(lookup)
            }
        }

        // Add unsupported items to failed list
        unsupported.forEach {
            val lookup = gatewaySwitch.lookup(it, LookupOptions(fallbackToUnknown = true))
            failedItems.add(lookup)
        }

        val trashRoot = if (supported.isNotEmpty()) {
            getTrashRoot(supported.first())
        } else null

        return@withContext TrashMoveReport(
            movedToTrash = movedItems,
            failedToMove = failedItems,
            bytesMoved = totalBytes,
            trashPath = trashRoot,
        )
    }

    suspend fun restore(
        items: List<TrashRepo.TrashItem>,
    ): TrashRestoreReport = withContext(dispatcherProvider.IO) {
        log(TAG, INFO) { "Restoring ${items.size} items from recycle bin" }

        val restoredItems = mutableSetOf<APath<*>>()
        val failedItems = mutableSetOf<APath<*>>()
        val conflicts = mutableSetOf<APath<*>>()

        for (item in items) {
            try {
                // TODO
                val trashLookup = item.trashLookup

                if (trashLookup == null) {
                    failedItems.add(item.trashPath)
                    log(TAG, ERROR) { "Failed to restore: $item (no trash path)" }
                    continue
                }

                val originalPath = item.originalPath

                // Check if original path already exists
                val existingLookup = gatewaySwitch.lookup(originalPath, LookupOptions(fallbackToUnknown = true))
                if (existingLookup.fileType != FileType.UNKNOWN) {
                    log(TAG, WARN) { "Original path already exists: $originalPath" }
                    conflicts.add(originalPath)
                    continue
                }

                // Restore the file
                val restoreState = trashLookup.lookedUp.move(
                    gateway = gatewaySwitch,
                    destination = originalPath,
                ).last()

                // TODO: Restore ownership/permissions from item.originalLookup if possible

                if (restoreState is MoveAction.State.Completed<*, *, *, *>) {
                    // Remove from repository
                    repository.deleteById(item.id)
                    restoredItems.add(originalPath)
                    log(TAG, DEBUG) { "Successfully restored: $trashLookup -> $originalPath" }
                } else {
                    failedItems.add(item.trashPath)
                    log(TAG, ERROR) { "Failed to restore: $item" }
                }
            } catch (e: Exception) {
                log(TAG, ERROR) { "Error restoring item ${item.id}: ${e.asLog()}" }
                failedItems.add(item.trashPath)
            }
        }

        return@withContext TrashRestoreReport(
            restored = restoredItems,
            failed = failedItems,
            conflicts = conflicts,
        )
    }

    suspend fun deletePermanently(
        items: List<TrashRepo.TrashItem>,
    ): Int = withContext(dispatcherProvider.IO) {
        log(TAG, INFO) { "Permanently deleting ${items.size} items from recycle bin" }

        var deletedCount = 0
        for (item in items) {
            val trashPath = item.trashPath

            val deleteState = try {
                // Delete the actual file/directory
                trashPath.delete(
                    gateway = gatewaySwitch,
                    options = DeleteAction.Options(recursive = true),
                ).last()
            } catch (e: Exception) {
                log(TAG, ERROR) { "Error permanently deleting item ${item.id}: ${e.asLog()}" }
                continue
            }

            if (deleteState is DeleteAction.State.Completed<*, *>) {
                // Remove from repository
                repository.deleteById(item.id)
                deletedCount++

                log(TAG, DEBUG) { "Permanently deleted: $trashPath" }
            }

        }

        return@withContext deletedCount
    }

    suspend fun emptyTrash(): Int = withContext(dispatcherProvider.IO) {
        log(TAG, INFO) { "Emptying trash" }
        val items = repository.getAllItems().first()
        return@withContext deletePermanently(items)
    }

    /**
     * Restores a specific item from within a trashed folder to its original location.
     * The parent trash item remains in trash.
     */
    suspend fun restoreNested(
        parentItem: TrashRepo.TrashItem,
        relativePath: String,
    ): TrashRestoreReport = withContext(dispatcherProvider.IO) {
        log(TAG, INFO) { "Restoring nested item: '$relativePath' from ${parentItem.id}" }

        val sourcePath = parentItem.trashPath.child(relativePath)
        val destinationPath = parentItem.originalPath.child(relativePath)

        val restoredItems = mutableSetOf<APath<*>>()
        val failedItems = mutableSetOf<APath<*>>()
        val conflicts = mutableSetOf<APath<*>>()

        try {
            // Check if destination already exists
            val destLookup = gatewaySwitch.lookup(destinationPath, LookupOptions(fallbackToUnknown = true))
            if (destLookup.fileType != FileType.UNKNOWN) {
                log(TAG, WARN) { "Destination already exists: $destinationPath" }
                conflicts.add(destinationPath)
                return@withContext TrashRestoreReport(
                    restored = restoredItems,
                    failed = failedItems,
                    conflicts = conflicts,
                )
            }

            // Ensure parent directories exist
            val parentDir = destinationPath.parent
            if (parentDir != null && !gatewaySwitch.exists(parentDir)) {
                gatewaySwitch.createDir(parentDir)
                log(TAG, DEBUG) { "Created parent directory: $parentDir" }
            }

            // Move the item
            val moveState = sourcePath.move(
                gateway = gatewaySwitch,
                destination = destinationPath,
            ).last()

            if (moveState is MoveAction.State.Completed<*, *, *, *>) {
                restoredItems.add(destinationPath)
                log(TAG, DEBUG) { "Successfully restored: $sourcePath -> $destinationPath" }
            } else {
                failedItems.add(sourcePath)
                log(TAG, ERROR) { "Failed to restore: $sourcePath" }
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Error restoring nested item: ${e.asLog()}" }
            failedItems.add(sourcePath)
        }

        // Note: We do NOT remove from database - parent item still exists
        // Database cleanup happens when entire trashed folder is restored/deleted

        return@withContext TrashRestoreReport(
            restored = restoredItems,
            failed = failedItems,
            conflicts = conflicts,
        )
    }

    /**
     * Permanently deletes a specific item from within a trashed folder.
     * The parent trash item remains in trash.
     */
    suspend fun deleteNestedPermanently(
        parentItem: TrashRepo.TrashItem,
        relativePath: String,
    ): Int = withContext(dispatcherProvider.IO) {
        log(TAG, INFO) { "Permanently deleting nested item: '$relativePath' from ${parentItem.id}" }

        val targetPath = parentItem.trashPath.child(relativePath)

        try {
            val deleteState = targetPath.delete(
                gateway = gatewaySwitch,
                options = DeleteAction.Options(recursive = true),
            ).last()

            if (deleteState is DeleteAction.State.Completed<*, *>) {
                log(TAG, DEBUG) { "Permanently deleted: $targetPath" }
                return@withContext 1
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Error deleting nested item: ${e.asLog()}" }
        }

        return@withContext 0
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

    fun getStats(): Flow<TrashStats> = repository.getAllItems()
        .map { items ->
            TrashStats(
                totalItems = items.size,
                totalSize = items.sumOf { it.originalLookup.size ?: 0L },
                oldestItem = items.minByOrNull { it.deletedAt }?.deletedAt,
            )
        }

    private suspend fun getTrashRoot(path: APath<*>): APath<*> {
        // Find the appropriate cache directory for this storage
        val cacheDir = storageEnv.ourPublicDirs.firstOrNull { cache ->
            // Check if path is on the same storage volume
            path.path.startsWith(cache.parent!!.parent!!.path)
        } ?: storageEnv.ourPublicDirs.first()

        return cacheDir.child(".trash")
    }

    private suspend fun getTrashPath(originalPath: APath<*>): APath<*> {
        val trashRoot = getTrashRoot(originalPath)

        // Generate unique filename to avoid collisions
        val hash = originalPath.path.hashCode().toString(16).take(8)
        val safeName = "${originalPath.name}_$hash"

        return trashRoot.child(safeName)
    }

    private suspend fun ensureTrashDirectory(dir: APath<*>) {
        try {
            if (!gatewaySwitch.exists(dir)) {
                gatewaySwitch.createDir(dir)
                log(TAG, DEBUG) { "Created trash directory: $dir" }
            }
        } catch (e: Exception) {
            log(TAG, ERROR) { "Failed to create trash directory: ${e.asLog()}" }
            throw e
        }
    }

    companion object {
        private val TAG = logTag("Trash", "Manager")
    }
}
