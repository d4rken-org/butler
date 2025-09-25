package eu.darken.butler.explorer.core.filesystem

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
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

    suspend fun trackPathsRemoved(paths: Collection<APath>) {
//        emitPathEvent(FileSystemEvent.FilesRemoved(operationId = operationId, paths = paths.toSet()))
    }

    suspend fun trackPathsAdded(paths: Collection<APath>) {
//        emitPathEvent(FileSystemEvent.FilesAdded(operationId = operationId, paths = paths.toSet()))
    }

    suspend fun trackPathsModified(paths: Collection<APath>) {
//        emitPathEvent(FileSystemEvent.FilesModified(operationId = operationId, paths = paths.toSet()))
    }

    companion object {
        private val TAG = logTag("Explorer", "FileSystemHinter")
    }
}