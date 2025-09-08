package eu.darken.butler.common

import eu.darken.butler.common.debug.logging.Logging
import kotlin.time.Clock

class JUnitLogger(private val minLogLevel: Logging.Priority = Logging.Priority.VERBOSE) : Logging.Logger {
    private val startTime = Clock.System.now()

    override fun isLoggable(priority: Logging.Priority): Boolean = priority.intValue >= minLogLevel.intValue

    override fun log(priority: Logging.Priority, tag: String, message: String, metaData: Map<String, Any>?) {
        val now = Clock.System.now()
        val ms = (startTime - now)
        println("${now.toEpochMilliseconds()} ($ms) ${priority.shortLabel}/$tag: $message")
    }

}
