package eu.darken.butler.common.debug.bugreport

import android.app.Application
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import eu.darken.butler.common.debug.AutomaticBugReporter
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy-first [AutomaticBugReporter]: instead of sending telemetry off-device, manual
 * [eu.darken.butler.common.debug.Bugs.report] calls are stored locally via [BugReportRepo] for the
 * user to review and optionally share. Bound for all flavors.
 */
@Singleton
class LocalBugReporter @Inject constructor(
    private val bugReportRepo: BugReportRepo,
) : AutomaticBugReporter {

    override fun setup(application: Application) {
        // Nothing to initialize — capture happens via the always-on log buffer + crash handler.
    }

    override fun leaveBreadCrumb(crumb: String) {
        log(TAG) { "Breadcrumb: $crumb" }
    }

    override fun notify(throwable: Throwable) {
        bugReportRepo.captureReport(throwable)
    }

    @Module
    @InstallIn(SingletonComponent::class)
    abstract class ReporterModule {
        @Binds
        @Singleton
        abstract fun reporter(impl: LocalBugReporter): AutomaticBugReporter
    }

    companion object {
        private val TAG = logTag("Debug", "LocalBugReporter")
    }
}
