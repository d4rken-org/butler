package eu.darken.butler.explorer.core.operations

import kotlin.time.Duration

data class OperationMetrics(
    val filesProcessed: Int = 0,
    val directoriesProcessed: Int = 0,
    val bytesProcessed: Long = 0L,
    val filesSkipped: Int = 0,
    val filesFailed: Int = 0,
    val totalDuration: Duration = Duration.ZERO,
    val averageSpeed: Long? = null, // bytes per second
    val peakSpeed: Long? = null, // bytes per second
) {
    val totalItemsProcessed: Int = filesProcessed + directoriesProcessed
    val successRate: Float = if (totalItemsProcessed > 0) {
        (totalItemsProcessed - filesFailed).toFloat() / totalItemsProcessed
    } else {
        0f
    }
    
    fun withAddedFile(bytes: Long = 0L): OperationMetrics = copy(
        filesProcessed = filesProcessed + 1,
        bytesProcessed = bytesProcessed + bytes,
    )
    
    fun withAddedDirectory(): OperationMetrics = copy(
        directoriesProcessed = directoriesProcessed + 1,
    )
    
    fun withSkippedFile(): OperationMetrics = copy(
        filesSkipped = filesSkipped + 1,
    )
    
    fun withFailedFile(): OperationMetrics = copy(
        filesFailed = filesFailed + 1,
    )
    
    fun withRemovedFile(bytes: Long = 0L): OperationMetrics = copy(
        filesProcessed = filesProcessed + 1,
        bytesProcessed = bytesProcessed + bytes,
    )
    
    fun withError(): OperationMetrics = copy(
        filesFailed = filesFailed + 1,
    )
    
    fun withUpdatedSpeed(currentSpeed: Long): OperationMetrics = copy(
        averageSpeed = if (averageSpeed == null) {
            currentSpeed
        } else {
            (averageSpeed + currentSpeed) / 2
        },
        peakSpeed = if (peakSpeed == null || currentSpeed > peakSpeed) {
            currentSpeed
        } else {
            peakSpeed
        },
    )
}