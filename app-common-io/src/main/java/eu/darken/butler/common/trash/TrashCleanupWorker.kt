package eu.darken.butler.common.trash

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag

@HiltWorker
class TrashCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val trashManager: TrashManager,
) : CoroutineWorker(appContext, workerParams) {

    private val tag = logTag("Trash", "CleanupWorker")

    override suspend fun doWork(): Result {
        log(tag, INFO) { "Starting recycle bin cleanup..." }

        return try {
            val deletedCount = trashManager.cleanupExpired()
            log(tag, INFO) { "Cleanup complete: Deleted $deletedCount expired items" }
            Result.success()
        } catch (e: Exception) {
            log(tag, ERROR) { "Cleanup failed: ${e.asLog()}" }
            Result.retry()
        }
    }
}
