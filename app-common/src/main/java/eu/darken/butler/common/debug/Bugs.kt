package eu.darken.butler.common.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    var isDebug = false
    var isTrace = false

    /**
     * Debug experiment: defer Searcher folder-preview collages until scrolling settles.
     * Snapshot-backed so composables reading it recompose when the Developer toggle flips.
     */
    var deferSearcherPreviews by mutableStateOf(false)

    var processTag: String = "Default"

    private val TAG = logTag("Debug", "Bugs")
}