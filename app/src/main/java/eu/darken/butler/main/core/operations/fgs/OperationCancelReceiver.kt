package eu.darken.butler.main.core.operations.fgs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.butler.common.coroutine.AppScope
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.workspace.core.operations.Operation
import eu.darken.butler.workspace.core.operations.OperationsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.uuid.Uuid

/**
 * Handles the "Cancel" action of an operation notification. Not exported; only reachable via the
 * explicit PendingIntent built in [OperationNotifications].
 */
@AndroidEntryPoint
class OperationCancelReceiver : BroadcastReceiver() {

    @Inject lateinit var operationsManager: OperationsManager
    @Inject @AppScope lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_OPERATION) return
        val raw = intent.getStringExtra(EXTRA_OPERATION_ID) ?: return
        val id = try {
            Operation.Id(Uuid.parse(raw))
        } catch (e: Exception) {
            log(TAG, WARN) { "Bad operation id in cancel intent: $raw" }
            return
        }

        log(TAG, INFO) { "Cancel requested for $id" }
        // operationsManager.cancel is suspend; appScope outlives this receiver call.
        val pending = goAsync()
        appScope.launch {
            try {
                operationsManager.cancel(id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val TAG = logTag("Operations", "FGS", "CancelReceiver")
    }
}
