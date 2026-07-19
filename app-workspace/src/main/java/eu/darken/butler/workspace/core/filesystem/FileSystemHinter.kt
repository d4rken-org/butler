package eu.darken.butler.workspace.core.filesystem

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APathLookup
import eu.darken.butler.workspace.core.operations.Operation
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileSystemHinter @Inject constructor() {
    val events = MutableSharedFlow<FileSystemEvent>()

    suspend fun track(event: FileSystemEvent) {
        log(TAG, VERBOSE) { "track(): $event" }
        events.emit(event)
    }

    suspend fun trackPathsRemoved(operationId: Operation.Id, paths: Collection<APathLookup<*>>) {
        track(FileSystemEvent.Removed(operationId = operationId, paths = paths.toSet()))
    }

    suspend fun trackPathsAdded(operationId: Operation.Id, paths: Collection<APathLookup<*>>) {
        track(FileSystemEvent.Added(operationId = operationId, paths = paths.toSet()))
    }

    suspend fun trackPathsModified(operationId: Operation.Id, paths: Collection<APathLookup<*>>) {
        track(FileSystemEvent.Modified(operationId = operationId, paths = paths.toSet()))
    }

    companion object {
        private val TAG = logTag("Workspace", "FileSystemHinter")
    }
}