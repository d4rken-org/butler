package eu.darken.butler.editor.ui.editor.text

import androidx.compose.ui.text.input.TextFieldValue
import eu.darken.butler.common.debug.logging.Logging.Priority.*
import eu.darken.butler.common.debug.logging.asLog
import eu.darken.butler.common.debug.logging.log
import eu.darken.butler.common.debug.logging.logTag
import eu.darken.butler.editor.core.engine.EditorEngine
import eu.darken.butler.editor.core.engine.TextPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

private val tag = logTag("Editor", "InputSession")

/**
 * One field-originated edit, expressed against the window the field last rebased on.
 *
 * [generation] identifies the field's current lineage: everything queued from the same lineage is
 * discarded once one of its deltas conflicts. [snapshotToken] is set ONLY on the first delta of a
 * generation - the document state it was computed against; successors chain on the token their
 * predecessor's acknowledgement returned, which the executor resolves at execution time.
 */
data class SessionDelta(
    val start: TextPosition,
    val end: TextPosition,
    val oldText: String,
    val newText: String,
    val caret: TextPosition,
    val generation: Long,
    val snapshotToken: EditorEngine.DocumentToken?,
)

/**
 * Lineage bookkeeping for the hidden IME field, which edits itself speculatively before the engine
 * has seen anything.
 *
 * The field keeps its own text (composition included) and each change is dispatched as a
 * [SessionDelta] mapped through the window the session last rebased on, evolved locally by every
 * unacknowledged edit since. Deltas are enqueued synchronously into the workspace's ordered edit
 * queue, so paste/undo/navigation can never overtake an already typed keystroke; their outcomes are
 * processed in emission order here.
 *
 * A conflict (a foreign mutation moved the document) invalidates the generation, discards its
 * queued descendants, and hands the caller an authoritative snapshot to rebuild from. All state is
 * touched from the composition thread and from ONE outcome-processing coroutine on [scope], never
 * concurrently.
 */
