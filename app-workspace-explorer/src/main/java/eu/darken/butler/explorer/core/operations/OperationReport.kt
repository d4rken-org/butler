package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.explorer.core.filesystem.FileSystemEvent
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.Operation.Report.*
import kotlin.time.Clock
import kotlin.time.Instant

data class OperationReport(
    override val affectedPaths: Collection<PathChange>,
    val affectedFiles: Int? = null,
    val affectedDirectories: Int? = null,
    val bytesProcessed: Long? = null,
    val averageBytesPerSecond: Long? = null,
    val peakBytesPerSecond: Long? = null,
) : Operation.Report {

    override val summary: CaString = caString {
        "// TODO: Summary"
    }

    class Builder(
        private val startTime: Instant = Clock.System.now()
    ) {
        private val affectedPaths = mutableListOf<PathChange>()

        fun addPathEvent(event: FileSystemEvent) {
            affectedPaths.addAll(
                when (event) {
                    is FileSystemEvent.Added -> event.paths.map { PathChange(it, PathChange.Type.ADDED) }
                    is FileSystemEvent.Modified -> event.paths.map { PathChange(it, PathChange.Type.MODIFIED) }
                    is FileSystemEvent.Removed -> event.paths.map { PathChange(it, PathChange.Type.REMOVED) }
                }
            )
        }

        private var bytesProcessed: Long? = null
        private var peakBytesPerSecond: Long? = null

        // Speed tracking state
        private var lastUpdateTime: Instant? = null
        private var lastBytesProcessed: Long = 0L
        private var totalWeightedSpeed: Long = 0L  // Sum of (speed * duration)

        private var totalDuration: Long = 0L       // Sum of all durations

        fun updateBytesProcessed(
            bytesProcessed: Long,
            currentTime: Instant = Clock.System.now()
        ): SpeedInfo {
            this.bytesProcessed = bytesProcessed

            val lastTime = lastUpdateTime
            return if (lastTime != null) {
                val timeDelta = (currentTime - lastTime).inWholeMilliseconds
                if (timeDelta >= 100) { // Update every 100ms minimum
                    val bytesDelta = bytesProcessed - lastBytesProcessed
                    val currentSpeed = if (timeDelta > 0) {
                        (bytesDelta * 1000) / timeDelta
                    } else 0L

                    // Time-weighted averaging
                    totalWeightedSpeed += currentSpeed * timeDelta
                    totalDuration += timeDelta
                    val weightedAverage = if (totalDuration > 0) totalWeightedSpeed / totalDuration else 0L

                    if (currentSpeed > (peakBytesPerSecond ?: 0L)) {
                        peakBytesPerSecond = currentSpeed
                    }

                    lastUpdateTime = currentTime
                    lastBytesProcessed = bytesProcessed

                    SpeedInfo(
                        current = currentSpeed,
                        average = weightedAverage,
                        peak = peakBytesPerSecond ?: 0L
                    )
                } else {
                    SpeedInfo()
                }
            } else {
                // First update
                lastUpdateTime = currentTime
                lastBytesProcessed = bytesProcessed
                SpeedInfo()
            }
        }

        fun build(): OperationReport {
            val finalAverageSpeed = bytesProcessed?.let { totalBytes ->
                val totalTime = (Clock.System.now() - startTime).inWholeSeconds
                if (totalTime > 0) totalBytes / totalTime else null
            }

            return OperationReport(
                affectedPaths = affectedPaths.distinct(),
                bytesProcessed = bytesProcessed,
                averageBytesPerSecond = finalAverageSpeed,
                peakBytesPerSecond = peakBytesPerSecond,
            )
        }
    }

    data class SpeedInfo(
        val current: Long = 0,
        val average: Long = 0,
        val peak: Long = 0,
    )
}