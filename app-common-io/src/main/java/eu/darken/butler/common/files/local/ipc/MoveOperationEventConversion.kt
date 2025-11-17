package eu.darken.butler.common.files.local.ipc

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.MoveAction
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.files.metadata.FileType
import eu.darken.butler.common.io.R
import eu.darken.butler.common.progress.Progress
import kotlin.time.Instant

/**
 * Conversion utilities between domain types (MoveAction.State) and IPC types (MoveOperationEvent).
 */

/**
 * Convert from domain MoveAction.State to IPC MoveOperationEvent.
 * Used on host side before streaming events to client.
 */
fun MoveAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>.toMoveOperationEvent(): MoveOperationEvent {
    return when (this) {
        is MoveAction.State.Active -> {
            // Determine phase based on secondaryProgress presence
            if (secondaryProgress == null) {
                // Scan phase (no secondaryProgress)
                MoveOperationEvent.ScanProgress(
                    scannedCount = primaryProgress.count.max,
                    scannedBytes = totalBytes,
                    currentPath = currentSource,
                )
            } else {
                // Move phase (has secondaryProgress)
                MoveOperationEvent.MoveProgress(
                    movedCount = primaryProgress.count.current,
                    totalCount = primaryProgress.count.max,
                    movedBytes = movedBytes,
                    totalBytes = totalBytes,
                    currentSource = currentSource,
                    currentDestination = currentDestination,
                    currentFileSize = currentFileSize,
                    currentFileBytes = currentFileBytes,
                )
            }
        }

        is MoveAction.State.Completed -> MoveOperationEvent.Result(
            movedItems = movedFiles.map { (src, dst) -> PathPair(src.lookedUp, dst.lookedUp) },
            skippedItems = skippedFiles.toList(),
            errorCount = 0,  // Not tracked in domain Result
            movedBytes = bytesMoved,
        )
    }
}

/**
 * Convert from IPC MoveOperationEvent to domain MoveAction.State.
 * Used on client side when receiving events from host.
 */
fun MoveOperationEvent.toMoveActionState(): MoveAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup> {
    return when (this) {
        is MoveOperationEvent.ScanProgress -> {
            // Reconstruct scan progress state
            MoveAction.State.Active(
                currentSource = currentPath,
                currentDestination = null,
                primaryProgress = Progress.Data(
                    primary = R.string.general_scan_progress_title.toCaString(),
                    secondary = currentPath.userReadablePath,
                    count = Progress.Count.Counter(
                        current = 0,
                        max = scannedCount,
                    ),
                ),
                secondaryProgress = null,
                totalBytes = scannedBytes,
            )
        }

        is MoveOperationEvent.MoveProgress -> {
            // Reconstruct move progress state
            MoveAction.State.Active(
                currentSource = currentSource,
                currentDestination = currentDestination,
                primaryProgress = Progress.Data(
                    primary = R.string.general_move_progress_title.toCaString(),
                    secondary = currentSource.userReadablePath,
                    count = Progress.Count.Counter(
                        current = movedCount,
                        max = totalCount,
                    ),
                ),
                secondaryProgress = Progress.Data(
                    primary = currentSource.lookedUp.name.toCaString(),
                    count = Progress.Count.Size(
                        current = currentFileBytes,
                        max = currentFileSize,
                    ),
                ),
                movedBytes = movedBytes,
                totalBytes = totalBytes,
                currentFileSize = currentFileSize,
                currentFileBytes = currentFileBytes,
            )
        }

        is MoveOperationEvent.Result -> {
            // Reconstruct result state
            // Note: Client side receives paths in PathPair but domain expects lookups.
            // We convert to minimal lookups using just the path information.
            // Full metadata is not available on client side post-operation.
            MoveAction.State.Completed(
                movedFiles = movedItems.map { pair ->
                    // Create minimal lookups from paths (client doesn't have full metadata)
                    val srcLookup = LocalPathLookup(
                        lookedUp = pair.source,
                        fileType = FileType.FILE,
                        size = 0L,
                        modifiedAt = Instant.DISTANT_PAST,
                    )
                    val dstLookup = LocalPathLookup(
                        lookedUp = pair.destination,
                        fileType = FileType.FILE,
                        size = 0L,
                        modifiedAt = Instant.DISTANT_PAST,
                    )
                    srcLookup to dstLookup
                }.toSet(),
                skippedFiles = skippedItems.toSet(),
                bytesMoved = movedBytes,
            )
        }

        is MoveOperationEvent.Error -> {
            // Error events are not converted to State - they should throw
            throw IllegalStateException("Cannot convert Error event to MoveAction.State: $error")
        }
    }
}
