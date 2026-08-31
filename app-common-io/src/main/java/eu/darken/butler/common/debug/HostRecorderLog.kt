package eu.darken.butler.common.debug

import eu.darken.butler.common.debug.logging.FileLogger
import eu.darken.butler.common.debug.logging.Logging
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import java.io.File

/**
 * Host-side sink for the main process's bug-report recording: keeps a [FileLogger] writing to
 * `<recorderPath>/<logName>` in step with the report directory the main process pushes along with
 * the host options.
 *
 * The path is a level, not an edge. A host generation outlives a single recording, so it can go
 * straight from one report directory to the next without passing through null; every transition is
 * derived from the difference to the currently active path.
 *
 * [update] never throws — a failure here must not terminate the host's option collection for the
 * rest of the process.
 */
class HostRecorderLog(
    private val logName: String,
    private val tag: String,
) {

    private var activePath: String? = null
    private var activeLogger: FileLogger? = null

    @Synchronized
    fun update(recorderPath: String?) {
        if (recorderPath == activePath) return
        try {
            detach()
            if (recorderPath != null) attach(recorderPath)
        } catch (e: Throwable) {
            log(tag, ERROR) { "Failed to switch the recorder log to $recorderPath: ${e.asLog()}" }
        }
    }

    private fun detach() {
        val logger = activeLogger ?: return
        log(tag) { "Removing recorder log: $logger" }
        Logging.remove(logger)
        // Also stop it: removing alone leaks the writer and its report never gets the END marker.
        logger.stop()
        activeLogger = null
        activePath = null
    }

    private fun attach(recorderPath: String) {
        val logFile = File(recorderPath, logName)
        val logger = FileLogger(logFile)
        if (!logger.start()) {
            log(tag, ERROR) { "Recorder log failed to start: $logFile" }
            return
        }
        Logging.install(logger)
        activeLogger = logger
        activePath = recorderPath
        log(tag) { "Recorder log installed: $logFile" }
    }
}
