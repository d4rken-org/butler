package eu.darken.butler.common.pkgs.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import javax.inject.Inject

/**
 * Status sink for system-installer sessions. Not exported; only reachable via the explicit
 * PendingIntent [AppInstaller] hands to `PackageInstaller.Session.commit`.
 */
@AndroidEntryPoint
class AppInstallStatusReceiver : BroadcastReceiver() {

    @Inject lateinit var relay: AppInstallStatusRelay

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (requestId == null) {
            log(TAG, WARN) { "Install status without a request id: $intent" }
            return
        }

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val userAction = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)

            else -> null
        }

        log(TAG, INFO) { "Install status $status for $requestId" }
        relay.publish(
            AppInstallStatusRelay.Status(
                requestId = requestId,
                status = status,
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                userAction = userAction,
            )
        )
    }

    companion object {
        private val TAG = logTag("Pkg", "Installer", "StatusReceiver")
        const val ACTION_INSTALL_STATUS = "eu.darken.butler.action.INSTALL_STATUS"
        const val EXTRA_REQUEST_ID = "eu.darken.butler.extra.INSTALL_REQUEST_ID"
    }
}