internal class EditorInputSession(
    scope: CoroutineScope,
    private val enqueueDelta: (SessionDelta) -> Deferred<EditorEngine.MutationResult>,
) {

    /** The window the session maps against: its identity plus the lines it was built from. */
    data class Snapshot(
        val token: EditorEngine.DocumentToken,
        val rangeStart: Long,
        val lines: List<String>,
        val startColumns: Map<Long, Long>,
    )

    private var snapshot: Snapshot? = null

    // The window as the field currently has it: the rebased lines plus every unacknowledged local
    // edit. Positions are mapped through THIS, not through the engine's window - typing "a", Enter,
    // "b" must place the "b" on the new line even though no ack arrived yet.
    private var localLines: List<String> = emptyList()
    private var localStartColumns: Map<Long, Long> = emptyMap()

    private var generation: Long = nextGeneration()
    private var firstOfGeneration = true
    private var unackedCount = 0
    private var pendingRebuild: EditorEngine.WindowSnapshot? = null

    private val _state = MutableStateFlow(0L)

    /**
     * Monotonic revision bumped on enqueue, acknowledgement, invalidation and cancellation: the
     * field's sync effect keys on it, so the idle transition after the last acknowledgement always
     * re-evaluates.
     */
    val state: StateFlow<Long> = _state.asStateFlow()

    /** True while edits are dispatched but not acknowledged - the window must stay pinned. */
    val hasUnackedWork: Boolean get() = unackedCount > 0

    val token: EditorEngine.DocumentToken? get() = snapshot?.token

    private data class Pending(
        val generation: Long,
        val outcome: Deferred<EditorEngine.MutationResult>,
    )

    // Unbounded, drained by ONE coroutine: outcomes must be processed in the order their deltas
    // were dispatched, or a conflict could invalidate a generation that a later ack revived.
    private val pending = Channel<Pending>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (entry in pending) {
                try {
                    handleOutcome(entry.generation, entry.outcome.await())
                } catch (e: CancellationException) {
                    // Our own cancellation ends the session; a cancelled OUTCOME must not wedge it
                    // in a permanently unacknowledged state.
                    if (!currentCoroutineContext().isActive) throw e
                    log(tag, WARN) { "Delta outcome was cancelled, invalidating the generation" }
                    if (entry.generation == generation) invalidate(null)
                } catch (e: Exception) {
                    log(tag, ERROR) { "Delta outcome failed - ${e.asLog()}" }
                    if (entry.generation == generation) invalidate(null)
                }
            }
        }
    }

    /** Adopts [snapshot] as the window every following delta is mapped against. */
    fun rebase(
        token: EditorEngine.DocumentToken,
        rangeStart: Long,
        lines: List<String>,
        startColumns: Map<Long, Long>,
    ) {
        snapshot = Snapshot(token, rangeStart, lines, startColumns)
        localLines = lines
        localStartColumns = startColumns
        generation = nextGeneration()
        firstOfGeneration = true
        unackedCount = 0
    }

    /** True when the session is already rebased on exactly this window. */
    fun matchesWindow(
        token: EditorEngine.DocumentToken,
        rangeStart: Long,
        lines: List<String>,
        startColumns: Map<Long, Long>,
    ): Boolean {
        val current = snapshot ?: return false
        return current.token == token &&
            current.rangeStart == rangeStart &&
            current.lines == lines &&
            current.startColumns == startColumns
    }

    /**
     * Flat offset of [position] in the field's CURRENT local text, or null when it is outside the
     * window (or no window is adopted). Lets the caller move the field caret locally - e.g. a tap
     * landing mid-burst, whose engine round-trip is still queued behind unacknowledged keystrokes.
     */
    fun localOffsetFor(position: TextPosition): Int? {
        val current = snapshot ?: return null
        return positionToFlatOffset(localLines, current.rangeStart, position, localStartColumns)
    }

    /**
     * Dispatches the field's [edit] (computed from [oldFieldText] into [newValue]) as a delta.
     * Start/end are mapped through the OLD local lines, the caret through the new ones; the local
     * mapping then advances so the next keystroke maps against the text the field really has.
     */
    fun onFieldEdit(oldFieldText: String, edit: TextEdit, newValue: TextFieldValue) {
        val current = snapshot ?: run {
            log(tag, WARN) { "Field edit without an adopted window, dropping it" }
            return
        }
        val oldLines = oldFieldText.split('\n')
        val newLines = newValue.text.split('\n')
        val start = flatOffsetToPosition(oldLines, current.rangeStart, edit.start, localStartColumns)
        val end = flatOffsetToPosition(oldLines, current.rangeStart, edit.end, localStartColumns)

        val shiftedColumns = shiftStartColumns(
            anchors = localStartColumns,
            startLine = start.line,
            endLine = end.line,
            lineDelta = newLines.size - oldLines.size,
        )
        val caret = flatOffsetToPosition(newLines, current.rangeStart, newValue.selection.end, shiftedColumns)

        val delta = SessionDelta(
            start = start,
            end = end,
            oldText = oldFieldText.substring(edit.start, edit.end),
            newText = edit.inserted,
            caret = caret,
            generation = generation,
            snapshotToken = if (firstOfGeneration) current.token else null,
        )
        localLines = newLines
        localStartColumns = shiftedColumns
        firstOfGeneration = false
        unackedCount++
        bumpRevision()
        pending.trySend(Pending(delta.generation, enqueueDelta(delta)))
    }

    /**
     * The authoritative state a conflicted (or failed) generation must rebuild the field from,
     * consumed once. Null when nothing needs rebuilding.
     */
    fun consumePendingRebuild(): EditorEngine.WindowSnapshot? = pendingRebuild.also { pendingRebuild = null }

    /**
     * Abandons the current lineage without a rebuild source: dispatched deltas stay dispatched but
     * their acknowledgements are discarded (e.g. the document went read-only mid-burst).
     */
    fun cancelPending() {
        invalidate(null)
    }

    private fun handleOutcome(deltaGeneration: Long, result: EditorEngine.MutationResult) {
        if (deltaGeneration != generation) {
            // A late acknowledgement from an abandoned lineage: dropping it is what keeps a
            // conflict's rebuild from being undone by its own descendants.
            log(tag, VERBOSE) { "Dropping outcome from generation $deltaGeneration (current $generation)" }
            return
        }
        when (result) {
            is EditorEngine.MutationResult.Applied -> {
                unackedCount = (unackedCount - 1).coerceAtLeast(0)
                bumpRevision()
            }
            is EditorEngine.MutationResult.Conflict -> {
                log(tag) { "Delta conflicted, rebuilding the field from the engine snapshot" }
                invalidate(result.snapshot)
            }
            is EditorEngine.MutationResult.Failed -> {
                log(tag, WARN) { "Delta failed - ${result.error.asLog()}" }
                invalidate(null)
            }
        }
    }

    private fun invalidate(rebuildFrom: EditorEngine.WindowSnapshot?) {
        generation = nextGeneration()
        firstOfGeneration = true
        unackedCount = 0
        snapshot = null
        localLines = emptyList()
        localStartColumns = emptyMap()
        if (rebuildFrom != null) pendingRebuild = rebuildFrom
        bumpRevision()
    }

    private fun bumpRevision() {
        _state.value = _state.value + 1
    }

    companion object {
        // Generations are unique across sessions: the executor's dead-generation bookkeeping
        // outlives a single field, so a restarted session must not inherit a dead lineage's id.
        private val generationSource = AtomicLong(0L)

        private fun nextGeneration(): Long = generationSource.incrementAndGet()

        /**
         * Moves per-line window anchors to the line numbers they occupy AFTER an edit spanning
         * [startLine]..[endLine] changed the line count by [lineDelta]: lines above keep theirs,
         * lines swallowed by the edit lose theirs, lines below shift.
         */
        internal fun shiftStartColumns(
            anchors: Map<Long, Long>,
            startLine: Long,
            endLine: Long,
            lineDelta: Int,
        ): Map<Long, Long> {
            if (anchors.isEmpty()) return anchors
            if (lineDelta == 0 && startLine == endLine) return anchors
            return buildMap {
                for ((line, anchor) in anchors) {
                    when {
                        line <= startLine -> put(line, anchor)
                        line <= endLine -> Unit // merged into the edit's start line
                        else -> put(line + lineDelta, anchor)
                    }
                }
            }
        }
    }
}
