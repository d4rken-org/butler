package eu.darken.butler.explorer.core.operations

import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.files.APath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Instant

class OperationNotifier @Inject constructor() {
    private val publisher = MutableSharedFlow<Hint>()
    val hints: Flow<Hint> = publisher

    suspend fun publish(hint: Hint) {
        publisher.emit(hint)
    }

    sealed class Hint {
        abstract val operationId: OperationId
        abstract val timestamp: Instant
        abstract val affectedFolder: APath

        data class FilesAdded(
            override val operationId: OperationId,
            override val timestamp: Instant = Clock.System.now(),
            override val affectedFolder: APath,
            val files: List<APath>,
        ) : Hint()

        data class FilesRemoved(
            override val operationId: OperationId,
            override val timestamp: Instant = Clock.System.now(),
            override val affectedFolder: APath,
            val files: List<APath>,
        ) : Hint()

        data class FileRenamed(
            override val operationId: OperationId,
            override val timestamp: Instant = Clock.System.now(),
            override val affectedFolder: APath,
            val oldName: String,
            val newName: String,
        ) : Hint()

        data class FilesModified(
            override val operationId: OperationId,
            override val timestamp: Instant = Clock.System.now(),
            override val affectedFolder: APath,
            val files: List<APath>,
        ) : Hint()

        data class RefreshRequired(
            override val operationId: OperationId,
            override val timestamp: Instant = Clock.System.now(),
            override val affectedFolder: APath,
            val reason: String,
        ) : Hint()
    }

    companion object {
        private val TAG = logTag("OperationHints")
    }
}