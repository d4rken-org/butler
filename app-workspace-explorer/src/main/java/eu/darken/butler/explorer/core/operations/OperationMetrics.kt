package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import kotlin.time.Duration

data class OperationMetrics(
    val duration: Duration,
    val affectedPaths: Collection<APath>,
    val bytesProcessed: Long? = null,
    val averageBytesPerSecond: Long? = null,
    val peakBytesPerSecond: Long? = null,
)