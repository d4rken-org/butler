package eu.darken.butler.main.core.operations.fgs

import android.content.Intent
import android.os.IBinder
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.Service2
import javax.inject.Inject

/**
 * Thin foreground-service shell. Owns no observation logic: on start it hands itself to
 * [OperationFgsCoordinator], which immediately calls [android.app.Service.startForeground] with a
 * pre-built summary notification (so the mandatory startForeground call never races the 5s window).
 * All notification posting and the stop decision live in the coordinator.
 */
@AndroidEntryPoint
class OperationFgsService : Service2() {

    @Inject lateinit var coordinator: Lazy<OperationFgsCoordinator>

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        coordinator.get().onServiceStarted(this)
        return START_NOT_STICKY
    }

    /** Android 14 (API 34) shortService timeout. Kept so a timeout is handled the same whichever
     *  overload the platform invokes. */
    override fun onTimeout(startId: Int) {
        log(TAG, WARN) { "onTimeout($startId): dataSync FGS time budget exhausted" }
        coordinator.get().onServiceTimeout(this)
    }

    /** Android 15 (API 35) delivers the cumulative dataSync timeout via this overload. Stop
     *  gracefully; the operation itself continues in its app-scoped coroutine (unprotected, same as
     *  if no FGS were running). */
    override fun onTimeout(startId: Int, fgsType: Int) {
        log(TAG, WARN) { "onTimeout($startId, type=$fgsType): FGS time budget exhausted" }
        coordinator.get().onServiceTimeout(this)
    }

    override fun onDestroy() {
        coordinator.get().onServiceDestroyed()
        super.onDestroy()
    }

    companion object {
        private val TAG = logTag("Operations", "FGS", "Service")
    }
}
