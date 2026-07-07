package eu.darken.butler.workspace.core.operations

import eu.darken.butler.common.ca.CaString
import eu.darken.butler.common.ca.caString
import eu.darken.butler.common.files.local.operations.core.PerformanceHistory
import eu.darken.butler.common.formatByteSpeed
import eu.darken.butler.common.formatItemSpeed
import eu.darken.butler.common.getQuantityString2
import eu.darken.butler.workspace.R
import kotlin.time.Clock
import kotlin.time.Instant

data class TransferProgressMetrics(
    val overall: CaString?,
    val currentFile: CaString?,
    val overallBytesSpeed: Long,
    val overallItemsSpeed: Double,
    val overallEta: Long?,
    val fileSpeed: Long,
    val fileEta: Long?,
)

/**
 * Shared speed/ETA metrics for byte-transfer operations (Explorer copy/move, Saver save).
 *
 * The two flags preserve pre-extraction differences between the call sites rather than express a
 * design intent — normalizing them would change visible progress text:
 * - [truncateItemSpeed]: copy/move historically truncated items/sec to whole units (and hid the
 *   item-speed segment below 1 item/sec), while save shows fractional speeds.
 * - [requireTotalBytesForEta]: copy/move suppress the overall ETA while the total size is still
 *   unknown (scan phase); save computes it from whatever the tracker reports.
 */
fun buildTransferProgressMetrics(
    performanceHistory: PerformanceHistory?,
    totalBytes: Long,
    processedBytes: Long,
    currentFileSize: Long,
    currentFileBytes: Long,
    currentFileStartTime: Instant?,
    truncateItemSpeed: Boolean,
    requireTotalBytesForEta: Boolean,
    now: Instant = Clock.System.now(),
): TransferProgressMetrics {
    val overallBytesSpeed = performanceHistory?.getRecentBytesPerSecond() ?: 0L
    val rawItemsSpeed = performanceHistory?.getRecentItemsPerSecond() ?: 0f
    val overallItemsSpeed = if (truncateItemSpeed) rawItemsSpeed.toLong().toDouble() else rawItemsSpeed.toDouble()

    val overallEta = if (overallBytesSpeed > 0 && (!requireTotalBytesForEta || totalBytes > 0)) {
        (totalBytes - processedBytes) / overallBytesSpeed // seconds
    } else null

    val (fileSpeed, fileEta) = if (currentFileStartTime != null && currentFileSize > 0) {
        val fileElapsed = (now - currentFileStartTime).inWholeMilliseconds / 1000.0
        if (fileElapsed > 0) {
            val speed = (currentFileBytes / fileElapsed).toLong()
            val remaining = currentFileSize - currentFileBytes
            val eta = if (speed > 0) remaining / speed else null
            speed to eta
        } else 0L to null
    } else 0L to null

    val overall = if (overallBytesSpeed > 0) {
        caString { ctx ->
            val bytesSpeedPart = formatByteSpeed(ctx, overallBytesSpeed)

            val itemsSpeedPart = if (overallItemsSpeed > 0) {
                " • " + formatItemSpeed(ctx, overallItemsSpeed)
            } else ""

            val etaPart = if (overallEta != null) {
                val duration = ctx.getQuantityString2(
                    eu.darken.butler.common.R.plurals.common_duration_seconds_full,
                    overallEta.toInt(),
                    overallEta
                )
                " • " + ctx.getString(R.string.workspace_operation_progress_time_remaining, duration)
            } else ""

            bytesSpeedPart + itemsSpeedPart + etaPart
        }
    } else null

    val currentFile = if (fileSpeed > 0) {
        caString { ctx ->
            val speedPart = formatByteSpeed(ctx, fileSpeed)
            val etaPart = if (fileEta != null) {
                val duration = ctx.getQuantityString2(
                    eu.darken.butler.common.R.plurals.common_duration_seconds_full,
                    fileEta.toInt(),
                    fileEta
                )
                " • " + ctx.getString(R.string.workspace_operation_progress_time_remaining, duration)
            } else ""
            speedPart + etaPart
        }
    } else null

    return TransferProgressMetrics(
        overall = overall,
        currentFile = currentFile,
        overallBytesSpeed = overallBytesSpeed,
        overallItemsSpeed = overallItemsSpeed,
        overallEta = overallEta,
        fileSpeed = fileSpeed,
        fileEta = fileEta,
    )
}
