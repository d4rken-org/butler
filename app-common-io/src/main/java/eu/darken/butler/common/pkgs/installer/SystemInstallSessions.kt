package eu.darken.butler.common.pkgs.installer

import android.content.Context
import android.content.pm.PackageInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform install sessions this package owns.
 *
 * A type of its own rather than a call into [PackageInstaller] where it is needed: an installer that
 * refuses to list or to abandon is not producible otherwise.
 */
@Singleton
class SystemInstallSessions @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val installer: PackageInstaller
        get() = context.packageManager.packageInstaller

    /** Every session created under this package, whether it was committed or not. */
    fun sessionIds(): List<Int> = installer.mySessions.map { it.sessionId }

    fun abandon(sessionId: Int) = installer.abandonSession(sessionId)
}
