package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.files.APath
import kotlin.time.Clock
import kotlin.time.Instant

sealed class FileSystemEvent {
    abstract val operationId: OperationId
    abstract val timestamp: Instant

    data class FilesAdded(
        override val operationId: OperationId,
        override val timestamp: Instant = Clock.System.now(),
        val paths: Set<APath>,
    ) : FileSystemEvent()

    data class FilesRemoved(
        override val operationId: OperationId,
        override val timestamp: Instant = Clock.System.now(),
        val paths: Set<APath>,
    ) : FileSystemEvent()

    data class FilesModified(
        override val operationId: OperationId,
        override val timestamp: Instant = Clock.System.now(),
        val paths: Set<APath>,
    ) : FileSystemEvent()
}