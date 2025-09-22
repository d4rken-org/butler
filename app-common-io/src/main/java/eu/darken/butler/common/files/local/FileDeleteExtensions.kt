package eu.darken.butler.common.files.local

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File

private val TAG = logTag("Files", "Delete", "Extensions")

/**
 * Result of a batch delete operation
 */
data class DeleteResult(
    val deletedFiles: Set<File>,
    val totalSize: Long
)

/**
 * Deletes a collection of files and directories efficiently.
 *
 * @param recursive If true, deletes directories recursively. If false, only empty directories can be deleted.
 * @param onProgress Callback called for each file being deleted with the file and its size.
 * @return DeleteResult containing the set of successfully deleted files and total size deleted.
 */
suspend fun Collection<File>.delete(
    recursive: Boolean = true,
    onProgress: (File, Long) -> Unit = { _, _ -> }
): DeleteResult {
    if (isEmpty()) {
        log(TAG, DEBUG) { "delete(): Empty collection, nothing to delete" }
        return DeleteResult(emptySet(), 0L)
    }

    log(TAG, DEBUG) { "delete(): Deleting ${size} targets (recursive=$recursive)" }

    val deletedFiles = mutableSetOf<File>()
    var totalSize = 0L

    // Process each target in the collection
    for (target in this) {
        if (!currentCoroutineContext().isActive) break

        try {
            val result = target.deleteTarget(recursive, onProgress)
            deletedFiles.addAll(result.deletedFiles)
            totalSize += result.totalSize
        } catch (e: Exception) {
            log(TAG, WARN) { "delete(): Failed to delete $target: ${e.message}" }
            // Continue with other files even if one fails
        }
    }

    log(TAG, DEBUG) { "delete(): Successfully deleted ${deletedFiles.size} files, total size: $totalSize bytes" }
    return DeleteResult(deletedFiles, totalSize)
}

/**
 * Deletes a single file or directory target.
 */
private suspend fun File.deleteTarget(
    recursive: Boolean,
    onProgress: (File, Long) -> Unit
): DeleteResult {
    val deletedFiles = mutableSetOf<File>()
    var totalSize = 0L

    if (!exists()) {
        log(TAG, DEBUG) { "deleteTarget(): File doesn't exist, skipping: $this" }
        return DeleteResult(deletedFiles, totalSize)
    }

    when {
        isFile -> {
            val size = length()
            if (delete()) {
                deletedFiles.add(this)
                totalSize += size
                onProgress(this, size)
                log(TAG, VERBOSE) { "deleteTarget(): Deleted file: $this ($size bytes)" }
            } else {
                log(TAG, WARN) { "deleteTarget(): Failed to delete file: $this" }
            }
        }

        isDirectory -> {
            if (recursive) {
                // Use walkBottomUp to delete children before parents
                val filesToDelete = walkBottomUp().toList()

                for (file in filesToDelete) {
                    if (!currentCoroutineContext().isActive) break

                    if (!file.exists()) continue // Already deleted or never existed

                    val size = if (file.isFile) file.length() else 0L

                    if (file.delete()) {
                        deletedFiles.add(file)
                        totalSize += size
                        onProgress(file, size)
                        log(TAG, VERBOSE) { "deleteTarget(): Deleted: $file" }
                    } else {
                        log(TAG, WARN) { "deleteTarget(): Failed to delete: $file" }
                    }
                }
            } else {
                // Non-recursive: only delete if directory is empty
                val contents = listFiles()
                if (contents == null) {
                    log(TAG, WARN) { "deleteTarget(): Cannot list directory contents: $this" }
                } else if (contents.isEmpty()) {
                    if (delete()) {
                        deletedFiles.add(this)
                        onProgress(this, 0L)
                        log(TAG, VERBOSE) { "deleteTarget(): Deleted empty directory: $this" }
                    } else {
                        log(TAG, WARN) { "deleteTarget(): Failed to delete empty directory: $this" }
                    }
                } else {
                    log(TAG, DEBUG) { "deleteTarget(): Skipping non-empty directory (recursive=false): $this" }
                }
            }
        }

        else -> {
            log(TAG, DEBUG) { "deleteTarget(): Unknown file type, attempting to delete: $this" }
            val size = if (canRead()) length() else 0L
            if (delete()) {
                deletedFiles.add(this)
                totalSize += size
                onProgress(this, size)
                log(TAG, VERBOSE) { "deleteTarget(): Deleted unknown file type: $this" }
            } else {
                log(TAG, WARN) { "deleteTarget(): Failed to delete unknown file type: $this" }
            }
        }
    }

    return DeleteResult(deletedFiles, totalSize)
}