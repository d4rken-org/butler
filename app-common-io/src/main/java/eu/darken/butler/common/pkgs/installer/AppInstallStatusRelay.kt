package eu.darken.butler.common.pkgs.installer

import android.content.Intent
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries `PackageInstaller` status callbacks from [AppInstallStatusReceiver] back to the install
 * that started them. [Status.requestId] is generated per session, so a stale callback from an
 * earlier install can never be mistaken for the current one.
 */
@Singleton
class AppInstallStatusRelay @Inject constructor() {

    private val _statuses = MutableSharedFlow<Status>(extraBufferCapacity = 32)
    val statuses: SharedFlow<Status> = _statuses.asSharedFlow()

    fun publish(status: Status) {
        if (!_statuses.tryEmit(status)) log(TAG, WARN) { "Dropped install status: $status" }
    }

    data class Status(
        val requestId: String,
        val status: Int,
        val message: String?,
        /** Set for `STATUS_PENDING_USER_ACTION`: the system's own install confirmation. */
        val userAction: Intent?,
    )

    companion object {
        private val TAG = logTag("Pkg", "Installer", "StatusRelay")
    }
}
