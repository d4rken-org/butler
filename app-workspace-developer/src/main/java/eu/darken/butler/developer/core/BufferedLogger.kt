package eu.darken.butler.developer.core

import eu.darken.butler.common.debug.logging.Logging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/**
 * A logger that buffers log lines in memory for live viewing.
 * Lines are stored with a maximum capacity to prevent OOM issues.
 */
class BufferedLogger(
    private val maxLines: Int = DEFAULT_MAX_LINES,
) : Logging.Logger {

    private val _logLines = MutableStateFlow<List<LogLine>>(emptyList())
    val logLines: StateFlow<List<LogLine>> = _logLines.asStateFlow()

    override fun log(
        priority: Logging.Priority,
        tag: String,
        message: String,
        metaData: Map<String, Any>?,
    ) {
        // Filter out developer workspace logs to prevent infinite loop
        if (EXCLUDED_TAG_PREFIXES.any { tag.contains(it) }) return

        val logLine = LogLine(
            timestamp = Clock.System.now(),
            priority = priority,
            tag = tag,
            message = message,
        )
        synchronized(_logLines) {
            val current = _logLines.value.toMutableList()
            current.add(logLine)
            if (current.size > maxLines) {
                _logLines.value = current.takeLast(maxLines)
            } else {
                _logLines.value = current
            }
        }
    }

    fun clear() {
        _logLines.value = emptyList()
    }

    data class LogLine(
        val timestamp: kotlin.time.Instant,
        val priority: Logging.Priority,
        val tag: String,
        val message: String,
    ) {
        fun format(): String = "${priority.shortLabel}/$tag: $message"
    }

    companion object {
        const val DEFAULT_MAX_LINES = 500
        private val EXCLUDED_TAG_PREFIXES = listOf(
            "Developer:Workspace",
            "Developer:LogRepo",
            "Developer:TestDataGenerator",
        )
    }
}
