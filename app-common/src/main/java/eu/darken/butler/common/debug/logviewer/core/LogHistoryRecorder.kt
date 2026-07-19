package eu.darken.butler.common.debug.logviewer.core

import eu.darken.butler.common.debug.logging.Logging
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory backlog shared by the floating log panel and the Developer workspace LOGS tab.
 *
 * A [Logging.Logger] backed by a bounded ring buffer. Because [Logging.Logger.log] is synchronous
 * and cannot suspend, this class does the cheapest possible work on the logging thread — a locked
 * deque append plus a conflated change signal — and leaves throttling/snapshotting to collectors.
 *
 * Bounded three ways: [BUFFER_CAP] rows, [MAX_TOTAL_CHARS] retained chars (evict-oldest), and
 * [MAX_ENTRY_CHARS] per raw message. Truncation happens BEFORE the multiline split, which also
 * bounds rows-per-message and thus lock-hold time for pathological input.
 *
 * Installation is ref-counted via [acquire]/[release]. The owner count and the
 * [Logging.install]/[Logging.remove] transition are serialized under [ownershipLock] so a racing
 * acquire/release pair cannot leave the logger installed with zero owners (or removed with one).
 * [ownershipLock] is distinct from [bufferLock]: [Logging.install] logs while holding the bus lock,
 * which re-enters [log] and takes [bufferLock] — the resulting order is always
 * ownership → bus → buffer, and [bufferLock] never acquires anything, so no inversion is possible.
 *
 * The buffer is retained across release/acquire within a process, so toggling the panel keeps
 * scrollback. There is deliberately no pause/freeze API: consumers that want a frozen view snapshot
 * locally and diff [Reading.totalLines] to count what they skipped — a global pause flag with
 * multiple independent owners can be left stuck by whichever owner disappears while paused.
 */
@Singleton
class LogHistoryRecorder @Inject constructor() : Logging.Logger {

    private val bufferLock = Any()
    private val ownershipLock = Any()
    private val buffer = ArrayDeque<LogLine>(BUFFER_CAP)
    private var idCounter = 0L
    private var currentChars = 0L
    private var activeOwners = 0

    /**
     * Minimum priority captured into the buffer — the global capture floor for ALL consumers,
     * owned by the app-level debug/trace wiring. Display-level filtering (the panel's level
     * selector) must stay consumer-local and never write this.
     */
    @Volatile var minPriority: Logging.Priority = Logging.Priority.DEBUG
        private set

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Conflated signal fired whenever the buffer changes. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    override fun isLoggable(priority: Logging.Priority): Boolean = priority.intValue >= minPriority.intValue

    /**
     * Adjust the capture floor. Triggers [Logging.refreshLoggable] so the inline log fast-path
     * (enabled mask) picks up the new threshold — without this, lowering the floor to VERBOSE
     * would capture nothing because call sites skip lambda evaluation entirely.
     */
    fun setMinPriority(priority: Logging.Priority) {
        if (minPriority == priority) return
        minPriority = priority
        Logging.refreshLoggable()
    }

    override fun log(priority: Logging.Priority, tag: String, message: String, metaData: Map<String, Any>?) {
        val capped = if (message.length > MAX_ENTRY_CHARS) {
            message.take(MAX_ENTRY_CHARS - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
        } else {
            message
        }
        synchronized(bufferLock) {
            // Split multiline messages (stack traces) so the cap and scrolling stay honest.
            for (line in capped.splitToSequence('\n')) {
                buffer.addLast(LogLine(id = idCounter++, priority = priority, tag = tag, message = line))
                currentChars += line.length + tag.length
                while (buffer.size > BUFFER_CAP || (currentChars > MAX_TOTAL_CHARS && buffer.isNotEmpty())) {
                    val removed = buffer.removeFirst()
                    currentChars -= removed.message.length + removed.tag.length
                }
            }
        }
        _changes.tryEmit(Unit)
    }

    /** Immutable snapshot of the current buffer contents. */
    fun snapshot(): List<LogLine> = synchronized(bufferLock) { buffer.toList() }

    /**
     * A consistent read for throttled UI snapshots. [Reading.totalLines] counts every physical line
     * ever recorded; consumers freeze a Reading and diff totalLines to show "N new lines" counters.
     */
    fun read(): Reading = synchronized(bufferLock) { Reading(buffer.toList(), idCounter) }

    /** Drop all buffered lines. Ids keep advancing so they stay monotonic across a clear. */
    fun clear() {
        synchronized(bufferLock) {
            buffer.clear()
            currentChars = 0L
        }
        _changes.tryEmit(Unit)
    }

    /** Register an active owner; installs the global logger on the first one. */
    fun acquire() {
        synchronized(ownershipLock) {
            if (activeOwners++ == 0) Logging.install(this)
        }
    }

    /** Release an active owner; removes the global logger after the last one. */
    fun release() {
        synchronized(ownershipLock) {
            when {
                activeOwners == 0 -> Unit
                --activeOwners == 0 -> Logging.remove(this)
            }
        }
    }

    data class Reading(
        val lines: List<LogLine>,
        val totalLines: Long,
    )

    companion object {
        const val BUFFER_CAP = 8000

        /** Hard per-message length bound (marker included), applied BEFORE the multiline split. */
        const val MAX_ENTRY_CHARS = 4_000

        /**
         * Evict oldest rows once retained chars (message+tag) exceed this. Counted in UTF-16 units
         * (2 bytes each on the heap), matching [eu.darken.butler.common.debug.logging.RingLogBuffer]'s
         * accounting approximation — the bound is about heap footprint, not encoded size.
         */
        const val MAX_TOTAL_CHARS = 2L * 1024L * 1024L

        private const val TRUNCATION_MARKER = "…[truncated]"
    }
}
