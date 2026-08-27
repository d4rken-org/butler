package eu.darken.butler.common.debug

import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag

object Bugs {
    var reporter: AutomaticBugReporter? = null

    fun report(throwable: Throwable) {
        log(TAG, VERBOSE) { "Reporting $throwable" }

        reporter?.notify(throwable) ?: run {
            log(TAG, WARN) { "Bug tracking not initialized yet." }
        }
    }

    fun leaveBreadCrumb(crumb: String) {
        log(TAG, VERBOSE) { "Leaving crumb $crumb" }

        reporter?.leaveBreadCrumb(crumb) ?: run {
            log(TAG, WARN) { "Bug tracking not initialized yet." }
        }
    }

    // Written from App's settings observer, read from arbitrary flow-collection threads that gate
    // their logging on them.
    @Volatile
    var isDebug = false

    @Volatile
    var isTrace = false

    var processTag: String = "Default"

    private val TAG = logTag("Debug", "Bugs")
}