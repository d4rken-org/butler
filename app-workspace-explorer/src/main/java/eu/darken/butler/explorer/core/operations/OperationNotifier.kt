package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Hints about operations that are about to happen or have just happened.
 * Used to provide optimistic UI updates before file system changes are confirmed.
 */
class OperationNotifier @Inject constructor() {
    private val publisher = MutableSharedFlow<Hint>()
    val hints: Flow<Hint> = publisher

    suspend fun publish(hint: Hint) {
        publisher.emit(hint)
    }

    sealed class Hint {
        abstract val targetPath: APath
        abstract val timestamp: Instant
        abstract val operationId: OperationId

        data class FilesAdded(
            override val targetPath: APath,
            val files: List<APath>,
            override val operationId: OperationId,
            override val timestamp: Instant = Clock.System.now(),
        ) : Hint()

        data class FilesRemoved(
            override val targetPath: APath,
            val files: List<APath>,
            override val operationId: OperationId,
            override val timestamp: Instant = Clock.System.now(),
        ) : Hint()

        data class FilesMoved(
            override val targetPath: APath,
            val sourcePath: APath,
            val files: List<APath>,
            override val operationId: OperationId,
            override val timestamp: Instant = Clock.System.now(),
        ) : Hint() {

            fun asRemovalHint(): FilesRemoved = FilesRemoved(
                targetPath = sourcePath,
                files = files,
                operationId = operationId,
                timestamp = timestamp
            )

            fun asAdditionHint(): FilesAdded = FilesAdded(
                targetPath = targetPath,
                files = files,
                operationId = operationId,
                timestamp = timestamp
            )
        }

        data class FileRenamed(
            override val targetPath: APath,
            val oldName: String,
            val newName: String,
            override val operationId: OperationId,
            override val timestamp: Instant = Clock.System.now(),
        ) : Hint()

        data class FilesModified(
            override val targetPath: APath,
            val files: List<APath>,
            override val operationId: OperationId,
            override val timestamp: Instant = Clock.System.now(),
        ) : Hint()

        data class RefreshRequired(
            override val targetPath: APath,
            val reason: String,
            override val operationId: OperationId,
            override val timestamp: Instant = Clock.System.now(),
        ) : Hint()
    }

    companion object {
        private val TAG = logTag("OperationHints")
    }
}