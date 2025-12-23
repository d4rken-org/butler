package eu.darken.butler.common.files.local.service

import android.content.Intent
import android.os.IBinder
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.common.ui.Service2
import javax.inject.Inject

/**
 * Bound service running in a separate process (`:local`) for file operations.
 *
 * This process isolation protects the main app from Android's `vold` daemon,
 * which sends SIGINT to kill any process with open file handles when storage
 * is suddenly disconnected (e.g., USB unplugged). If vold kills this process,
 * the main app survives and can show an error message to the user.
 *
 * No foreground notification is needed - the separate process provides protection
 * regardless of foreground status. Bound services inherit priority from their client.
 */
@AndroidEntryPoint
class LocalService : Service2() {

    @Inject lateinit var serviceHost: Lazy<LocalServiceHost>

    override fun onBind(intent: Intent?): IBinder {
        log(TAG) { "onBind(intent=$intent)" }
        return serviceHost.get()
    }

    override fun onCreate() {
        super.onCreate()
        log(TAG) { "onCreate() - service process started" }
    }

    override fun onDestroy() {
        log(TAG) { "onDestroy()" }
        super.onDestroy()
    }

    companion object {
        private val TAG = logTag("Local", "Service")
    }
}
