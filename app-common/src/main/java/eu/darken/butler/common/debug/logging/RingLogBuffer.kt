package eu.darken.butler.common.debug.logging

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * Always-on, in-memory circular log buffer that retains the most recent log lines so a crash or
 * [eu.darken.butler.common.debug.Bugs] report can attach the trail leading up to it — without the
 * opt-in debug Recorder running.
 *
 * Capture threshold is dynamic: [Logging.Priority.INFO] by default (cheap — VERBOSE/DEBUG message
 * lambdas are skipped via [Logging]'s fast-path), dropped to a lower priority while debug mode or
 * the Recorder is active (when the cost is already accepted) for a richer trail.
 *
 * Bounded by total bytes AND per-entry length, not line count, so a single huge stacktrace or path
 * dump cannot blow up memory.
 */
@Singleton
class RingLogBuffer @Inject constructor() : Logging.Logger {

    private val lock = Any()
    private val entries = ArrayDeque<String>()
    private var currentBytes = 0L

    @Volatile
    private var threshold: Logging.Priority = DEFAULT_THRESHOLD

    override fun isLoggable(priority: Logging.Priority): Boolean =
        priority.intValue >= threshold.intValue

    /**
     * Adjust how much detail is retained. Triggers [Logging.refreshLoggable] so the inline log
     * fast-path picks up the new threshold immediately.
     */
    fun setThreshold(priority: Logging.Priority) {
        if (threshold == priority) return
        threshold = priority
        Logging.refreshLoggable()
    }

    override fun log(priority: Logging.Priority, tag: String, message: String, metaData: Map<String, Any>?) {
        val line = buildString {
            append(Clock.System.now())
            append("  ")
            append(priority.shortLabel)
            append('/')
            append(tag)
            append(": ")
            append(message)
        }.let { if (it.length > MAX_ENTRY_CHARS) it.take(MAX_ENTRY_CHARS) + "…[truncated]" else it }

        val lineBytes = line.length.toLong()
        synchronized(lock) {
            entries.addLast(line)
            currentBytes += lineBytes
            while (currentBytes > MAX_TOTAL_BYTES && entries.isNotEmpty()) {
                val removed = entries.removeFirst()
                currentBytes -= removed.length.toLong()
            }
        }
    }

    /** Snapshot of the retained lines, oldest first, as a single newline-joined string. */
    fun snapshot(): String = synchronized(lock) { entries.joinToString("\n") }

    fun clear() = synchronized(lock) {
        entries.clear()
        currentBytes = 0L
    }

    override fun toString(): String = "RingLogBuffer(threshold=$threshold, bytes=$currentBytes)"

    companion object {
        val DEFAULT_THRESHOLD = Logging.Priority.INFO

        /** Truncate any single entry longer than this (chars ≈ bytes for the ASCII-dominant logs). */
        private const val MAX_ENTRY_CHARS = 4_000

        /** Evict oldest entries once the retained total exceeds this. */
        private const val MAX_TOTAL_BYTES = 512L * 1024L
    }
}
