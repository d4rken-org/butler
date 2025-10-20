package eu.darken.butler.common.files.local.ipc

import eu.darken.butler.common.ca.toCaString
import eu.darken.butler.common.files.LocalPath
import eu.darken.butler.common.files.actions.DeleteAction
import eu.darken.butler.common.files.local.LocalPathLookup
import eu.darken.butler.common.io.R
import eu.darken.butler.common.progress.Progress

/**
 * Conversion utilities between domain types (DeleteAction.State) and IPC types (DeleteOperationEvent).
 */

/**
 * Convert from domain DeleteAction.State to IPC DeleteOperationEvent.
 * Used on host side before streaming events to client.
 */
fun DeleteAction.State<LocalPath, LocalPathLookup>.toDeleteOperationEvent(): DeleteOperationEvent {
    return when (this) {
        is DeleteAction.State.Active -> {
            // Determine phase based on secondaryProgress presence
            if (secondaryProgress == null) {
                // Scan phase (no secondaryProgress)
                DeleteOperationEvent.ScanProgress(
                    scannedCount = primaryProgress.count.max,
                    currentPath = target,
                )
            } else {
                // Delete phase (has secondaryProgress)
                DeleteOperationEvent.DeleteProgress(
                    deletedCount = primaryProgress.count.current,
                    totalCount = primaryProgress.count.max,
                    currentPath = target,
                    currentSize = deletedBytes,
                    totalSize = totalBytes,
                )
            }
        }

        is DeleteAction.State.Completed -> DeleteOperationEvent.Result(
            deletedItems = deleted.toList(),
            skippedItems = skipped.toList(),
            errorCount = 0, // Not tracked in domain Result
        )
    }
}

/**
 * Convert from IPC DeleteOperationEvent to domain DeleteAction.State.
 * Used on client side when receiving events from host.
 */
fun DeleteOperationEvent.toDeleteActionState(): DeleteAction.State<LocalPath, LocalPathLookup> {
    return when (this) {
        is DeleteOperationEvent.ScanProgress -> {
            // Reconstruct scan progress state
            DeleteAction.State.Active(
                target = currentPath,
                primaryProgress = Progress.Data(
                    primary = R.string.general_scan_progress_title.toCaString(),
                    secondary = currentPath.userReadablePath,
                    count = Progress.Count.Counter(
                        current = 0,
                        max = scannedCount,
                    ),
                ),
                secondaryProgress = null,
                deletedBytes = 0,
                totalBytes = 0,
                currentItemStartTime = null,
            )
        }

        is DeleteOperationEvent.DeleteProgress -> {
            // Reconstruct delete progress state
            DeleteAction.State.Active(
                target = currentPath,
                primaryProgress = Progress.Data(
                    primary = R.string.general_delete_progress_title.toCaString(),
                    secondary = currentPath.userReadablePath,
                    count = Progress.Count.Counter(
                        current = deletedCount,
                        max = totalCount,
                    ),
                ),
                secondaryProgress = Progress.Data(
                    primary = currentPath.lookedUp.name.toCaString(),
                    count = Progress.Count.Size(
                        current = currentSize,
                        max = totalSize,
                    ),
                ),
                deletedBytes = currentSize,
                totalBytes = totalSize,
                currentItemStartTime = null,
            )
        }

        is DeleteOperationEvent.Result -> {
            // Reconstruct result state
            DeleteAction.State.Completed(
                deleted = deletedItems.toSet(),
                skipped = skippedItems.toSet(),
            )
        }

        is DeleteOperationEvent.Error -> {
            // Error events are not converted to State - they should throw
            throw IllegalStateException("Cannot convert Error event to DeleteAction.State: $error")
        }
    }
}
