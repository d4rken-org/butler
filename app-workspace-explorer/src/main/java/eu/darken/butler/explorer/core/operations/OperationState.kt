package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.common.progress.Progress
import eu.darken.butler.explorer.core.errors.ConflictResolution
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed class OperationState {
    abstract val operationId: OperationId
    abstract val startTime: Instant
    
    data class OnGoing(
        override val operationId: OperationId,
        override val startTime: Instant,
        val progress: Progress.Data,
        val currentItem: APath? = null,
        val processedCount: Int = 0,
        val totalCount: Int? = null,
        val bytesProcessed: Long = 0L,
        val totalBytes: Long? = null,
        val currentSpeed: Long? = null, // bytes per second
        val estimatedTimeRemaining: Duration? = null,
        val canCancel: Boolean = true,
    ) : OperationState()
    
    data class AwaitingInput(
        override val operationId: OperationId,
        override val startTime: Instant,
        val conflict: ConflictInfo,
        val conflictId: String,
        val previousProgress: Progress.Data? = null,
        val timeout: Duration? = null,
    ) : OperationState()
    
    data class Completed(
        override val operationId: OperationId,
        override val startTime: Instant,
        val result: OperationResult,
        val endTime: Instant,
    ) : OperationState() {
        val duration: Duration = endTime - startTime
    }
}

data class ConflictInfo(
    val type: ConflictType,
    val sourcePath: APath,
    val targetPath: APath? = null,
    val sourceSize: Long? = null,
    val targetSize: Long? = null,
    val sourceModified: Instant? = null,
    val targetModified: Instant? = null,
    val message: String? = null,
    val canSkip: Boolean = true,
    val canOverwrite: Boolean = true,
    val canMerge: Boolean = false,
    val canRename: Boolean = true,
    val suggestedName: String? = null,
)

enum class ConflictType {
    FILE_EXISTS,
    DIRECTORY_EXISTS,
    PERMISSION_DENIED,
    INSUFFICIENT_SPACE,
    NAME_CONFLICT,
    LOCKED_FILE,
}

data class ConflictStrategy(
    val defaultResolution: ConflictResolution? = null,
    val applyToAll: Boolean = false,
    val applyToType: ConflictType? = null,
) {
    companion object {
        val ASK = ConflictStrategy(defaultResolution = null)
        val SKIP_ALL = ConflictStrategy(ConflictResolution.Skip(applyToAll = true), applyToAll = true)
        val OVERWRITE_ALL = ConflictStrategy(ConflictResolution.Overwrite(applyToAll = true), applyToAll = true)
    }
}