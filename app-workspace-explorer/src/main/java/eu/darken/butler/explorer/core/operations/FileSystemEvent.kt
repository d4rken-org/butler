package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import eu.darken.butler.workspace.core.operations.Operation
import kotlin.time.Clock
import kotlin.time.Instant

sealed class FileSystemEvent {
    abstract val operationId: Operation.Id
    abstract val timestamp: Instant

    data class FilesAdded(
        override val operationId: Operation.Id,
        override val timestamp: Instant = Clock.System.now(),
        val paths: Set<APath>,
    ) : FileSystemEvent()

    data class FilesRemoved(
        override val operationId: Operation.Id,
        override val timestamp: Instant = Clock.System.now(),
        val paths: Set<APath>,
    ) : FileSystemEvent()

    data class FilesModified(
        override val operationId: Operation.Id,
        override val timestamp: Instant = Clock.System.now(),
        val paths: Set<APath>,
    ) : FileSystemEvent()
}