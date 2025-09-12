package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import kotlin.time.Instant

/**
 * Hints about operations that are about to happen or have just happened.
 * Used to provide optimistic UI updates before file system changes are confirmed.
 */
sealed class OperationHint {
    abstract val targetPath: APath
    abstract val timestamp: Instant
    abstract val operationId: OperationId
    
    /**
     * Files have been or will be added to a directory.
     */
    data class FilesAdded(
        override val targetPath: APath,
        val files: List<APath>,
        override val operationId: OperationId,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : OperationHint()
    
    /**
     * Files have been or will be removed from a directory.
     */
    data class FilesRemoved(
        override val targetPath: APath,
        val files: List<APath>,
        override val operationId: OperationId,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : OperationHint()
    
    /**
     * Files have been or will be moved from one directory to another.
     */
    data class FilesMoved(
        override val targetPath: APath,
        val sourcePath: APath,
        val files: List<APath>,
        override val operationId: OperationId,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : OperationHint() {
        /**
         * Convenience method to get the removal hint for the source directory.
         */
        fun asRemovalHint(): FilesRemoved = FilesRemoved(
            targetPath = sourcePath,
            files = files,
            operationId = operationId,
            timestamp = timestamp
        )
        
        /**
         * Convenience method to get the addition hint for the target directory.
         */
        fun asAdditionHint(): FilesAdded = FilesAdded(
            targetPath = targetPath,
            files = files,
            operationId = operationId,
            timestamp = timestamp
        )
    }
    
    /**
     * A file has been or will be renamed.
     */
    data class FileRenamed(
        override val targetPath: APath,
        val oldName: String,
        val newName: String,
        override val operationId: OperationId,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : OperationHint()
    
    /**
     * Files have been or will be modified.
     */
    data class FilesModified(
        override val targetPath: APath,
        val files: List<APath>,
        override val operationId: OperationId,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : OperationHint()
    
    /**
     * A directory needs to be refreshed completely.
     */
    data class RefreshRequired(
        override val targetPath: APath,
        val reason: String,
        override val operationId: OperationId,
        override val timestamp: Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
    ) : OperationHint()
}