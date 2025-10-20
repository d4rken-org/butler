package eu.darken.butler.common.files.local.ipc

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.CopyAction
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.io.R
import eu.darken.butler.common.progress.Progress

/**
 * Conversion utilities between domain types (CopyAction.State) and IPC types (CopyOperationEvent).
 */

/**
 * Convert from domain CopyAction.State to IPC CopyOperationEvent.
 * Used on host side before streaming events to client.
 */
fun CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup>.toCopyOperationEvent(): CopyOperationEvent {
    return when (this) {
        is CopyAction.State.Active -> {
            // Determine phase based on secondaryProgress presence
            if (secondaryProgress == null) {
                // Scan phase (no secondaryProgress)
                CopyOperationEvent.ScanProgress(
                    scannedCount = primaryProgress.count.max,
                    scannedBytes = totalBytes,
                    currentPath = currentSource,
                )
            } else {
                // Copy phase (has secondaryProgress)
                CopyOperationEvent.CopyProgress(
                    copiedCount = primaryProgress.count.current,
                    totalCount = primaryProgress.count.max,
                    copiedBytes = copiedBytes,
                    totalBytes = totalBytes,
                    currentSource = currentSource,
                    currentDestination = currentDestination,
                    currentFileSize = currentFileSize,
                    currentFileBytes = currentFileBytes,
                )
            }
        }

        is CopyAction.State.Completed -> CopyOperationEvent.Result(
            copiedItems = copied.map { (src, dst) -> PathPair(src.lookedUp, dst.lookedUp) },
            skippedItems = skipped.toList(),
            errorCount = 0,  // Not tracked in domain Result
            copiedBytes = copiedBytes,
        )
    }
}

/**
 * Convert from IPC CopyOperationEvent to domain CopyAction.State.
 * Used on client side when receiving events from host.
 */
fun CopyOperationEvent.toCopyActionState(): CopyAction.State<LocalPath, LocalPathLookup, LocalPath, LocalPathLookup> {
    return when (this) {
        is CopyOperationEvent.ScanProgress -> {
            // Reconstruct scan progress state
            CopyAction.State.Active(
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

        is CopyOperationEvent.CopyProgress -> {
            // Reconstruct copy progress state
            CopyAction.State.Active(
                currentSource = currentSource,
                currentDestination = currentDestination,
                primaryProgress = Progress.Data(
                    primary = R.string.general_copy_progress_title.toCaString(),
                    secondary = currentSource.userReadablePath,
                    count = Progress.Count.Counter(
                        current = copiedCount,
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
                copiedBytes = copiedBytes,
                totalBytes = totalBytes,
                currentFileSize = currentFileSize,
                currentFileBytes = currentFileBytes,
            )
        }

        is CopyOperationEvent.Result -> {
            // Reconstruct result state
            // Note: Client side receives paths in PathPair but domain expects lookups.
            // We convert to minimal lookups using just the path information.
            // Full metadata is not available on client side post-operation.
            CopyAction.State.Completed(
                copied = copiedItems.map { pair ->
                    // Create minimal lookups from paths (client doesn't have full metadata)
                    val srcLookup = LocalPathLookup(
                        lookedUp = pair.source,
                        fileType = eu.darken.butler.common.files.metadata.FileType.FILE,
                        size = 0L,
                        modifiedAt = kotlin.time.Instant.DISTANT_PAST,
                    )
                    val dstLookup = LocalPathLookup(
                        lookedUp = pair.destination,
                        fileType = eu.darken.butler.common.files.metadata.FileType.FILE,
                        size = 0L,
                        modifiedAt = kotlin.time.Instant.DISTANT_PAST,
                    )
                    srcLookup to dstLookup
                }.toSet(),
                skipped = skippedItems.toSet(),
                copiedBytes = copiedBytes,
            )
        }

        is CopyOperationEvent.Error -> {
            // Error events are not converted to State - they should throw
            throw IllegalStateException("Cannot convert Error event to CopyAction.State: $error")
        }
    }
}
