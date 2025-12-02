package eu.darken.butler.common.trash

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrashCleanupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trashSettings: TrashSettings,
    @AppScope private val appScope: CoroutineScope,
) {

    fun schedule() {
        appScope.launch {
            val enabled = trashSettings.enabled.flow.first()

            if (enabled) {
                log(TAG, INFO) { "Scheduling trash cleanup worker" }

                val constraints = Constraints.Builder().apply {
                    setRequiresBatteryNotLow(true)
                    setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                }.build()

                val cleanupWork = PeriodicWorkRequestBuilder<TrashCleanupWorker>(
                    repeatInterval = 1,
                    repeatIntervalTimeUnit = TimeUnit.DAYS
                ).apply {
                    setConstraints(constraints)
                }.build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    cleanupWork
                )

                log(TAG, INFO) { "Trash cleanup worker scheduled successfully" }
            } else {
                log(TAG, INFO) { "Trash disabled, canceling cleanup worker" }
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            }
        }
    }

    fun cancel() {
        log(TAG, INFO) { "Canceling trash cleanup worker" }
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        private const val WORK_NAME = "trash_cleanup"
        private val TAG = logTag("Trash", "CleanupScheduler")

    }
}
